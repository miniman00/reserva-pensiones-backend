package uy.pensiones.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeSessionRepository;
import uy.pensiones.security.BackofficePrincipal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

/**
 * Emite y valida una cookie opaca propia del Backoffice.
 * El valor de autenticación nunca se persiste: en BD sólo queda SHA-256(token).
 */
@Service
public class BackofficeSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    private final BackofficeSessionRepository sessions;
    private final Duration ttl;
    private final Duration idleTimeout;
    private final String cookieName;
    private final String cookiePath;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final BackofficeMfaPolicy mfaPolicy;

    public BackofficeSessionService(
            BackofficeSessionRepository sessions,
            @Value("${app.backoffice.session.ttl:PT8H}") Duration ttl,
            @Value("${app.backoffice.session.idle-timeout:PT30M}") Duration idleTimeout,
            @Value("${app.backoffice.session.cookie-name:PENSIONES_BACKOFFICE_SESSION}") String cookieName,
            @Value("${app.backoffice.session.cookie-path:/api}") String cookiePath,
            @Value("${app.backoffice.session.cookie-secure:false}") boolean cookieSecure,
            @Value("${app.backoffice.session.cookie-same-site:Strict}") String cookieSameSite,
            BackofficeMfaPolicy mfaPolicy
    ) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalStateException("app.backoffice.session.ttl debe ser mayor a 0 y no superar 7 días");
        }
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative() || idleTimeout.compareTo(ttl) > 0) {
            throw new IllegalStateException("app.backoffice.session.idle-timeout debe ser mayor a 0 y no superar el TTL absoluto");
        }
        this.sessions = sessions;
        this.ttl = ttl;
        this.idleTimeout = idleTimeout;
        this.cookieName = requireCookieValue(cookieName, "cookie-name");
        this.cookiePath = requireCookieValue(cookiePath, "cookie-path");
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = normalizeSameSite(cookieSameSite);
        this.mfaPolicy = mfaPolicy;
    }

    @Transactional
    public SessionCredentials create(BackofficeUser user, HttpServletResponse response) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("La sesión de Backoffice requiere un usuario persistido");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sessions.deleteByExpiresAtBefore(now);
        // Para cuentas internas privilegiadas mantenemos una única sesión activa por usuario.
        sessions.deleteByBackofficeUser_Id(user.getId());

        String rawToken = randomToken();
        String csrfToken = randomToken();
        BackofficeSession saved = sessions.save(BackofficeSession.builder()
                .backofficeUser(user)
                .tokenHash(sha256(rawToken))
                .csrfToken(csrfToken)
                .sessionVersion(user.getSessionVersion())
                .createdAt(now)
                .lastSeenAt(now)
                .lastReauthenticatedAt(now)
                .mfaVerifiedAt(mfaPolicy.secondFactorNeeded(user) ? null : now)
                .reauthFailedAttempts(0)
                .expiresAt(now.plus(ttl))
                .build());

        writeCookie(response, rawToken, ttl);
        return new SessionCredentials(BackofficePrincipal.from(user, saved.getId(), csrfToken, mfaPolicy.satisfied(user, saved)), csrfToken);
    }

    @Transactional
    public Optional<BackofficePrincipal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) return Optional.empty();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BackofficeSession session = sessions.findByTokenHash(sha256(rawToken)).orElse(null);
        if (session == null) return Optional.empty();

        BackofficeUser user = session.getBackofficeUser();
        OffsetDateTime lastActivity = session.getLastSeenAt() == null ? session.getCreatedAt() : session.getLastSeenAt();
        boolean valid = session.getExpiresAt().isAfter(now)
                && lastActivity != null
                && lastActivity.plus(idleTimeout).isAfter(now)
                && user.isActive()
                && session.getSessionVersion() == user.getSessionVersion();
        if (!valid) {
            sessions.delete(session);
            return Optional.empty();
        }

        if (session.getLastSeenAt() == null || session.getLastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            session.setLastSeenAt(now);
            sessions.save(session);
        }
        return Optional.of(BackofficePrincipal.from(user, session.getId(), session.getCsrfToken(), mfaPolicy.satisfied(user, session)));
    }

    @Transactional
    public void revoke(BackofficePrincipal principal, HttpServletResponse response) {
        if (principal != null && principal.sessionId() != null) {
            sessions.deleteById(principal.sessionId());
        }
        clearCookie(response);
    }

    public String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    public void clearCookie(HttpServletResponse response) {
        writeCookie(response, "", Duration.ZERO);
    }

    public boolean csrfMatches(BackofficePrincipal principal, String provided) {
        if (principal == null || principal.csrfToken() == null || provided == null) return false;
        return MessageDigest.isEqual(
                principal.csrfToken().getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void writeCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .path(cookiePath)
                .sameSite(cookieSameSite)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no está disponible", ex);
        }
    }

    private String requireCookieValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("app.backoffice.session." + field + " no puede estar vacío");
        }
        return value.trim();
    }

    private String normalizeSameSite(String value) {
        String normalized = value == null ? "Strict" : value.trim();
        if (!(normalized.equalsIgnoreCase("Strict")
                || normalized.equalsIgnoreCase("Lax")
                || normalized.equalsIgnoreCase("None"))) {
            throw new IllegalStateException("app.backoffice.session.cookie-same-site debe ser Strict, Lax o None");
        }
        if (normalized.equalsIgnoreCase("None") && !cookieSecure) {
            throw new IllegalStateException("SameSite=None requiere app.backoffice.session.cookie-secure=true");
        }
        return normalized.substring(0, 1).toUpperCase() + normalized.substring(1).toLowerCase();
    }

    public record SessionCredentials(BackofficePrincipal principal, String csrfToken) {}
}
