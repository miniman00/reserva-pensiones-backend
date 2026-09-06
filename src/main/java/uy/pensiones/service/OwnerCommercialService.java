package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.enums.PromotionProductVersionStatus;
import uy.pensiones.enums.SubscriptionStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.PromotionProductVersion;
import uy.pensiones.model.User;
import uy.pensiones.payment.PaymentGatewayRegistry;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PlanVersionRepository;
import uy.pensiones.repo.PromotionProductVersionRepository;
import uy.pensiones.repo.UserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read model used by the marketplace owner area.
 *
 * It deliberately exposes no payment provider identity, mode, credentials or Backoffice-only data.
 * Checkout creation remains a separate operation where ownership, eligibility, price and provider
 * selection are validated again server-side.
 */
@Service
public class OwnerCommercialService {

    private final AppProperties properties;
    private final UserRepository users;
    private final PlanVersionRepository planVersions;
    private final PromotionProductVersionRepository promotionVersions;
    private final PensionPromotionRepository promotions;
    private final OwnerEntitlementService entitlements;
    private final PaymentRuntimeConfigurationService paymentRuntime;
    private final PaymentGatewayRegistry paymentGateways;
    private final SubscriptionFeaturedBenefitService featuredBenefits;
    private final FounderFeaturedBenefitService founderBenefits;

    public OwnerCommercialService(AppProperties properties,
                                  UserRepository users,
                                  PlanVersionRepository planVersions,
                                  PromotionProductVersionRepository promotionVersions,
                                  PensionPromotionRepository promotions,
                                  OwnerEntitlementService entitlements,
                                  PaymentRuntimeConfigurationService paymentRuntime,
                                  PaymentGatewayRegistry paymentGateways,
                                  SubscriptionFeaturedBenefitService featuredBenefits,
                                  FounderFeaturedBenefitService founderBenefits) {
        this.properties = properties;
        this.users = users;
        this.planVersions = planVersions;
        this.promotionVersions = promotionVersions;
        this.promotions = promotions;
        this.entitlements = entitlements;
        this.paymentRuntime = paymentRuntime;
        this.paymentGateways = paymentGateways;
        this.featuredBenefits = featuredBenefits;
        this.founderBenefits = founderBenefits;
    }

