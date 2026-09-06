package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.BackofficeRole;

import java.time.OffsetDateTime;

@Entity
@Table(name = "backoffice_users", indexes = {
        @Index(name = "idx_backoffice_users_active", columnList = "active"),
        @Index(name = "idx_backoffice_users_role", columnList = "role")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BackofficeUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(length = 190)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private BackofficeRole role = BackofficeRole.ADMIN;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "last_failed_login_at")
    private OffsetDateTime lastFailedLoginAt;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "session_version", nullable = false)
    @Builder.Default
    private int sessionVersion = 1;

    @Column(name = "mfa_secret_ciphertext", columnDefinition = "TEXT")
    private String mfaSecretCiphertext;

    @Column(name = "mfa_pending_secret_ciphertext", columnDefinition = "TEXT")
    private String mfaPendingSecretCiphertext;

    @Column(name = "mfa_pending_expires_at")
    private OffsetDateTime mfaPendingExpiresAt;

    @Column(name = "mfa_enabled_at")
    private OffsetDateTime mfaEnabledAt;

    @Column(name = "mfa_last_verified_at")
    private OffsetDateTime mfaLastVerifiedAt;

    @Column(name = "mfa_last_accepted_counter")
    private Long mfaLastAcceptedCounter;

    @Column(name = "mfa_failed_attempts", nullable = false)
    @Builder.Default
    private int mfaFailedAttempts = 0;

    @Column(name = "mfa_locked_until")
    private OffsetDateTime mfaLockedUntil;

    @Column(name = "mfa_break_glass_failed_attempts", nullable = false)
    @Builder.Default
    private int mfaBreakGlassFailedAttempts = 0;

    @Column(name = "mfa_break_glass_locked_until")
    private OffsetDateTime mfaBreakGlassLockedUntil;

    /** Marca la cuenta de recuperación cuya credencial vive exclusivamente en infraestructura. */
    @Column(name = "system_managed", nullable = false)
    @Builder.Default
    private boolean systemManaged = false;

    /** Huella no reversible del hash configurado; permite invalidar sesiones cuando cambia la credencial externa. */
    @Column(name = "managed_credential_fingerprint", length = 64)
    private String managedCredentialFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (sessionVersion <= 0) sessionVersion = 1;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
