package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentStatus;

import java.time.OffsetDateTime;
import java.math.BigDecimal;

@Entity
@Table(name = "mock_payment_states")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MockPaymentState {
    @Id
    @Column(name = "provider_payment_id", length = 190)
    private String providerPaymentId;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "provider_subscription_id", length = 190)
    private String providerSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "provider_status", nullable = false, length = 80)
    private String providerStatus;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (refundedAmount == null) refundedAmount = BigDecimal.ZERO;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
