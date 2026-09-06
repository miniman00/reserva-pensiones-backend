package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PensionPromotionSource;
import uy.pensiones.enums.PensionPromotionStatus;
import uy.pensiones.enums.PensionPromotionType;
import uy.pensiones.enums.PromotionTargetType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_promotions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PensionPromotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pension_id", nullable = false)
    private Pension pension;

    /** Nulo únicamente para compatibilidad con destacados previos al catálogo comercial. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_product_version_id")
    private PromotionProductVersion productVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_type", nullable = false, length = 30)
    @Builder.Default
    private PensionPromotionType type = PensionPromotionType.FEATURED;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private PromotionTargetType targetType;

    /** Target estable para STUDY_CENTER. Otros targets futuros podrán usar targetValue. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "study_center_id")
    private StudyCenterCatalog studyCenter;

    @Column(name = "target_value", length = 160)
    private String targetValue;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    /** Nulo solamente para promociones legadas sin vencimiento conocido. */
    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PensionPromotionStatus status = PensionPromotionStatus.ACTIVE;

    /** Importe realmente cobrado. Un grant administrativo se registra en 0. */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "UYU";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private PaymentRecord payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PensionPromotionSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_backoffice_user_id")
    private BackofficeUser createdByBackoffice;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 1500)
    private String cancellationReason;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (type == null) type = PensionPromotionType.FEATURED;
        if (status == null) status = PensionPromotionStatus.ACTIVE;
        if (price == null) price = BigDecimal.ZERO;
        if (currency == null) currency = "UYU";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
