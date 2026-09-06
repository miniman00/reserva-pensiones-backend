package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.SubscriptionSource;
import uy.pensiones.enums.SubscriptionStatus;

import java.time.OffsetDateTime;

@Entity
@Table(name = "owner_subscriptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OwnerSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_version_id", nullable = false)
    private PlanVersion planVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Column(length = 40)
    private String provider;

    @Column(name = "provider_subscription_id", length = 190)
    private String providerSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SubscriptionSource source;

    private OffsetDateTime cancelledAt;

    @Column(length = 500)
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
        if (status == null) status = SubscriptionStatus.ACTIVE;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
