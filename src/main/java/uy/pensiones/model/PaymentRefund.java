package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentRefundStatus;
import uy.pensiones.enums.PaymentRefundType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_refunds")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentRefund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private PaymentRecord payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_type", nullable = false, length = 20)
    private PaymentRefundType refundType;

    @Column(name = "requested_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal requestedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentRefundStatus status = PaymentRefundStatus.REQUESTED;

    @Column(name = "provider_status", length = 120)
    private String providerStatus;

    @Column(nullable = false, length = 1500)
    private String reason;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "last_error", length = 600)
    private String lastError;

    @Column(name = "last_reconciliation_attempt_at")
    private OffsetDateTime lastReconciliationAttemptAt;

    @Column(name = "last_reconciliation_error", length = 600)
    private String lastReconciliationError;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_backoffice_user_id", nullable = false)
    private BackofficeUser requestedByBackoffice;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = PaymentRefundStatus.REQUESTED;
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }
}
