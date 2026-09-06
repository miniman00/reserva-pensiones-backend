package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;

import java.time.OffsetDateTime;

@Entity
@Table(name = "launch_campaign_beneficiaries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LaunchCampaignBeneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private LaunchCampaign campaign;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_pension_id", nullable = false)
    private Pension sourcePension;

    /** Snapshot de la versión de plan prometida al momento del grant. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_version_id")
    private PlanVersion planVersion;

    /** Snapshot del máximo de destacados prometido a este beneficiario. */
    @Column(name = "max_featured_pensions_snapshot")
    private Integer maxFeaturedPensionsSnapshot;

    @Column(name = "granted_order", nullable = false)
    private int grantedOrder;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LaunchCampaignBeneficiaryStatus status = LaunchCampaignBeneficiaryStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by_backoffice_user_id")
    private BackofficeUser grantedByBackofficeUser;

    @Column(name = "grant_reason", length = 1000)
    private String grantReason;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revocation_reason", length = 1000)
    private String revocationReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = LaunchCampaignBeneficiaryStatus.ACTIVE;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
