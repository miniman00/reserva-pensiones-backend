package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeMfaRecoveryCodeRepository;
import uy.pensiones.repo.BackofficeSessionRepository;
import uy.pensiones.repo.BackofficeUserRepository;
import uy.pensiones.security.BackofficePrincipal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class BackofficeMfaBreakGlassService {
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$(?:0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}$");
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(30);
    private static final Duration MAX_WINDOW = Duration.ofHours(24);

    private final boolean enabled;
    private final String tokenHash;
    private final String credentialFingerprint;
    private final OffsetDateTime expiresAt;
    private final PasswordEncoder passwordEncoder;
    private final BackofficeSystemSuperAdminService systemAdmin;
    private final BackofficeUserRepository users;
    private final BackofficeSessionRepository sessions;
    private final BackofficeMfaRecoveryCodeRepository recoveryCodes;
    private final BackofficeMfaPolicy mfaPolicy;
    private final BackofficeReauthenticationService reauthentication;
    private final AdminAuditService audit;
    private final MailService mail;
    private final JdbcTemplate jdbc;

    public BackofficeMfaBreakGlassService(
            PasswordEncoder passwordEncoder,
            BackofficeSystemSuperAdminService systemAdmin,
            BackofficeUserRepository users,
            BackofficeSessionRepository sessions,
            BackofficeMfaRecoveryCodeRepository recoveryCodes,
            BackofficeMfaPolicy mfaPolicy,
            BackofficeReauthenticationService reauthentication,
            AdminAuditService audit,
            MailService mail,
            JdbcTemplate jdbc,
            @Value("${app.backoffice.mfa.break-glass.enabled:false}") boolean enabled,
            @Value("${app.backoffice.mfa.break-glass.token-hash:}") String tokenHash,
            @Value("${app.backoffice.mfa.break-glass.expires-at:}") String expiresAt
    ) {
        this.passwordEncoder = passwordEncoder;
        this.systemAdmin = systemAdmin;
        this.users = users;
        this.sessions = sessions;
        this.recoveryCodes = recoveryCodes;
        this.mfaPolicy = mfaPolicy;
        this.reauthentication = reauthentication;
        this.audit = audit;
        this.mail = mail;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.tokenHash = tokenHash == null ? "" : tokenHash.trim();
        this.expiresAt = enabled ? parseExpiry(expiresAt) : null;
        this.credentialFingerprint = this.tokenHash.isBlank() ? null : sha256(this.tokenHash);
        validateConfiguration();
    }

    @Transactional(readOnly = true)
    public Status status(BackofficePrincipal principal) {
        BackofficeUser user = requireSystemSuperAdmin(principal, false);
        OffsetDateTime now = now();
        boolean used = credentialFingerprint != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from backoffice_mfa_break_glass_uses where credential_fingerprint = ?)",
                Boolean.class, credentialFingerprint));
        boolean expired = expiresAt != null && !expiresAt.isAfter(now);
        boolean locked = user.getMfaBreakGlassLockedUntil() != null && user.getMfaBreakGlassLockedUntil().isAfter(now);
        boolean available = enabled && !used && !expired && !locked && mfaPolicy.enrolled(user);
        return new Status(enabled, available, expiresAt, used, expired, locked ? user.getMfaBreakGlassLockedUntil() : null);
    }

    @Transactional
    public Status consume(BackofficePrincipal principal, String rawToken, String rawReason) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El mecanismo de recuperación extraordinaria no está habilitado");
        reauthentication.requireRecent(principal);
        BackofficeUser user = requireSystemSuperAdmin(principal, true);
        OffsetDateTime now = now();
        checkLock(user, now);
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.GONE, "La credencial break-glass está vencida");
        }
        if (credentialFingerprint == null || alreadyUsed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La credencial break-glass ya fue consumida o no está disponible");
        }
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.length() < 32 || token.length() > 72 || !passwordEncoder.matches(token, tokenHash)) {
            registerFailure(user, now);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credencial break-glass inválida");
        }
        String reason = audit.requireReason(rawReason);
        if (!mfaPolicy.enrolled(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta no tiene MFA configurado; no es necesario usar break-glass");
        }

        int inserted = jdbc.update("""
                insert into backoffice_mfa_break_glass_uses(credential_fingerprint, backoffice_user_id, used_at, reason)
                values (?, ?, ?, ?)
                on conflict (credential_fingerprint) do nothing
                """, credentialFingerprint, user.getId(), now, reason);
        if (inserted != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La credencial break-glass ya fue consumida");
        }

        Map<String, Object> before = Map.of(
                "mfaEnabled", true,
                "mfaEnabledAt", user.getMfaEnabledAt() == null ? "" : user.getMfaEnabledAt().toString()
        );
        clearMfa(user);
        users.save(user);
        recoveryCodes.deleteByBackofficeUser_Id(user.getId());

        if (principal.sessionId() != null) {
            BackofficeSession session = sessions.findById(principal.sessionId()).orElse(null);
            if (session != null && session.getBackofficeUser().getId().equals(user.getId())) {
                session.setMfaVerifiedAt(null);
                sessions.save(session);
            }
        }

        audit.record(user, AdminAuditAction.ADMIN_USE_MFA_BREAK_GLASS, AdminAuditEntityType.BACKOFFICE_USER,
                user.getId(), before, Map.of("mfaEnabled", false, "breakGlassCredentialFingerprint", credentialFingerprint), reason);
        notifySecurity(user);
        return status(principal);
    }

    private BackofficeUser requireSystemSuperAdmin(BackofficePrincipal principal, boolean lock) {
        if (principal == null || principal.id() == null) throw unauthorized();
        BackofficeUser user = lock
                ? users.findByIdForUpdate(principal.id()).orElseThrow(this::unauthorized)
                : users.findById(principal.id()).orElseThrow(this::unauthorized);
        if (!user.isActive() || !user.isSystemManaged() || user.getRole() != BackofficeRole.SUPER_ADMIN
                || !systemAdmin.isSystemUsername(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Break-glass está reservado al SUPER_ADMIN administrado por infraestructura");
        }
        return user;
    }

    private void clearMfa(BackofficeUser user) {
        user.setMfaSecretCiphertext(null);
        user.setMfaPendingSecretCiphertext(null);
        user.setMfaPendingExpiresAt(null);
        user.setMfaEnabledAt(null);
        user.setMfaLastVerifiedAt(null);
        user.setMfaLastAcceptedCounter(null);
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
        user.setMfaBreakGlassFailedAttempts(0);
        user.setMfaBreakGlassLockedUntil(null);
    }

    private void registerFailure(BackofficeUser user, OffsetDateTime now) {
        int attempts = user.getMfaBreakGlassFailedAttempts() + 1;
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setMfaBreakGlassFailedAttempts(0);
            user.setMfaBreakGlassLockedUntil(now.plus(LOCK_DURATION));
        } else {
            user.setMfaBreakGlassFailedAttempts(attempts);
        }
        users.save(user);
    }

    private void checkLock(BackofficeUser user, OffsetDateTime now) {
        if (user.getMfaBreakGlassLockedUntil() != null && user.getMfaBreakGlassLockedUntil().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Break-glass está temporalmente bloqueado por demasiados intentos fallidos");
        }
        if (user.getMfaBreakGlassLockedUntil() != null) {
            user.setMfaBreakGlassLockedUntil(null);
            user.setMfaBreakGlassFailedAttempts(0);
        }
    }

    private boolean alreadyUsed() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from backoffice_mfa_break_glass_uses where credential_fingerprint = ?)",
                Boolean.class, credentialFingerprint));
    }

    private void notifySecurity(BackofficeUser user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) return;
        try {
            mail.sendBackofficeSecurityNotice(user.getEmail(), user.getDisplayName(),
                    "Se utilizó el mecanismo extraordinario break-glass para resetear MFA. Debes enrolar un autenticador nuevo inmediatamente. Si no realizaste esta acción, rota las credenciales de infraestructura y revisa la auditoría.");
        } catch (RuntimeException ignored) {
            // La recuperación ya quedó auditada; un fallo del correo no debe dejar el estado a medias.
        }
    }

    private void validateConfiguration() {
        if (!enabled) return;
        if (!systemAdmin.isConfigured()) {
            throw new IllegalStateException("Break-glass requiere que el SUPER_ADMIN de sistema esté configurado");
        }
        if (!BCRYPT_HASH.matcher(tokenHash).matches()) {
            throw new IllegalStateException("APP_BACKOFFICE_MFA_BREAK_GLASS_TOKEN_HASH debe contener un hash BCrypt válido");
        }
        if (expiresAt == null) {
            throw new IllegalStateException("APP_BACKOFFICE_MFA_BREAK_GLASS_EXPIRES_AT es obligatorio cuando break-glass está habilitado");
        }
        OffsetDateTime now = now();
        if (!expiresAt.isAfter(now)) {
            throw new IllegalStateException("APP_BACKOFFICE_MFA_BREAK_GLASS_EXPIRES_AT debe estar en el futuro");
        }
        if (Duration.between(now, expiresAt).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalStateException("La ventana break-glass no puede superar 24 horas");
        }
    }

    private OffsetDateTime parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException("APP_BACKOFFICE_MFA_BREAK_GLASS_EXPIRES_AT debe ser ISO-8601 con zona, por ejemplo 2026-08-31T23:30:00-03:00", ex);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no está disponible", ex);
        }
    }

    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión administrativa inválida"); }

    public record Status(boolean enabled, boolean available, OffsetDateTime expiresAt,
                         boolean credentialUsed, boolean expired, OffsetDateTime lockedUntil) {}
}
