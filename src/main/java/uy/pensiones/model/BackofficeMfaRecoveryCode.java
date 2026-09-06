package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "backoffice_mfa_recovery_codes", indexes = {
        @Index(name = "idx_backoffice_mfa_recovery_user_unused", columnList = "backoffice_user_id,used_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BackofficeMfaRecoveryCode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "backoffice_user_id", nullable = false)
    private BackofficeUser backofficeUser;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
