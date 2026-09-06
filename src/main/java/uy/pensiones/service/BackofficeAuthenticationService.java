package uy.pensiones.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.repo.BackofficeUserRepository;
import uy.pensiones.security.BackofficePrincipal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@Service
public class BackofficeAuthenticationService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;
    private static final int FAILURE_OBSERVATION_MINUTES = 15;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;
    private final BackofficeUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BackofficeSystemSuperAdminService systemAdmin;
    private final BackofficeSessionService sessions;
    private final AdminAuditService audit;
    private final String dummyBcryptHash;

    public BackofficeAuthenticationService(BackofficeUserRepository users,
                                           PasswordEncoder passwordEncoder,
                                           BackofficeSystemSuperAdminService systemAdmin,
                                           BackofficeSessionService sessions,
                                           AdminAuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.systemAdmin = systemAdmin;
        this.sessions = sessions;
        this.audit = audit;
        // Hash efímero con el mismo costo que las credenciales reales para cuentas inexistentes.
        this.dummyBcryptHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    @Transactional
    public BackofficeSessionService.SessionCredentials login(String rawUsername,
                                                              String rawPassword,
                                                              HttpServletResponse response) {
        String username = normalizeUsername(rawUsername);
        String password = rawPassword == null ? "" : rawPassword;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (!fitsBcrypt(password)) {
            // BCrypt sólo admite 72 bytes. Consumimos igualmente un BCrypt dummy para no crear un atajo temporal.
            passwordEncoder.matches("oversized-login-attempt", dummyBcryptHash);
            throw invalidCredentials();
        }

        BackofficeUser user = users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyBcryptHash);
            throw invalidCredentials();
        }

        boolean passwordMatches;
        if (user.isSystemManaged()) {
            // Una cuenta systemManaged nunca puede caer al hash almacenado en BD.
            if (systemAdmin.isConfigured() && systemAdmin.isSystemUsername(username)) {
                passwordMatches = systemAdmin.matches(password);
            } else {
                passwordEncoder.matches(password, dummyBcryptHash);
                passwordMatches = false;
            }
        } else {
            passwordMatches = passwordEncoder.matches(password, user.getPasswordHash());
        }

        // Evitamos revelar por HTTP si la cuenta existe, está deshabilitada o está bloqueada.
        if (!user.isActive() || (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now))) {
            throw invalidCredentials();
        }
        if (user.getLockedUntil() != null) {
            user.setLockedUntil(null);
            user.setFailedLoginAttempts(0);
            user.setLastFailedLoginAt(null);
        }

        if (!passwordMatches) {
            boolean sameObservationWindow = user.getLastFailedLoginAt() != null
                    && user.getLastFailedLoginAt().plusMinutes(FAILURE_OBSERVATION_MINUTES).isAfter(now);
            int failed = sameObservationWindow ? user.getFailedLoginAttempts() + 1 : 1;
            user.setFailedLoginAttempts(failed);
            user.setLastFailedLoginAt(now);
            if (failed >= MAX_FAILED_ATTEMPTS) {
                user.setLockedUntil(now.plusMinutes(LOCK_MINUTES));
                user.setFailedLoginAttempts(0);
            }
            users.save(user);
            throw invalidCredentials();
        }

        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        user = users.save(user);
        return sessions.create(user, response);
    }

    @Transactional(readOnly = true)
    public BackofficeUser current(BackofficePrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No hay una sesión de Backoffice activa");
        }
        BackofficeUser user = users.findById(principal.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La sesión de Backoffice ya no es válida"));
        if (!user.isActive() || user.getSessionVersion() != principal.sessionVersion()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La sesión de Backoffice ya no es válida");
        }
        return user;
    }

    @Transactional
    public BackofficeSessionService.SessionCredentials changeOwnPassword(BackofficePrincipal principal,
                                                                         String currentPassword,
                                                                         String newPassword,
                                                                         HttpServletResponse response) {
        BackofficeUser user = current(principal);
        if (user.isSystemManaged() || systemAdmin.isSystemUsername(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La contraseña del SUPER_ADMIN de sistema se administra mediante configuración del servidor");
        }
        String safeCurrentPassword = currentPassword == null ? "" : currentPassword;
        if (!fitsBcrypt(safeCurrentPassword) || !passwordEncoder.matches(safeCurrentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta");
        }
        validatePassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nueva contraseña debe ser diferente a la actual");
        }

        Map<String, Object> before = credentialSnapshot(user);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setSessionVersion(user.getSessionVersion() + 1);
        user = users.save(user);
        audit.record(user, AdminAuditAction.ADMIN_CHANGE_OWN_PASSWORD, AdminAuditEntityType.BACKOFFICE_USER,
                user.getId(), before, credentialSnapshot(user), null);

        // create() revoca cualquier sesión anterior del usuario y emite una nueva cookie/CSRF.
        return sessions.create(user, response);
    }

    public void logout(BackofficePrincipal principal, HttpServletResponse response) {
        sessions.revoke(principal, response);
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres");
        }
        if (!fitsBcrypt(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contraseña no puede superar " + MAX_BCRYPT_PASSWORD_BYTES
                            + " bytes UTF-8 por compatibilidad segura con BCrypt");
        }
        boolean upper = password.chars().anyMatch(Character::isUpperCase);
        boolean lower = password.chars().anyMatch(Character::isLowerCase);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!upper || !lower || !digit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contraseña debe incluir mayúsculas, minúsculas y números");
        }
    }

    private Map<String, Object> credentialSnapshot(BackofficeUser user) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", user.getId());
        value.put("username", user.getUsername());
        value.put("mustChangePassword", user.isMustChangePassword());
        value.put("sessionVersion", user.getSessionVersion());
        return value;
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "No se pudo iniciar sesión. Verifica tus credenciales o inténtalo nuevamente más tarde.");
    }

    static boolean fitsBcrypt(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= MAX_BCRYPT_PASSWORD_BYTES;
    }

    private String normalizeUsername(String value) {
        if (value == null || value.isBlank()) throw invalidCredentials();
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 80) throw invalidCredentials();
        return normalized;
    }
}
