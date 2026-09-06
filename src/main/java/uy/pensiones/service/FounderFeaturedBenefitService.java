package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.PensionPromotionSource;
import uy.pensiones.enums.PensionPromotionStatus;
import uy.pensiones.enums.PensionPromotionType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Administra los slots destacados continuos incluidos en la Campaña Fundadores.
 * Es independiente de featured_days del plan: un Fundador puede mantener hasta N pensiones
 * destacadas simultáneamente durante la vigencia del beneficio, sin consumir días mensuales.
 */
@Service
public class FounderFeaturedBenefitService {

    private static final String OWNER_DEACTIVATION_REASON = "Destacado Fundador desactivado por el propietario";

    private final LaunchCampaignBeneficiaryRepository beneficiaries;
    private final PensionRepository pensions;
    private final PensionPromotionRepository promotions;

    public FounderFeaturedBenefitService(LaunchCampaignBeneficiaryRepository beneficiaries,
                                         PensionRepository pensions,
                                         PensionPromotionRepository promotions) {
        this.beneficiaries = beneficiaries;
        this.pensions = pensions;
        this.promotions = promotions;
    }

    @Transactional(readOnly = true)
    public BenefitUsage currentUsage(Long userId, OffsetDateTime now) {
        if (userId == null || userId <= 0) return BenefitUsage.unavailable();
        OffsetDateTime instant = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        LaunchCampaignBeneficiary beneficiary = beneficiaries.findEffectiveDetailed(
                userId, FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE,
                LaunchCampaignBeneficiaryStatus.ACTIVE, instant).orElse(null);
        if (beneficiary == null) return BenefitUsage.unavailable();
        return usage(beneficiary, userId, instant);
    }

    @Transactional
    public ActivationResult activate(Long userId, Long pensionId) {
        Long cleanUserId = requireId(userId, "No se pudo identificar al usuario autenticado", HttpStatus.UNAUTHORIZED);
        Long cleanPensionId = requireId(pensionId, "La pensión no es válida", HttpStatus.BAD_REQUEST);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        LaunchCampaignBeneficiary beneficiary = requireActiveForUpdate(cleanUserId, now);
        int max = maxFeaturedPensions(beneficiary);
        if (max == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tu beneficio Fundador no incluye pensiones destacadas");
        }

        long active = countActive(cleanUserId, now);
        if (active >= max) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya estás utilizando los " + max + " destacados simultáneos de tu beneficio Fundador");
        }

        Pension pension = pensions.findByIdForEntitlementUpdate(cleanPensionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        validateOwnedPublishedPension(pension, cleanUserId);

        PensionPromotion promotion = createPromotionIfAvailable(beneficiary, pension, now, false);
        BenefitUsage updated = usageWithActive(beneficiary, active + 1);
        return new ActivationResult(
                promotion.getId(), pension.getId(), pension.getName(),
                promotion.getStartsAt(), promotion.getEndsAt(), updated
        );
    }

    @Transactional
    public DeactivationResult deactivate(Long userId, Long promotionId) {
        Long cleanUserId = requireId(userId, "No se pudo identificar al usuario autenticado", HttpStatus.UNAUTHORIZED);
        Long cleanPromotionId = requireId(promotionId, "El destacado no es válido", HttpStatus.BAD_REQUEST);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LaunchCampaignBeneficiary beneficiary = requireActiveForUpdate(cleanUserId, now);

        PensionPromotion promotion = promotions.findFounderPromotionForOwnerForUpdate(
                        cleanPromotionId, cleanUserId, PensionPromotionSource.LAUNCH_CAMPAIGN, now)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Destacado Fundador activo no encontrado"));
        promotion.setStatus(PensionPromotionStatus.CANCELLED);
        promotion.setCancelledAt(now);
        promotion.setCancellationReason(OWNER_DEACTIVATION_REASON);
        promotions.saveAndFlush(promotion);

        long active = countActive(cleanUserId, now);
        return new DeactivationResult(promotion.getId(), promotion.getPension().getId(), usageWithActive(beneficiary, active));
    }

