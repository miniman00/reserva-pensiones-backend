package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.enums.PromotionProductVersionStatus;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.enums.SubscriptionStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.OwnerSubscription;
import uy.pensiones.model.PaymentRecord;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.PlanVersionPeriodPrice;
import uy.pensiones.model.PaymentProviderConfig;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PromotionProduct;
import uy.pensiones.model.PromotionProductVersion;
import uy.pensiones.model.User;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentTransactionMarketplaceTest {

    private PaymentRepository payments;
    private AdminSubscriptionUserRepository users;
    private PlanRepository plans;
    private PlanVersionRepository planVersions;
    private PensionRepository pensions;
    private PromotionProductRepository promotionProducts;
    private PromotionProductVersionRepository promotionVersions;
    private StudyCenterCatalogRepository studyCenters;
    private OwnerSubscriptionRepository subscriptions;
    private PensionPromotionRepository promotions;
    private PaymentRuntimeConfigurationService paymentRuntime;
    private PaymentTransactionService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        users = mock(AdminSubscriptionUserRepository.class);
        plans = mock(PlanRepository.class);
        planVersions = mock(PlanVersionRepository.class);
        pensions = mock(PensionRepository.class);
        promotionProducts = mock(PromotionProductRepository.class);
        promotionVersions = mock(PromotionProductVersionRepository.class);
        studyCenters = mock(StudyCenterCatalogRepository.class);
        subscriptions = mock(OwnerSubscriptionRepository.class);
        promotions = mock(PensionPromotionRepository.class);
        paymentRuntime = mock(PaymentRuntimeConfigurationService.class);
        service = new PaymentTransactionService(
                payments, mock(PaymentStatusHistoryRepository.class), users, plans, planVersions, pensions,
                promotionProducts, promotionVersions, studyCenters, subscriptions, promotions,
                mock(PaymentFulfillmentService.class), paymentRuntime, mock(AdminAuditService.class));

        when(paymentRuntime.provider(PaymentProvider.MERCADO_PAGO)).thenReturn(
                PaymentProviderConfig.builder().provider(PaymentProvider.MERCADO_PAGO).mode(PaymentProviderMode.SANDBOX).build());
        when(payments.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void marketplaceSubscriptionIsRejectedBeforePaymentWhenAnotherSubscriptionIsActive() {
        User owner = User.builder().id(10L).email("owner@example.com").role(UserRole.OWNER).build();
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(List.of(OwnerSubscription.builder().id(99L).user(owner).status(SubscriptionStatus.ACTIVE).build()));

        var input = new PaymentTransactionService.CreateInput(
                PaymentPurpose.SUBSCRIPTION, 999L, 22L, 1, null, null, null, "portal:10:attempt");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.prepareMarketplace(PaymentProvider.MERCADO_PAGO, input, 10L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("suscripción activa"));
        verify(payments, never()).save(any());
        verifyNoInteractions(plans, planVersions);
    }

    @Test
    void marketplacePromotionIsRejectedBeforePaymentWhenSameTargetWouldOverlap() {
        OffsetDateTime now = OffsetDateTime.now().minusMinutes(1);
        User owner = User.builder().id(10L).email("owner@example.com").role(UserRole.OWNER).build();
        Pension pension = Pension.builder().id(80L).owner(owner).createdBy(owner).status(PensionStatus.PUBLISHED)
                .moderationBlocked(false).build();
        PromotionProduct product = PromotionProduct.builder().id(5L).active(true).targetType(PromotionTargetType.GLOBAL)
                .durationDays(7).build();
        PromotionProductVersion version = PromotionProductVersion.builder().id(52L).product(product)
                .price(new BigDecimal("450.00")).currency("UYU").status(PromotionProductVersionStatus.PUBLISHED)
                .effectiveFrom(now).build();

        when(pensions.findByIdForEntitlementUpdate(80L)).thenReturn(Optional.of(pension));
        when(promotionVersions.findProductIdByVersionId(52L)).thenReturn(Optional.of(5L));
        when(promotionProducts.findByIdForUpdate(5L)).thenReturn(Optional.of(product));
        when(promotionVersions.findById(52L)).thenReturn(Optional.of(version));
        when(promotions.countOverlapping(eq(80L), eq("GLOBAL"), isNull(), any(), any())).thenReturn(1L);

        var input = new PaymentTransactionService.CreateInput(
                PaymentPurpose.PROMOTION, null, null, null, 80L, 52L, null, "portal:10:promotion");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.prepareMarketplace(PaymentProvider.MERCADO_PAGO, input, 10L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("solapa"));
        verify(payments, never()).save(any());
    }
    @Test
    void subscriptionUsesConfiguredPeriodTotalInsteadOfMultiplyingMonthlyPrice() {
        OffsetDateTime now = OffsetDateTime.now().minusMinutes(1);
        User owner = User.builder().id(10L).email("owner@example.com").countryCode("UY").role(UserRole.OWNER).build();
        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(true).build();
        PlanVersion version = PlanVersion.builder().id(22L).plan(plan).version(1)
                .monthlyPrice(new BigDecimal("590.00")).currency("UYU")
                .status(PlanVersionStatus.PUBLISHED).effectiveFrom(now).build();
        version.replacePeriodPrices(List.of(
                PlanVersionPeriodPrice.builder().periodMonths(1).totalPrice(new BigDecimal("590.00")).enabled(true).build(),
                PlanVersionPeriodPrice.builder().periodMonths(3).totalPrice(new BigDecimal("1590.00")).enabled(true).build(),
                PlanVersionPeriodPrice.builder().periodMonths(6).totalPrice(new BigDecimal("2890.00")).enabled(false).build()
        ));

        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));
        when(planVersions.findPlanIdByVersionId(22L)).thenReturn(Optional.of(2L));
        when(plans.findByIdForUpdate(2L)).thenReturn(Optional.of(plan));
        when(planVersions.findById(22L)).thenReturn(Optional.of(version));
        when(payments.save(any(PaymentRecord.class))).thenAnswer(invocation -> {
            PaymentRecord payment = invocation.getArgument(0);
            payment.setId(77L);
            return payment;
        });

        var input = new PaymentTransactionService.CreateInput(
                PaymentPurpose.SUBSCRIPTION, 10L, 22L, 3, null, null, null, "portal:10:period-3");

        var prepared = service.prepareMarketplace(PaymentProvider.MERCADO_PAGO, input, 10L);

        assertEquals(new BigDecimal("1590.00"), prepared.gatewayRequest().amount());
        assertEquals(77L, prepared.paymentId());
        ArgumentCaptor<PaymentRecord> saved = ArgumentCaptor.forClass(PaymentRecord.class);
        verify(payments).save(saved.capture());
        assertEquals(3, saved.getValue().getSubscriptionPeriodMonths());
        assertEquals(new BigDecimal("1590.00"), saved.getValue().getAmount());
    }

    @Test
    void subscriptionRejectsDisabledOrUnconfiguredPeriod() {
        OffsetDateTime now = OffsetDateTime.now().minusMinutes(1);
        User owner = User.builder().id(10L).email("owner@example.com").countryCode("UY").role(UserRole.OWNER).build();
        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(true).build();
        PlanVersion version = PlanVersion.builder().id(22L).plan(plan).version(1)
                .monthlyPrice(new BigDecimal("590.00")).currency("UYU")
                .status(PlanVersionStatus.PUBLISHED).effectiveFrom(now).build();
        version.replacePeriodPrices(List.of(
                PlanVersionPeriodPrice.builder().periodMonths(1).totalPrice(new BigDecimal("590.00")).enabled(true).build(),
                PlanVersionPeriodPrice.builder().periodMonths(6).totalPrice(new BigDecimal("2890.00")).enabled(false).build()
        ));

        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));
        when(planVersions.findPlanIdByVersionId(22L)).thenReturn(Optional.of(2L));
        when(plans.findByIdForUpdate(2L)).thenReturn(Optional.of(plan));
        when(planVersions.findById(22L)).thenReturn(Optional.of(version));

        var input = new PaymentTransactionService.CreateInput(
                PaymentPurpose.SUBSCRIPTION, 10L, 22L, 6, null, null, null, "portal:10:period-6");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.prepareMarketplace(PaymentProvider.MERCADO_PAGO, input, 10L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("período seleccionado"));
        verify(payments, never()).save(any());
    }

}
