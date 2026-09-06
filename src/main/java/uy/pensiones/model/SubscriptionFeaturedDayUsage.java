package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "subscription_featured_day_usage")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubscriptionFeaturedDayUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private OwnerSubscription subscription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pension_id", nullable = false)
    private Pension pension;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", nullable = false, unique = true)
    private PensionPromotion promotion;

    @Column(name = "cycle_start", nullable = false)
    private OffsetDateTime cycleStart;

    @Column(name = "cycle_end", nullable = false)
    private OffsetDateTime cycleEnd;

    @Column(nullable = false)
    private int days;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