    /**
     * Mantiene alineados los destacados ya elegidos cuando Backoffice extiende el beneficio.
     * También reactiva naturalmente promociones almacenadas como ACTIVE cuyo endsAt era el vencimiento anterior.
     */
    int extendForBeneficiary(LaunchCampaignBeneficiary beneficiary,
                             OffsetDateTime previousExpiresAt,
                             OffsetDateTime newExpiresAt,
                             OffsetDateTime now) {
        if (beneficiary == null || beneficiary.getUser() == null || beneficiary.getUser().getId() == null
                || previousExpiresAt == null || newExpiresAt == null || !newExpiresAt.isAfter(previousExpiresAt)) {
            return 0;
        }
        return promotions.extendFounderPromotionsForOwner(
                beneficiary.getUser().getId(),
                PensionPromotionSource.LAUNCH_CAMPAIGN,
                previousExpiresAt,
                newExpiresAt,
                now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now);
    }

    /** Un destacado de campaña pertenece al beneficiario, no se transfiere con la pensión. */
    void cancelForOwnershipTransfer(Pension pension, OffsetDateTime now) {
        if (pension == null || pension.getId() == null) return;
        OffsetDateTime instant = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        promotions.cancelEffectiveForPensionBySource(
                pension.getId(), PensionPromotionSource.LAUNCH_CAMPAIGN, instant,
                "Destacado Fundador cancelado por transferencia de responsable");
    }

    /** Se invoca en la misma transacción del grant para destacar de inmediato la primera pensión. */
    PensionPromotion activateInitialIfPossible(LaunchCampaignBeneficiary beneficiary,
                                               Pension pension,
                                               OffsetDateTime now) {
        if (beneficiary == null || pension == null || beneficiary.getCampaign() == null) return null;
        if (maxFeaturedPensions(beneficiary) <= 0) return null;
        if (beneficiary.getUser() == null || beneficiary.getUser().getId() == null) return null;
        try {
            validateOwnedPublishedPension(pension, beneficiary.getUser().getId());
            return createPromotionIfAvailable(beneficiary, pension, now, true);
        } catch (ResponseStatusException ignored) {
            // El beneficio principal no debe perderse porque la pensión ya tenga un destacado incompatible.
            return null;
        }
    }

    private PensionPromotion createPromotionIfAvailable(LaunchCampaignBeneficiary beneficiary,
                                                         Pension pension,
                                                         OffsetDateTime now,
                                                         boolean skipOnOverlap) {
        OffsetDateTime endsAt = beneficiary.getExpiresAt();
        if (endsAt == null || !endsAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El beneficio Fundador ya no está vigente");
        }
        long overlapping = promotions.countOverlapping(
                pension.getId(), PromotionTargetType.GLOBAL.name(), null, now, endsAt);
        if (overlapping > 0) {
            if (skipOnOverlap) return null;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La pensión ya tiene un destacado general activo o programado durante esa vigencia");
        }

        String currency = beneficiary.getPlanVersion() == null || beneficiary.getPlanVersion().getCurrency() == null
                ? "UYU" : beneficiary.getPlanVersion().getCurrency();
        return promotions.save(PensionPromotion.builder()
                .pension(pension)
                .productVersion(null)
                .type(PensionPromotionType.FEATURED)
                .targetType(PromotionTargetType.GLOBAL)
                .studyCenter(null)
                .targetValue(null)
                .startsAt(now)
                .endsAt(endsAt)
                .status(PensionPromotionStatus.ACTIVE)
                .price(BigDecimal.ZERO)
                .currency(currency)
                .payment(null)
                .source(PensionPromotionSource.LAUNCH_CAMPAIGN)
                .createdByBackoffice(null)
                .build());
    }

    private LaunchCampaignBeneficiary requireActiveForUpdate(Long userId, OffsetDateTime now) {
        return beneficiaries.findEffectiveForUpdate(
                        userId, FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE,
                        LaunchCampaignBeneficiaryStatus.ACTIVE, now)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No tienes un beneficio de Propietario Fundador vigente"));
    }

    private void validateOwnedPublishedPension(Pension pension, Long userId) {
        User owner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        if (owner == null || owner.getId() == null || !owner.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada");
        }
        if (pension.getStatus() != PensionStatus.PUBLISHED || Boolean.TRUE.equals(pension.getModerationBlocked())
                || owner.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La pensión debe estar publicada y operativa para utilizar el beneficio Fundador");
        }
    }

