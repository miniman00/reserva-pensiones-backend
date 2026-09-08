package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.EntitlementSource;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PensionPromotionSource;
import uy.pensiones.enums.PensionPromotionStatus;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.enums.PromotionProductVersionStatus;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.enums.SubscriptionSource;
import uy.pensiones.enums.SubscriptionStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.PlanVersionPeriodPrice;
import uy.pensiones.model.PromotionProduct;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerCommercialServiceTest {

    private AppProperties properties;
    private UserRepository users;
    private PlanVersionRepository planVersions;
    private PromotionProductVersionRepository promotionVersions;
    private PensionPromotionRepository promotions;
    private OwnerEntitlementService entitlements;
    private PaymentRuntimeConfigurationService paymentRuntime;
    private PaymentGatewayRegistry paymentGateways;
    private SubscriptionFeaturedBenefitService featuredBenefits;
    private FounderFeaturedBenefitService founderBenefits;
    private OwnerCommercialService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        users = mock(UserRepository.class);
        planVersions = mock(PlanVersionRepository.class);
        promotionVersions = mock(PromotionProductVersionRepository.class);
        promotions = mock(PensionPromotionRepository.class);
        entitlements = mock(OwnerEntitlementService.class);
        paymentRuntime = mock(PaymentRuntimeConfigurationService.class);
        paymentGateways = mock(PaymentGatewayRegistry.class);
        featuredBenefits = mock(SubscriptionFeaturedBenefitService.class);
        founderBenefits = mock(FounderFeaturedBenefitService.class);
        service = new OwnerCommercialService(properties, users, planVersions, promotionVersions,
                promotions, entitlements, paymentRuntime, paymentGateways, featuredBenefits, founderBenefits);
    }

    @Test
    void overviewReturnsOnlyOneEffectiveVersionPerCatalogItemAndOwnerState() {
        properties.getMonetization().setEnabled(true);
        User owner = User.builder().id(10L).email("owner@example.com").role(UserRole.OWNER).build();
        when(users.findById(10L)).thenReturn(Optional.of(owner));

        OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        Plan pro = Plan.builder().id(2L).code("PRO").name("Pro").description("Plan profesional").active(true).build();
        PlanVersion currentPlan = PlanVersion.builder().id(22L).plan(pro).version(2)
                .monthlyPrice(new BigDecimal("1290.00")).currency("UYU")
                .maxPensions(5).maxCollaborators(4).maxPhotos(30).maxVideos(3)
                .featuredDays(5).advancedAnalytics(true).inquiryHistory(true)
                .consolidatedAnalytics(true).exportEnabled(true)
                .effectiveFrom(from).status(PlanVersionStatus.PUBLISHED).build();
        currentPlan.replacePeriodPrices(List.of(
                PlanVersionPeriodPrice.builder().periodMonths(1).totalPrice(new BigDecimal("1290.00")).enabled(true).build(),
                PlanVersionPeriodPrice.builder().periodMonths(3).totalPrice(new BigDecimal("3490.00")).enabled(true).build(),
                PlanVersionPeriodPrice.builder().periodMonths(6).totalPrice(new BigDecimal("6490.00")).enabled(false).build()
        ));
        PlanVersion overlappingOlderPlan = PlanVersion.builder().id(21L).plan(pro).version(1)
                .monthlyPrice(new BigDecimal("990.00")).currency("UYU")
                .effectiveFrom(from.minusMonths(1)).status(PlanVersionStatus.PUBLISHED).build();
        when(planVersions.findEffectivePublishedCatalog(eq(PlanVersionStatus.PUBLISHED), any()))
                .thenReturn(List.of(currentPlan, overlappingOlderPlan));

        PromotionProduct featured = PromotionProduct.builder().id(5L).code("FEATURED_GLOBAL_7")
                .name("Destacado 7 días").description("Más visibilidad")
                .targetType(PromotionTargetType.GLOBAL).durationDays(7).active(true).build();
        PromotionProductVersion currentPromotion = PromotionProductVersion.builder().id(52L).product(featured).version(2)
                .price(new BigDecimal("450.00")).currency("UYU")
                .effectiveFrom(from).status(PromotionProductVersionStatus.PUBLISHED).build();
        when(promotionVersions.findEffectivePublishedCatalog(eq(PromotionProductVersionStatus.PUBLISHED), any()))
                .thenReturn(List.of(currentPromotion));

        Pension pension = Pension.builder().id(80L).name("Pensión Centro").owner(owner).createdBy(owner).build();
        PensionPromotion activePromotion = PensionPromotion.builder().id(90L).pension(pension)
                .productVersion(currentPromotion).targetType(PromotionTargetType.GLOBAL)
                .startsAt(from).endsAt(from.plusDays(7)).status(PensionPromotionStatus.ACTIVE)
                .source(PensionPromotionSource.PAYMENT).build();
        when(promotions.findEffectiveActiveForOwner(eq(10L), any())).thenReturn(List.of(activePromotion));
        var performance = mock(PensionPromotionRepository.OwnerPromotionPerformanceRow.class);
        when(performance.getPromotionId()).thenReturn(90L);
        when(performance.getPensionId()).thenReturn(80L);
        when(performance.getPensionName()).thenReturn("Pensión Centro");
        when(performance.getProductCode()).thenReturn("FEATURED_GLOBAL_7");
        when(performance.getProductName()).thenReturn("Destacado 7 días");
        when(performance.getTargetType()).thenReturn("GLOBAL");
        when(performance.getStartsAt()).thenReturn(Instant.parse("2026-09-01T00:00:00Z"));
        when(performance.getEndsAt()).thenReturn(Instant.parse("2026-09-08T00:00:00Z"));
        when(performance.getEffectiveStatus()).thenReturn("ACTIVE");
        when(performance.getImpressions()).thenReturn(100L);
        when(performance.getClicks()).thenReturn(20L);
        when(performance.getInquiries()).thenReturn(8L);
        when(performance.getConversions()).thenReturn(2L);
        when(promotions.ownerPerformance(10L)).thenReturn(List.of(performance));

        var entitlementSnapshot = new OwnerEntitlementService.EntitlementSnapshot(
                10L, true, true, true, true, EntitlementSource.SUBSCRIPTION,
                null, null,
                new OwnerEntitlementService.EffectivePlan(
                        2L, "PRO", "Pro", 22L, 2, 5, 4, 30, 3, 5,
                        true, true, true, true),
                new OwnerEntitlementService.EffectiveSubscription(
                        70L, SubscriptionSource.PAYMENT, from, from.plusMonths(1)),
                null,
                null,
                new OwnerEntitlementService.UsageSummary(1L, 4, false), List.of(), false);
        when(entitlements.resolve(10L)).thenReturn(entitlementSnapshot);
        when(featuredBenefits.currentUsage(eq(entitlementSnapshot), any())).thenReturn(
                new SubscriptionFeaturedBenefitService.BenefitUsage(
                        true, 70L, from, from.plusMonths(1), 5, 2L, 3, 3, true));
        when(founderBenefits.currentUsage(eq(10L), any()))
                .thenReturn(FounderFeaturedBenefitService.BenefitUsage.unavailable());

        when(paymentRuntime.paymentsEnabled()).thenReturn(true);
        when(paymentRuntime.infrastructureAllowed()).thenReturn(true);
        when(paymentRuntime.databasePaymentsEnabled()).thenReturn(true);
        when(paymentGateways.isAvailableForNewPayments(PaymentProvider.MERCADO_PAGO, PaymentPurpose.SUBSCRIPTION)).thenReturn(true);
        when(paymentGateways.isAvailableForNewPayments(PaymentProvider.MERCADO_PAGO, PaymentPurpose.PROMOTION)).thenReturn(true);

        var result = service.overview(10L);

        assertTrue(result.monetizationEnabled());
        assertEquals(1, result.plans().size());
        assertEquals(22L, result.plans().get(0).versionId());
        assertEquals(new BigDecimal("1290.00"), result.plans().get(0).monthlyPrice());
        assertEquals(2, result.plans().get(0).periodPrices().size());
        assertEquals(3, result.plans().get(0).periodPrices().get(1).periodMonths());
        assertEquals(new BigDecimal("3490.00"), result.plans().get(0).periodPrices().get(1).totalPrice());
        assertEquals(1, result.promotionProducts().size());
        assertEquals(52L, result.promotionProducts().get(0).versionId());
        assertNotNull(result.currentSubscription());
        assertEquals(70L, result.currentSubscription().id());
        assertEquals(SubscriptionStatus.ACTIVE, result.currentSubscription().status());
        assertSame(entitlementSnapshot, result.entitlements());
        assertEquals(3, result.featuredBenefit().remainingDays());
        assertEquals(1, result.activePromotions().size());
        assertEquals(80L, result.activePromotions().get(0).pensionId());
        assertEquals(1, result.promotionPerformance().size());
        assertEquals(100L, result.promotionPerformance().get(0).impressions());
        assertEquals(20L, result.promotionPerformance().get(0).clicks());
        assertEquals(8L, result.promotionPerformance().get(0).inquiries());
        assertEquals(2L, result.promotionPerformance().get(0).conversions());
        assertEquals(new BigDecimal("20.0"), result.promotionPerformance().get(0).clickThroughRate());
        assertEquals(new BigDecimal("40.0"), result.promotionPerformance().get(0).inquiryRate());
        assertEquals(new BigDecimal("25.0"), result.promotionPerformance().get(0).conversionRate());
        assertTrue(result.payments().enabled());
        assertTrue(result.payments().subscriptionsAvailable());
        assertTrue(result.payments().promotionsAvailable());
        assertNull(result.payments().message());
    }

    @Test
    void overviewDoesNotExposeCheckoutAvailabilityWhenInfrastructureBlocksPayments() {
        User owner = User.builder().id(10L).email("owner@example.com").role(UserRole.SEEKER).build();
        when(users.findById(10L)).thenReturn(Optional.of(owner));
        when(planVersions.findEffectivePublishedCatalog(eq(PlanVersionStatus.PUBLISHED), any())).thenReturn(List.of());
        when(promotionVersions.findEffectivePublishedCatalog(eq(PromotionProductVersionStatus.PUBLISHED), any())).thenReturn(List.of());
        when(promotions.findEffectiveActiveForOwner(eq(10L), any())).thenReturn(List.of());
        when(promotions.ownerPerformance(10L)).thenReturn(List.of());
        var bypassSnapshot = new OwnerEntitlementService.EntitlementSnapshot(
                10L, false, false, true, true, EntitlementSource.BYPASS,
                null, null, null, null, null, null,
                new OwnerEntitlementService.UsageSummary(0L, null, false), List.of(), false);
        when(entitlements.resolve(10L)).thenReturn(bypassSnapshot);
        when(featuredBenefits.currentUsage(eq(bypassSnapshot), any()))
                .thenReturn(SubscriptionFeaturedBenefitService.BenefitUsage.unavailable());
        when(founderBenefits.currentUsage(eq(10L), any()))
                .thenReturn(FounderFeaturedBenefitService.BenefitUsage.unavailable());
        when(paymentRuntime.paymentsEnabled()).thenReturn(false);
        when(paymentRuntime.infrastructureAllowed()).thenReturn(false);

        var result = service.overview(10L);

        assertFalse(result.payments().enabled());
        assertFalse(result.payments().subscriptionsAvailable());
        assertFalse(result.payments().promotionsAvailable());
        assertEquals("Los pagos no están disponibles temporalmente.", result.payments().message());
        verifyNoInteractions(paymentGateways);
    }
}
