package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PromotionProductVersionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "promotion_product_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_promotion_product_versions_product_version",
                columnNames = {"promotion_product_id", "version"}
        )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PromotionProductVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_product_id", nullable = false)
    private PromotionProduct product;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "UYU";

    private OffsetDateTime effectiveFrom;
    private OffsetDateTime effectiveUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PromotionProductVersionStatus status = PromotionProductVersionStatus.DRAFT;

    private OffsetDateTime publishedAt;
    private OffsetDateTime retiredAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (price == null) price = BigDecimal.ZERO;
        if (currency == null) currency = "UYU";
        if (status == null) status = PromotionProductVersionStatus.DRAFT;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