    private BenefitUsage usage(LaunchCampaignBeneficiary beneficiary, Long userId, OffsetDateTime now) {
        return usageWithActive(beneficiary, countActive(userId, now));
    }

    private BenefitUsage usageWithActive(LaunchCampaignBeneficiary beneficiary, long active) {
        int max = maxFeaturedPensions(beneficiary);
        int safeActive = active > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, active);
        int remaining = Math.max(0, max - safeActive);
        return new BenefitUsage(
                true,
                beneficiary.getId(),
                beneficiary.getCampaign().getCode(),
                beneficiary.getCampaign().getName(),
                beneficiary.getGrantedOrder(),
                beneficiary.getGrantedAt(),
                beneficiary.getExpiresAt(),
                planSnapshot(beneficiary),
                max,
                safeActive,
                remaining,
                remaining > 0
        );
    }

    private FounderPlanSnapshot planSnapshot(LaunchCampaignBeneficiary beneficiary) {
        if (beneficiary == null || beneficiary.getPlanVersion() == null) return null;
        PlanVersion version = beneficiary.getPlanVersion();
        var plan = version.getPlan();
        if (plan == null) return null;
        return new FounderPlanSnapshot(
                plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(),
                version.getId(), version.getVersion(),
                version.getMaxPensions(), version.getMaxCollaborators(),
                version.getMaxPhotos(), version.getMaxVideos(), version.getFeaturedDays(),
                version.isAdvancedAnalytics(), version.isInquiryHistory(),
                version.isConsolidatedAnalytics(), version.isExportEnabled()
        );
    }


    /** Cancela inmediatamente los destacados vigentes al revocar el beneficio desde Backoffice. */
    int cancelActiveForBeneficiary(LaunchCampaignBeneficiary beneficiary, OffsetDateTime now, String reason) {
        if (beneficiary == null || beneficiary.getUser() == null || beneficiary.getUser().getId() == null) return 0;
        OffsetDateTime instant = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        return promotions.cancelEffectiveForOwnerBySource(
                beneficiary.getUser().getId(), PensionPromotionSource.LAUNCH_CAMPAIGN, instant, reason);
    }

    private int maxFeaturedPensions(LaunchCampaignBeneficiary beneficiary) {
        if (beneficiary == null) return 0;
        Integer snapshot = beneficiary.getMaxFeaturedPensionsSnapshot();
        if (snapshot != null) return Math.max(0, snapshot);
        return beneficiary.getCampaign() == null ? 0 : Math.max(0, beneficiary.getCampaign().getMaxFeaturedPensions());
    }

    private long countActive(Long userId, OffsetDateTime now) {
        return Math.max(0L, promotions.countEffectiveActiveForOwnerBySource(
                userId, PensionPromotionSource.LAUNCH_CAMPAIGN.name(), now));
    }

    private Long requireId(Long id, String message, HttpStatus status) {
        if (id == null || id <= 0) throw new ResponseStatusException(status, message);
        return id;
    }

    public record BenefitUsage(
            boolean available,
            Long beneficiaryId,
            String campaignCode,
            String campaignName,
            Integer grantedOrder,
            OffsetDateTime grantedAt,
            OffsetDateTime expiresAt,
            FounderPlanSnapshot plan,
            int maxFeaturedPensions,
            int activeFeaturedPensions,
            int remainingFeaturedSlots,
            boolean canActivate
    ) {
        static BenefitUsage unavailable() {
            return new BenefitUsage(false, null, null, null, null, null, null, null, 0, 0, 0, false);
        }
    }

    public record FounderPlanSnapshot(
            Long planId, String planCode, String planName, String planDescription,
            Long planVersionId, int planVersion,
            Integer maxPensions, Integer maxCollaboratorsPerPension,
            Integer maxPhotosPerPension, Integer maxVideosPerPension, int featuredDays,
            boolean advancedAnalytics, boolean inquiryHistory,
            boolean consolidatedAnalytics, boolean exportEnabled
    ) {}

    public record ActivationResult(
            Long promotionId,
            Long pensionId,
            String pensionName,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BenefitUsage usage
    ) {}

    public record DeactivationResult(
            Long promotionId,
            Long pensionId,
            BenefitUsage usage
    ) {}
}
