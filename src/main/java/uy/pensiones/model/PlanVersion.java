package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PlanVersionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @OneToMany(mappedBy = "planVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("periodMonths ASC")
    @Builder.Default
    private List<PlanVersionPeriodPrice> periodPrices = new ArrayList<>();

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

    public void replacePeriodPrices(List<PlanVersionPeriodPrice> prices) {
        if (periodPrices == null) periodPrices = new ArrayList<>();
        List<PlanVersionPeriodPrice> incoming = prices == null ? List.of() : prices.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Integer, PlanVersionPeriodPrice> existingByPeriod = new HashMap<>();
        for (PlanVersionPeriodPrice current : periodPrices) {
            existingByPeriod.put(current.getPeriodMonths(), current);
        }
        Set<Integer> incomingPeriods = new HashSet<>();
        for (PlanVersionPeriodPrice price : incoming) incomingPeriods.add(price.getPeriodMonths());
        periodPrices.removeIf(current -> !incomingPeriods.contains(current.getPeriodMonths()));

        for (PlanVersionPeriodPrice price : incoming) {
            PlanVersionPeriodPrice current = existingByPeriod.get(price.getPeriodMonths());
            if (current != null) {
                current.setTotalPrice(price.getTotalPrice());
                current.setEnabled(price.isEnabled());
                continue;
            }
            price.setPlanVersion(this);
            periodPrices.add(price);
        }
    }
}
