package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.LaunchCampaignStatus;

import java.time.OffsetDateTime;

@Entity
@Table(name = "launch_campaigns")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LaunchCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(nullable = false, length = 140)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LaunchCampaignStatus status = LaunchCampaignStatus.PAUSED;

    @Column(name = "max_beneficiaries", nullable = false)
    private int maxBeneficiaries;

    @Column(name = "granted_count", nullable = false)
    private int grantedCount;

    @Column(name = "benefit_duration_days", nullable = false)
    private int benefitDurationDays;

    @Column(name = "max_featured_pensions", nullable = false)
    private int maxFeaturedPensions;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefit_plan_version_id")
    private PlanVersion benefitPlanVersion;

    @Column(name = "enrollment_starts_at", nullable = false)
    private OffsetDateTime enrollmentStartsAt;

    @Column(name = "enrollment_ends_at")
    private OffsetDateTime enrollmentEndsAt;

    @Column(name = "show_remaining_slots", nullable = false)
    private boolean showRemainingSlots;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (enrollmentStartsAt == null) enrollmentStartsAt = now;
        if (status == null) status = LaunchCampaignStatus.PAUSED;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
