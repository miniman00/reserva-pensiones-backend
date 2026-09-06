package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeUserRepository;

import java.util.Objects;

/**
 * Mantiene la cuenta SUPER_ADMIN de recuperación sincronizada con infraestructura.
 * La BD conserva sólo un placeholder no utilizable y una huella de la credencial externa.
 */
@Component
public class BackofficeBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackofficeBootstrapService.class);
    private static final String MANAGED_PASSWORD_PLACEHOLDER = "!EXTERNAL_BACKOFFICE_CREDENTIAL!";

    private final BackofficeUserRepository users;
    private final BackofficeSystemSuperAdminService systemAdmin;

    public BackofficeBootstrapService(BackofficeUserRepository users,
                                      BackofficeSystemSuperAdminService systemAdmin) {
        this.users = users;
        this.systemAdmin = systemAdmin;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!systemAdmin.isConfigured()) {
            // Nunca permitimos que un registro técnico legado caiga al flujo normal de password_hash de BD.
            for (BackofficeUser managed : users.findAllBySystemManagedTrue()) {
                retireManagedAccount(managed);
            }
            users.findByUsernameIgnoreCase(systemAdmin.username())
                    .filter(user -> user.isSystemManaged() || user.getRole() == BackofficeRole.SUPER_ADMIN)
                    .ifPresent(this::retireManagedAccount);
            log.warn("Cuenta SUPER_ADMIN de sistema deshabilitada. Configura APP_BACKOFFICE_SUPER_ADMIN_PASSWORD_HASH para habilitar '{}'.",
                    systemAdmin.username());
            return;
        }

        // Si infraestructura cambia el username, retiramos cualquier cuenta systemManaged anterior.
        for (BackofficeUser managed : users.findAllBySystemManagedTrue()) {
            if (!managed.getUsername().equalsIgnoreCase(systemAdmin.username())) {
                retireManagedAccount(managed);
                log.warn("Se retiró la antigua cuenta SUPER_ADMIN de sistema '{}'.", managed.getUsername());
            }
        }

        BackofficeUser user = users.findByUsernameIgnoreCase(systemAdmin.username()).orElse(null);
        if (user == null) {
            user = BackofficeUser.builder()
                    .username(systemAdmin.username())
                    .passwordHash(MANAGED_PASSWORD_PLACEHOLDER)
                    .displayName(systemAdmin.displayName())
                    .email(systemAdmin.email())
                    .role(BackofficeRole.SUPER_ADMIN)
                    .active(true)
                    .mustChangePassword(false)
                    .systemManaged(true)
                    .managedCredentialFingerprint(systemAdmin.credentialFingerprint())
                    .build();
            users.save(user);
            log.info("Se creó la cuenta SUPER_ADMIN de sistema '{}'.", systemAdmin.username());
            return;
        }

        // Compatibilidad con el registro técnico creado por versiones previas.
        if (!user.isSystemManaged() && user.getRole() != BackofficeRole.SUPER_ADMIN) {
            throw new IllegalStateException("El username reservado para el SUPER_ADMIN de sistema ya pertenece a otra cuenta interna");
        }

        boolean invalidateSessions = false;
        String fingerprint = systemAdmin.credentialFingerprint();
        if (!Objects.equals(user.getManagedCredentialFingerprint(), fingerprint)) {
            user.setManagedCredentialFingerprint(fingerprint);
            invalidateSessions = true;
        }
        if (!user.isSystemManaged()) {
            user.setSystemManaged(true);
            invalidateSessions = true;
        }
        // Forzamos un placeholder no verificable: la credencial externa nunca queda verificable desde la BD.
        user.setPasswordHash(MANAGED_PASSWORD_PLACEHOLDER);
        if (user.getRole() != BackofficeRole.SUPER_ADMIN) {
            user.setRole(BackofficeRole.SUPER_ADMIN);
            invalidateSessions = true;
        }
        if (!user.isActive()) {
            user.setActive(true);
            invalidateSessions = true;
        }
        if (user.isMustChangePassword()) {
            user.setMustChangePassword(false);
            invalidateSessions = true;
        }
        if (!Objects.equals(user.getDisplayName(), systemAdmin.displayName())) {
            user.setDisplayName(systemAdmin.displayName());
        }
        if (!Objects.equals(user.getEmail(), systemAdmin.email())) {
            user.setEmail(systemAdmin.email());
        }
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockedUntil(null);
        if (invalidateSessions) {
            user.setSessionVersion(user.getSessionVersion() + 1);
        }
        users.save(user);
    }
    private void retireManagedAccount(BackofficeUser user) {
        boolean changed = user.isActive()
                || user.getRole() == BackofficeRole.SUPER_ADMIN
                || !user.isSystemManaged()
                || !Objects.equals(user.getPasswordHash(), MANAGED_PASSWORD_PLACEHOLDER)
                || user.getManagedCredentialFingerprint() != null
                || user.isMustChangePassword()
                || user.getFailedLoginAttempts() != 0
                || user.getLastFailedLoginAt() != null
                || user.getLockedUntil() != null;
        user.setActive(false);
        if (user.getRole() == BackofficeRole.SUPER_ADMIN) user.setRole(BackofficeRole.ADMIN);
        user.setSystemManaged(true);
        user.setPasswordHash(MANAGED_PASSWORD_PLACEHOLDER);
        user.setManagedCredentialFingerprint(null);
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockedUntil(null);
        if (changed) user.setSessionVersion(user.getSessionVersion() + 1);
        users.save(user);
    }

}
