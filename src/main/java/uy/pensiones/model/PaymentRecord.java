package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.enums.RefundBenefitDecision;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_mode", length = 20)
    private PaymentProviderMode providerMode;

    @Column(name = "provider_payment_id", length = 190)
    private String providerPaymentId;

    @Column(name = "provider_subscription_id", length = 190)
    private String providerSubscriptionId;

    @Column(name = "provider_checkout_id", length = 190)
    private String providerCheckoutId;

    @Column(name = "checkout_url", columnDefinition = "text")
    private String checkoutUrl;

    @Column(name = "merchant_reference", nullable = false, length = 80)
    private String merchantReference;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_version_id")
    private PlanVersion planVersion;

    @Column(name = "subscription_period_months")
    private Integer subscriptionPeriodMonths;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pension_id")
    private Pension pension;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_product_version_id")
    private PromotionProductVersion promotionProductVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "study_center_id")
    private StudyCenterCatalog studyCenter;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "provider_status", length = 80)
    private String providerStatus;

    @Column(name = "provider_refunded_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal providerRefundedAmount = BigDecimal.ZERO;

    @Column(name = "fulfilled_at")
    private OffsetDateTime fulfilledAt;

    @Column(name = "fulfillment_error_code", length = 80)
    private String fulfillmentErrorCode;

    @Column(name = "fulfillment_error_message", length = 500)
    private String fulfillmentErrorMessage;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_subscription_id")
    private OwnerSubscription fulfilledSubscription;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pension_promotion_id")
    private PensionPromotion fulfilledPromotion;

    private OffsetDateTime approvedAt;
    private OffsetDateTime rejectedAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime refundedAt;
    private OffsetDateTime expiredAt;

    @Column(name = "last_provider_sync_at")
    private OffsetDateTime lastProviderSyncAt;

    @Column(name = "last_reconciliation_attempt_at")
    private OffsetDateTime lastReconciliationAttemptAt;

    @Column(name = "last_reconciliation_error", length = 600)
    private String lastReconciliationError;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_benefit_decision", length = 30)
    private RefundBenefitDecision refundBenefitDecision;

    @Column(name = "refund_benefit_decided_at")
    private OffsetDateTime refundBenefitDecidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_benefit_decided_by_backoffice_user_id")
    private BackofficeUser refundBenefitDecidedByBackoffice;

    @Column(name = "refund_benefit_reason", length = 1500)
    private String refundBenefitReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_backoffice_user_id")
    private BackofficeUser createdByBackoffice;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = PaymentStatus.CREATED;
        if (providerRefundedAmount == null) providerRefundedAmount = BigDecimal.ZERO;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
