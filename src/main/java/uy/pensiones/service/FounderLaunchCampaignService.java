package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.LaunchCampaignEventType;
import uy.pensiones.enums.LaunchCampaignStatus;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.LaunchCampaign;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.LaunchCampaignEvent;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;
import uy.pensiones.repo.LaunchCampaignEventRepository;
import uy.pensiones.repo.LaunchCampaignRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class FounderLaunchCampaignService {

    public static final String FOUNDER_CAMPAIGN_CODE = "PROPIETARIOS_FUNDADORES";
    private static final String AUTO_GRANT_REASON = "Primera pensión válida publicada durante la campaña de lanzamiento";

    private final LaunchCampaignRepository campaigns;
    private final LaunchCampaignBeneficiaryRepository beneficiaries;
    private final LaunchCampaignEventRepository events;
    private final FounderFeaturedBenefitService featuredBenefits;

    public FounderLaunchCampaignService(LaunchCampaignRepository campaigns,
                                        LaunchCampaignBeneficiaryRepository beneficiaries,
                                        LaunchCampaignEventRepository events,
                                        FounderFeaturedBenefitService featuredBenefits) {
        this.campaigns = campaigns;
        this.beneficiaries = beneficiaries;
        this.events = events;
        this.featuredBenefits = featuredBenefits;
    }

    /**
     * Intenta otorgar el beneficio Fundador al responsable de una pensión que acaba de publicarse.
     * La fila de campaña se bloquea con PESSIMISTIC_WRITE para serializar el último cupo entre
     * publicaciones concurrentes y garantizar que granted_count nunca supere max_beneficiaries.
     */
    @Transactional
    public GrantResult onFirstValidPublication(Pension pension) {
        if (pension == null || pension.getId() == null || pension.getStatus() != PensionStatus.PUBLISHED) {
            return GrantResult.skipped(GrantDecision.NOT_PUBLISHED);
        }

        User owner = responsible(pension);
        if (!eligibleOwner(owner)) {
            return GrantResult.skipped(GrantDecision.INELIGIBLE_OWNER);
        }

        LaunchCampaign campaign = campaigns.findByCodeForUpdate(FOUNDER_CAMPAIGN_CODE).orElse(null);
        if (campaign == null) return GrantResult.skipped(GrantDecision.CAMPAIGN_NOT_CONFIGURED);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (campaign.getStatus() != LaunchCampaignStatus.ACTIVE) {
            return GrantResult.skipped(GrantDecision.CAMPAIGN_NOT_ACTIVE);
        }
        if (!insideEnrollmentWindow(campaign, now)) {
            return GrantResult.skipped(GrantDecision.OUTSIDE_ENROLLMENT_WINDOW);
        }
        return grantLocked(campaign, pension, owner, now, null, null, false);
    }

    /**
     * Otorgamiento excepcional desde Backoffice. Consume un cupo real de la campaña y por eso
     * conserva la misma serialización transaccional del grant automático. Puede utilizarse con
     * la campaña PAUSED, pero nunca después de finalizarla.
     */
    @Transactional
    public GrantResult grantManually(Pension pension, BackofficeUser actor, String reason) {
        if (pension == null || pension.getId() == null || pension.getStatus() != PensionStatus.PUBLISHED) {
            return GrantResult.skipped(GrantDecision.NOT_PUBLISHED);
        }
        User owner = responsible(pension);
        if (!eligibleOwner(owner)) return GrantResult.skipped(GrantDecision.INELIGIBLE_OWNER);
        if (actor == null || actor.getId() == null) return GrantResult.skipped(GrantDecision.INVALID_ADMIN_ACTOR);

        LaunchCampaign campaign = campaigns.findByCodeForUpdate(FOUNDER_CAMPAIGN_CODE).orElse(null);
        if (campaign == null) return GrantResult.skipped(GrantDecision.CAMPAIGN_NOT_CONFIGURED);
        if (campaign.getStatus() == LaunchCampaignStatus.ENDED) {
            return GrantResult.skipped(GrantDecision.CAMPAIGN_ENDED);
        }
        return grantLocked(campaign, pension, owner, OffsetDateTime.now(ZoneOffset.UTC), actor, reason, true);
    }

    private GrantResult grantLocked(LaunchCampaign campaign,
                                    Pension pension,
                                    User owner,
                                    OffsetDateTime now,
                                    BackofficeUser actor,
                                    String reason,
                                    boolean manual) {
        if (beneficiaries.existsByCampaignIdAndUserId(campaign.getId(), owner.getId())) {
            return GrantResult.skipped(GrantDecision.ALREADY_BENEFICIARY);
        }
        if (campaign.getGrantedCount() >= campaign.getMaxBeneficiaries()) {
            return GrantResult.skipped(GrantDecision.CAMPAIGN_FULL);
        }

        PlanVersion benefitPlanVersion = campaign.getBenefitPlanVersion();
        if (benefitPlanVersion == null || benefitPlanVersion.getPlan() == null) {
            return GrantResult.skipped(GrantDecision.BENEFIT_PLAN_NOT_CONFIGURED);
        }
        if (!isGrantableBenefitPlan(benefitPlanVersion, now)) {
            return GrantResult.skipped(GrantDecision.BENEFIT_PLAN_NOT_AVAILABLE);
        }

        String grantReason = manual ? reason : AUTO_GRANT_REASON;
        int grantedOrder = campaign.getGrantedCount() + 1;
        OffsetDateTime expiresAt = now.plusDays(campaign.getBenefitDurationDays());
        int featuredSnapshot = Math.max(0, campaign.getMaxFeaturedPensions());
        LaunchCampaignBeneficiary beneficiary = beneficiaries.save(LaunchCampaignBeneficiary.builder()
                .campaign(campaign)
                .user(owner)
                .sourcePension(pension)
                .planVersion(benefitPlanVersion)
                .maxFeaturedPensionsSnapshot(featuredSnapshot)
                .grantedOrder(grantedOrder)
                .grantedAt(now)
                .expiresAt(expiresAt)
                .status(LaunchCampaignBeneficiaryStatus.ACTIVE)
                .grantedByBackofficeUser(actor)
                .grantReason(grantReason)
                .build());

        campaign.setGrantedCount(grantedOrder);
        campaigns.save(campaign);

        PensionPromotion initialFeatured = featuredBenefits.activateInitialIfPossible(beneficiary, pension, now);
        Long initialFeaturedId = initialFeatured == null ? null : initialFeatured.getId();

        events.save(LaunchCampaignEvent.builder()
                .campaign(campaign)
                .beneficiary(beneficiary)
                .user(owner)
                .pension(pension)
                .backofficeUser(actor)
                .eventType(manual ? LaunchCampaignEventType.MANUAL_GRANTED : LaunchCampaignEventType.AUTO_GRANTED)
                .reason(grantReason)
                .afterJson("{\"grantedOrder\":" + grantedOrder
                        + ",\"grantedAt\":\"" + now
                        + "\",\"expiresAt\":\"" + expiresAt
                        + "\",\"planVersionId\":" + benefitPlanVersion.getId()
                        + ",\"maxFeaturedPensions\":" + featuredSnapshot
                        + ",\"initialFeaturedPromotionId\":" + (initialFeaturedId == null ? "null" : initialFeaturedId)
                        + "}")
                .build());

        return new GrantResult(
                GrantDecision.GRANTED,
                beneficiary.getId(),
                grantedOrder,
                now,
                expiresAt,
                benefitPlanVersion.getId(),
                initialFeaturedId
        );
    }

    private User responsible(Pension pension) {
        return pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
    }

    private boolean eligibleOwner(User owner) {
        return owner != null && owner.getId() != null && !owner.isSuspended();
    }

    private boolean insideEnrollmentWindow(LaunchCampaign campaign, OffsetDateTime now) {
        if (campaign.getEnrollmentStartsAt() != null && now.isBefore(campaign.getEnrollmentStartsAt())) return false;
        return campaign.getEnrollmentEndsAt() == null || now.isBefore(campaign.getEnrollmentEndsAt());
    }

    private boolean isGrantableBenefitPlan(PlanVersion version, OffsetDateTime now) {
        Plan plan = version.getPlan();
        if (plan == null || !plan.isActive()) return false;
        if (version.getStatus() != PlanVersionStatus.PUBLISHED) return false;
        if (version.getEffectiveFrom() == null || now.isBefore(version.getEffectiveFrom())) return false;
        return version.getEffectiveUntil() == null || now.isBefore(version.getEffectiveUntil());
    }

    public enum GrantDecision {
        GRANTED,
        NOT_PUBLISHED,
        INELIGIBLE_OWNER,
        INVALID_ADMIN_ACTOR,
        CAMPAIGN_NOT_CONFIGURED,
        CAMPAIGN_NOT_ACTIVE,
        CAMPAIGN_ENDED,
        OUTSIDE_ENROLLMENT_WINDOW,
        ALREADY_BENEFICIARY,
        CAMPAIGN_FULL,
        BENEFIT_PLAN_NOT_CONFIGURED,
        BENEFIT_PLAN_NOT_AVAILABLE
    }

    public record GrantResult(
            GrantDecision decision,
            Long beneficiaryId,
            Integer grantedOrder,
            OffsetDateTime grantedAt,
            OffsetDateTime expiresAt,
            Long planVersionId,
            Long initialFeaturedPromotionId
    ) {
        static GrantResult skipped(GrantDecision decision) {
            return new GrantResult(decision, null, null, null, null, null, null);
        }

        public boolean granted() {
            return decision == GrantDecision.GRANTED;
        }
    }
}
