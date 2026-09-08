package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.OwnerTrialConsumptionReason;

import java.time.OffsetDateTime;

@Entity
@Table(name = "owner_trial_lifecycles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OwnerTrialLifecycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumption_reason", nullable = false, length = 30)
    private OwnerTrialConsumptionReason consumptionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_pension_id")
    private Pension sourcePension;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trial_plan_version_id")
    private PlanVersion trialPlanVersion;

    @Column(name = "consumed_at", nullable = false)
    private OffsetDateTime consumedAt;

    @Column(name = "trial_started_at")
    private OffsetDateTime trialStartedAt;

    @Column(name = "trial_expires_at")
    private OffsetDateTime trialExpiresAt;

    @Column(name = "grace_expires_at")
    private OffsetDateTime graceExpiresAt;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    @Column(name = "duration_days_snapshot")
    private Integer durationDaysSnapshot;

    @Column(name = "grace_days_snapshot", nullable = false)
    private int graceDaysSnapshot;

    @Column(name = "access_suspended_at")
    private OffsetDateTime accessSuspendedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (consumedAt == null) consumedAt = now;
        if (graceDaysSnapshot < 0) graceDaysSnapshot = 0;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