    @Transactional(readOnly = true)
    public CommercialOverview overview(Long userId) {
        User user = requireMarketplaceUser(userId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        List<PlanOffer> plans = uniquePlans(planVersions.findEffectivePublishedCatalog(PlanVersionStatus.PUBLISHED, now));
        List<PromotionOffer> promotionProducts = uniquePromotions(
                promotionVersions.findEffectivePublishedCatalog(PromotionProductVersionStatus.PUBLISHED, now));
        OwnerEntitlementService.EntitlementSnapshot entitlementSnapshot = entitlements.resolve(user.getId());
        CurrentSubscription currentSubscription = currentSubscription(entitlementSnapshot);
        SubscriptionFeaturedBenefitService.BenefitUsage featuredBenefit = featuredBenefits.currentUsage(entitlementSnapshot, now);
        FounderFeaturedBenefitService.BenefitUsage founderBenefit = founderBenefits.currentUsage(user.getId(), now);
        List<ActivePromotion> activePromotions = promotions.findEffectiveActiveForOwner(user.getId(), now).stream()
                .map(this::activePromotion)
                .toList();
        List<PromotionPerformance> promotionPerformance = promotionPerformance(user.getId());

        return new CommercialOverview(
                properties.getMonetization().isEnabled(),
                paymentAvailability(),
                plans,
                promotionProducts,
                currentSubscription,
                entitlementSnapshot,
                featuredBenefit,
                founderBenefit,
                activePromotions,
                promotionPerformance
        );
    }

    private User requireMarketplaceUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No se pudo identificar al usuario autenticado");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        if (user.getRole() != UserRole.SEEKER && user.getRole() != UserRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario del marketplace no encontrado");
        }
        return user;
    }

    private List<PlanOffer> uniquePlans(List<PlanVersion> versions) {
        Map<Long, PlanOffer> result = new LinkedHashMap<>();
        for (PlanVersion version : versions) {
            if (version.getPlan() == null) continue;
            result.putIfAbsent(version.getPlan().getId(), planOffer(version));
        }
        return List.copyOf(result.values());
    }

    private List<PromotionOffer> uniquePromotions(List<PromotionProductVersion> versions) {
        Map<Long, PromotionOffer> result = new LinkedHashMap<>();
        for (PromotionProductVersion version : versions) {
            if (version.getProduct() == null) continue;
            result.putIfAbsent(version.getProduct().getId(), promotionOffer(version));
        }
        return List.copyOf(result.values());
    }

    private CurrentSubscription currentSubscription(OwnerEntitlementService.EntitlementSnapshot snapshot) {
        if (snapshot == null || snapshot.source() != uy.pensiones.enums.EntitlementSource.SUBSCRIPTION
                || snapshot.subscription() == null || snapshot.plan() == null) {
            return null;
        }
        var subscription = snapshot.subscription();
        var plan = snapshot.plan();
        return new CurrentSubscription(
                subscription.id(), SubscriptionStatus.ACTIVE,
                plan.planId(), plan.planCode(), plan.planName(),
                plan.planVersionId(), plan.planVersion(), subscription.source(),
                subscription.startedAt(), subscription.expiresAt()
        );
    }

    private PaymentAvailability paymentAvailability() {
        boolean enabled = paymentRuntime.paymentsEnabled();
        boolean subscriptionsAvailable = enabled && hasAvailableGateway(PaymentPurpose.SUBSCRIPTION);
        boolean promotionsAvailable = enabled && hasAvailableGateway(PaymentPurpose.PROMOTION);
        String message = null;
        if (!paymentRuntime.infrastructureAllowed()) {
            message = "Los pagos no están disponibles temporalmente.";
        } else if (!paymentRuntime.databasePaymentsEnabled()) {
            message = "Las compras están temporalmente deshabilitadas.";
        } else if (!subscriptionsAvailable && !promotionsAvailable) {
            message = "No hay una forma de pago disponible en este momento.";
        }
        return new PaymentAvailability(enabled, subscriptionsAvailable, promotionsAvailable, message);
    }

    private boolean hasAvailableGateway(PaymentPurpose purpose) {
        for (PaymentProvider provider : PaymentProvider.values()) {
            // MOCK es una herramienta de Backoffice/desarrollo y no produce un checkout navegable para el portal.
            if (provider == PaymentProvider.MOCK) continue;
            if (paymentGateways.isAvailableForNewPayments(provider, purpose)) return true;
        }
        return false;
    }

    private PlanOffer planOffer(PlanVersion version) {
        var plan = version.getPlan();
        return new PlanOffer(
                plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(),
                version.getId(), version.getVersion(), money(version.getMonthlyPrice()), version.getCurrency(),
                version.getEffectiveFrom(), version.getEffectiveUntil(),
                version.getMaxPensions(), version.getMaxCollaborators(), version.getMaxPhotos(), version.getMaxVideos(),
                version.getFeaturedDays(), version.isAdvancedAnalytics(), version.isInquiryHistory(),
                version.isConsolidatedAnalytics(), version.isExportEnabled()
        );
    }

    private PromotionOffer promotionOffer(PromotionProductVersion version) {
        var product = version.getProduct();
        return new PromotionOffer(
                product.getId(), product.getCode(), product.getName(), product.getDescription(),
                product.getTargetType(), product.getDurationDays(),
                version.getId(), version.getVersion(), money(version.getPrice()), version.getCurrency(),
                version.getEffectiveFrom(), version.getEffectiveUntil()
        );
    }

    private ActivePromotion activePromotion(PensionPromotion promotion) {
        PromotionProductVersion version = promotion.getProductVersion();
        boolean subscriptionBenefit = promotion.getSource() == uy.pensiones.enums.PensionPromotionSource.SUBSCRIPTION_BENEFIT;
        boolean founderBenefit = promotion.getSource() == uy.pensiones.enums.PensionPromotionSource.LAUNCH_CAMPAIGN;
        String productCode = subscriptionBenefit ? "PLAN_FEATURED"
                : founderBenefit ? "FOUNDER_FEATURED"
                : (version == null || version.getProduct() == null ? null : version.getProduct().getCode());
        String productName = subscriptionBenefit ? "Destacado incluido en el plan"
                : founderBenefit ? "Destacado Propietario Fundador"
                : (version == null || version.getProduct() == null ? "Destacado" : version.getProduct().getName());
        return new ActivePromotion(
                promotion.getId(), promotion.getPension().getId(), promotion.getPension().getName(),
                productCode, productName,
                version == null ? null : version.getId(),
                promotion.getTargetType(),
                promotion.getStudyCenter() == null ? null : promotion.getStudyCenter().getId(),
                promotion.getStudyCenter() == null ? null : promotion.getStudyCenter().getName(),
                promotion.getStartsAt(), promotion.getEndsAt(), promotion.getSource()
        );
    }

    private List<PromotionPerformance> promotionPerformance(Long userId) {
        List<PensionPromotionRepository.OwnerPromotionPerformanceRow> rows = promotions.ownerPerformance(userId);
        if (rows == null || rows.isEmpty()) return List.of();
        return rows.stream().map(row -> {
            long impressions = safeCount(row.getImpressions());
            long clicks = safeCount(row.getClicks());
            long inquiries = safeCount(row.getInquiries());
            long conversions = safeCount(row.getConversions());
            BigDecimal clickThroughRate = percentage(clicks, impressions);
            BigDecimal inquiryRate = percentage(inquiries, clicks);
            BigDecimal conversionRate = percentage(conversions, inquiries);
            return new PromotionPerformance(
                    row.getPromotionId(), row.getPensionId(), row.getPensionName(),
                    row.getProductCode(), row.getProductName(), row.getTargetType(),
                    row.getStudyCenterName(), toOffset(row.getStartsAt()), toOffset(row.getEndsAt()),
                    row.getEffectiveStatus(), impressions, clicks, inquiries, conversions,
                    clickThroughRate, inquiryRate, conversionRate
            );
        }).toList();
    }

    private long safeCount(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator <= 0) return null;
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    private OffsetDateTime toOffset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    public record CommercialOverview(
            boolean monetizationEnabled,
            PaymentAvailability payments,
            List<PlanOffer> plans,
            List<PromotionOffer> promotionProducts,
            CurrentSubscription currentSubscription,
            OwnerEntitlementService.EntitlementSnapshot entitlements,
            SubscriptionFeaturedBenefitService.BenefitUsage featuredBenefit,
            FounderFeaturedBenefitService.BenefitUsage founderBenefit,
            List<ActivePromotion> activePromotions,
            List<PromotionPerformance> promotionPerformance
    ) {}

    public record PaymentAvailability(
            boolean enabled,
            boolean subscriptionsAvailable,
            boolean promotionsAvailable,
            String message
    ) {}

    public record PlanOffer(
            Long planId, String code, String name, String description,
            Long versionId, int version, BigDecimal monthlyPrice, String currency,
            OffsetDateTime effectiveFrom, OffsetDateTime effectiveUntil,
            Integer maxPensions, Integer maxCollaboratorsPerPension,
            Integer maxPhotosPerPension, Integer maxVideosPerPension,
            int featuredDays, boolean advancedAnalytics, boolean inquiryHistory,
            boolean consolidatedAnalytics, boolean exportEnabled
    ) {}

    public record PromotionOffer(
            Long productId, String code, String name, String description,
            uy.pensiones.enums.PromotionTargetType targetType, int durationDays,
            Long versionId, int version, BigDecimal price, String currency,
            OffsetDateTime effectiveFrom, OffsetDateTime effectiveUntil
    ) {}

    public record CurrentSubscription(
            Long id, SubscriptionStatus status,
            Long planId, String planCode, String planName,
            Long planVersionId, int planVersion,
            uy.pensiones.enums.SubscriptionSource source,
            OffsetDateTime startedAt, OffsetDateTime expiresAt
    ) {}

    public record ActivePromotion(
            Long id, Long pensionId, String pensionName,
            String productCode, String productName, Long productVersionId,
            uy.pensiones.enums.PromotionTargetType targetType,
            Long studyCenterId, String studyCenterName,
            OffsetDateTime startsAt, OffsetDateTime endsAt,
            uy.pensiones.enums.PensionPromotionSource source
    ) {}

    public record PromotionPerformance(
            Long promotionId, Long pensionId, String pensionName,
            String productCode, String productName, String targetType, String studyCenterName,
            OffsetDateTime startsAt, OffsetDateTime endsAt, String effectiveStatus,
            long impressions, long clicks, long inquiries, long conversions,
            BigDecimal clickThroughRate, BigDecimal inquiryRate, BigDecimal conversionRate
    ) {}
}
