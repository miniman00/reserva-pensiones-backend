package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeSessionRepository;
import uy.pensiones.security.BackofficePrincipal;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Step-up authentication para acciones administrativas de alto impacto.
 * La confirmación vive en la sesión persistente para funcionar también con múltiples instancias.
 */
@Service
public class BackofficeReauthenticationService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final BackofficeSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final BackofficeSystemSuperAdminService systemAdmin;
    private final Duration ttl;

    public BackofficeReauthenticationService(
            BackofficeSessionRepository sessions,
            PasswordEncoder passwordEncoder,
            BackofficeSystemSuperAdminService systemAdmin,
            @Value("${app.backoffice.reauthentication.ttl:PT10M}") Duration ttl
    ) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("app.backoffice.reauthentication.ttl debe ser mayor a 0 y no superar 1 hora");
        }
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.systemAdmin = systemAdmin;
        this.ttl = ttl;
    }

    @Transactional(readOnly = true)
    public ReauthenticationStatus status(BackofficePrincipal principal) {
        BackofficeSession session = session(principal);
        OffsetDateTime at = session.getLastReauthenticatedAt();
        OffsetDateTime expiresAt = at == null ? null : at.plus(ttl);
        return new ReauthenticationStatus(at, expiresAt, expiresAt != null && expiresAt.isAfter(now()));
    }

    @Transactional
    public ReauthenticationStatus reauthenticate(BackofficePrincipal principal, String rawPassword) {
        BackofficeSession session = session(principal);
        OffsetDateTime now = now();
        if (session.getReauthLockedUntil() != null && session.getReauthLockedUntil().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos de confirmación. Inténtalo nuevamente más tarde.");
        }

        String password = rawPassword == null ? "" : rawPassword;
        BackofficeUser user = session.getBackofficeUser();
        boolean matches = false;
        if (BackofficeAuthenticationService.fitsBcrypt(password)) {
            if (user.isSystemManaged()) {
                matches = systemAdmin.isConfigured()
                        && systemAdmin.isSystemUsername(user.getUsername())
                        && systemAdmin.matches(password);
            } else {
                matches = passwordEncoder.matches(password, user.getPasswordHash());
            }
        }

        if (!matches) {
            int failed = session.getReauthFailedAttempts() + 1;
            session.setReauthFailedAttempts(failed);
            if (failed >= MAX_FAILED_ATTEMPTS) {
                session.setReauthFailedAttempts(0);
                session.setReauthLockedUntil(now.plus(LOCK_DURATION));
            }
            sessions.save(session);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña no es correcta");
        }

        session.setLastReauthenticatedAt(now);
        session.setReauthFailedAttempts(0);
        session.setReauthLockedUntil(null);
        sessions.save(session);
        return new ReauthenticationStatus(now, now.plus(ttl), true);
    }

    @Transactional(readOnly = true)
    public void requireRecent(BackofficePrincipal principal) {
        ReauthenticationStatus status = status(principal);
        if (!status.fresh()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "Esta acción requiere volver a confirmar tu contraseña");
        }
    }

    private BackofficeSession session(BackofficePrincipal principal) {
        if (principal == null || principal.sessionId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La sesión de Backoffice ya no es válida");
        }
        BackofficeSession session = sessions.findById(principal.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La sesión de Backoffice ya no es válida"));
        BackofficeUser user = session.getBackofficeUser();
        if (user == null || !user.getId().equals(principal.id())
                || !user.isActive() || session.getSessionVersion() != principal.sessionVersion()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La sesión de Backoffice ya no es válida");
        }
        return session;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record ReauthenticationStatus(OffsetDateTime reauthenticatedAt,
                                         OffsetDateTime reauthenticationExpiresAt,
                                         boolean fresh) {}
}
