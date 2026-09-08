package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "owner_trial_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OwnerTrialSettings {

    @Id
    private Short id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trial_plan_version_id")
    private PlanVersion trialPlanVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) id = 1;
        if (durationDays <= 0) durationDays = 90;
        if (graceDays < 0) graceDays = 7;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
