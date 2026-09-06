package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PlanVersionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "plan_versions",
        uniqueConstraints = @UniqueConstraint(name = "uq_plan_versions_plan_version", columnNames = {"plan_id", "version"})
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(nullable = false)
    private int version;

    @Column(name = "monthly_price", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal monthlyPrice = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "UYU";

    private Integer maxPensions;
    private Integer maxCollaborators;
    private Integer maxPhotos;
    private Integer maxVideos;

    @Column(nullable = false)
    @Builder.Default
    private int featuredDays = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean advancedAnalytics = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean inquiryHistory = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean consolidatedAnalytics = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean exportEnabled = false;

    private OffsetDateTime effectiveFrom;
    private OffsetDateTime effectiveUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PlanVersionStatus status = PlanVersionStatus.DRAFT;

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
        if (monthlyPrice == null) monthlyPrice = BigDecimal.ZERO;
        if (currency == null) currency = "UYU";
        if (status == null) status = PlanVersionStatus.DRAFT;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
