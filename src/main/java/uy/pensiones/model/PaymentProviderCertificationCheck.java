package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProviderCertificationCheckCode;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_provider_certification_checks",
        uniqueConstraints = @UniqueConstraint(name = "uq_payment_provider_certification_check", columnNames = {"run_id", "check_code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentProviderCertificationCheck {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private PaymentProviderCertificationRun run;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_code", nullable = false, length = 80)
    private PaymentProviderCertificationCheckCode checkCode;

    @Column(nullable = false)
    private boolean confirmed;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by_backoffice_user_id")
    private BackofficeUser confirmedByBackoffice;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(length = 1500)
    private String reason;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() { if (updatedAt == null) updatedAt = OffsetDateTime.now(); }
    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }
}
