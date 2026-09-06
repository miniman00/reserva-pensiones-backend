package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Sesión opaca exclusiva del Backoffice; no reutiliza la sesión OAuth del marketplace. */
@Entity
@Table(name = "backoffice_sessions", indexes = {
        @Index(name = "idx_backoffice_sessions_user", columnList = "backoffice_user_id"),
        @Index(name = "idx_backoffice_sessions_expires", columnList = "expires_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BackofficeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "backoffice_user_id", nullable = false)
    private BackofficeUser backofficeUser;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "csrf_token", nullable = false, length = 100)
    private String csrfToken;

    @Column(name = "session_version", nullable = false)
    private int sessionVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "last_reauthenticated_at", nullable = false)
    private OffsetDateTime lastReauthenticatedAt;

    @Column(name = "mfa_verified_at")
    private OffsetDateTime mfaVerifiedAt;

    @Column(name = "reauth_failed_attempts", nullable = false)
    private int reauthFailedAttempts;

    @Column(name = "reauth_locked_until")
    private OffsetDateTime reauthLockedUntil;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (lastSeenAt == null) lastSeenAt = now;
        if (lastReauthenticatedAt == null) lastReauthenticatedAt = createdAt;
    }
}
