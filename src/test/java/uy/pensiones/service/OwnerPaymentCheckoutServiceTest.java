package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.PaymentProviderConfig;
import uy.pensiones.model.PaymentRecord;
import uy.pensiones.model.PaymentSettings;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentGatewayRegistry;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PaymentProviderConfigRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PlanVersionRepository;
import uy.pensiones.repo.PromotionProductVersionRepository;
import uy.pensiones.repo.UserRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerPaymentCheckoutServiceTest {

    private AppProperties properties;
    private UserRepository users;
    private PensionRepository pensions;
    private PlanVersionRepository planVersions;
    private PromotionProductVersionRepository promotionVersions;
    private PaymentProviderConfigRepository providerConfigs;
    private PaymentRuntimeConfigurationService paymentRuntime;
    private PaymentGatewayRegistry gateways;
    private PaymentTransactionService transactions;
    private OwnerPaymentCheckoutService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getMonetization().setEnabled(true);
        users = mock(UserRepository.class);
        pensions = mock(PensionRepository.class);
        planVersions = mock(PlanVersionRepository.class);
        promotionVersions = mock(PromotionProductVersionRepository.class);
        providerConfigs = mock(PaymentProviderConfigRepository.class);
        paymentRuntime = mock(PaymentRuntimeConfigurationService.class);
        gateways = mock(PaymentGatewayRegistry.class);
        transactions = mock(PaymentTransactionService.class);
        service = new OwnerPaymentCheckoutService(properties, users, pensions, planVersions, promotionVersions,
                providerConfigs, paymentRuntime, gateways, transactions);
    }

    @Test
    void subscriptionCheckoutUsesServerCatalogAndConfiguredProviderWithoutExposingProvider() {
        User owner = User.builder().id(10L).email("owner@example.com").countryCode("UY").role(UserRole.OWNER).build();
        PlanVersion version = PlanVersion.builder().id(22L).monthlyPrice(new BigDecimal("1290.00")).currency("UYU").build();
        when(users.findById(10L)).thenReturn(Optional.of(owner));
        when(planVersions.findById(22L)).thenReturn(Optional.of(version));
        when(paymentRuntime.settings()).thenReturn(PaymentSettings.builder().id((short) 1).defaultProvider(PaymentProvider.MERCADO_PAGO).build());
        when(gateways.isAvailableForNewPayments(PaymentProvider.MERCADO_PAGO, PaymentPurpose.SUBSCRIPTION, "UYU", "UY"))
                .thenReturn(true);

        var gatewayRequest = new PaymentGateway.PaymentCreationRequest(
                "pay-1", "portal:10:attempt-1", PaymentPurpose.SUBSCRIPTION, 10L, owner.getEmail(),
                new BigDecimal("1290.00"), "UYU", "Suscripción", Map.of());
        when(transactions.prepareMarketplace(eq(PaymentProvider.MERCADO_PAGO), any(), eq(10L)))
                .thenReturn(new PaymentTransactionService.PreparedPayment(55L, gatewayRequest, true));

        PaymentRecord beforeProvider = PaymentRecord.builder().id(55L).user(owner).purpose(PaymentPurpose.SUBSCRIPTION)
                .status(PaymentStatus.CREATED).build();
        PaymentRecord afterProvider = PaymentRecord.builder().id(55L).user(owner).purpose(PaymentPurpose.SUBSCRIPTION)
                .status(PaymentStatus.PENDING).checkoutUrl("https://checkout.example/55").build();
        when(transactions.detailEntity(55L)).thenReturn(beforeProvider, afterProvider);

        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateways.requireEnabled(PaymentProvider.MERCADO_PAGO, PaymentPurpose.SUBSCRIPTION)).thenReturn(gateway);
        when(gateway.createPayment(gatewayRequest)).thenReturn(new PaymentGateway.PaymentCreationResult(
                null, null, "order-55", "https://checkout.example/55", PaymentStatus.PENDING, "pending"));

        var result = service.createSubscriptionCheckout(10L, 22L, 1, "attempt-1");

        assertEquals(55L, result.paymentId());
        assertEquals(PaymentPurpose.SUBSCRIPTION, result.purpose());
        assertEquals(PaymentStatus.PENDING, result.status());
        assertEquals("https://checkout.example/55", result.checkoutUrl());

        ArgumentCaptor<PaymentTransactionService.CreateInput> input = ArgumentCaptor.forClass(PaymentTransactionService.CreateInput.class);
        verify(transactions).prepareMarketplace(eq(PaymentProvider.MERCADO_PAGO), input.capture(), eq(10L));
        assertEquals(10L, input.getValue().userId());
        assertEquals(22L, input.getValue().planVersionId());
        assertEquals("portal:10:attempt-1", input.getValue().idempotencyKey());
        verify(transactions).attachProviderResult(eq(55L), any());
    }

    @Test
    void providerSelectionFallsBackByDatabasePriorityAndNeverUsesMockForPortal() {
        User owner = User.builder().id(10L).email("owner@example.com").countryCode("UY").role(UserRole.OWNER).build();
        PlanVersion version = PlanVersion.builder().id(22L).currency("UYU").build();
        when(users.findById(10L)).thenReturn(Optional.of(owner));
        when(planVersions.findById(22L)).thenReturn(Optional.of(version));
        when(paymentRuntime.settings()).thenReturn(PaymentSettings.builder().id((short) 1).defaultProvider(PaymentProvider.MOCK).build());
        when(providerConfigs.findAllByOrderByPriorityAscProviderAsc()).thenReturn(List.of(
                PaymentProviderConfig.builder().provider(PaymentProvider.MOCK).priority(1).build(),
                PaymentProviderConfig.builder().provider(PaymentProvider.MERCADO_PAGO).priority(2).build()));
        when(gateways.isAvailableForNewPayments(PaymentProvider.MERCADO_PAGO, PaymentPurpose.SUBSCRIPTION, "UYU", "UY"))
                .thenReturn(true);

        var request = new PaymentGateway.PaymentCreationRequest(
                "pay-2", "portal:10:attempt-2", PaymentPurpose.SUBSCRIPTION, 10L, owner.getEmail(),
                BigDecimal.ONE, "UYU", "Suscripción", Map.of());
        when(transactions.prepareMarketplace(eq(PaymentProvider.MERCADO_PAGO), any(), eq(10L)))
                .thenReturn(new PaymentTransactionService.PreparedPayment(56L, request, true));
        when(transactions.detailEntity(56L)).thenReturn(
                PaymentRecord.builder().id(56L).user(owner).purpose(PaymentPurpose.SUBSCRIPTION).status(PaymentStatus.CREATED).build(),
                PaymentRecord.builder().id(56L).user(owner).purpose(PaymentPurpose.SUBSCRIPTION).status(PaymentStatus.PENDING)
                        .checkoutUrl("https://checkout.example/56").build());
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateways.requireEnabled(PaymentProvider.MERCADO_PAGO, PaymentPurpose.SUBSCRIPTION)).thenReturn(gateway);
        when(gateway.createPayment(request)).thenReturn(new PaymentGateway.PaymentCreationResult(
                null, null, "order-56", "https://checkout.example/56", PaymentStatus.PENDING, "pending"));

        service.createSubscriptionCheckout(10L, 22L, 1, "attempt-2");

        verify(gateways, never()).isAvailableForNewPayments(eq(PaymentProvider.MOCK), any(), any(), any());
        verify(transactions).prepareMarketplace(eq(PaymentProvider.MERCADO_PAGO), any(), eq(10L));
    }

    @Test
    void promotionCheckoutRejectsPensionOwnedByAnotherUserBeforeCreatingPayment() {
        User requester = User.builder().id(10L).email("owner@example.com").countryCode("UY").role(UserRole.OWNER).build();
        User otherOwner = User.builder().id(11L).email("other@example.com").countryCode("UY").role(UserRole.OWNER).build();
        Pension pension = Pension.builder().id(80L).owner(otherOwner).createdBy(otherOwner).build();
        when(users.findById(10L)).thenReturn(Optional.of(requester));
        when(pensions.findWithOwnerById(80L)).thenReturn(Optional.of(pension));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createPromotionCheckout(10L, 80L, 52L, null, "attempt-3"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(transactions);
        verifyNoInteractions(promotionVersions);
    }

    @Test
    void checkoutFailsClearlyWhenOnlyMockIsConfigured() {
        User owner = User.builder().id(10L).email("owner@example.com").countryCode("UY").role(UserRole.OWNER).build();
        PlanVersion version = PlanVersion.builder().id(22L).currency("UYU").build();
        when(users.findById(10L)).thenReturn(Optional.of(owner));
        when(planVersions.findById(22L)).thenReturn(Optional.of(version));
        when(paymentRuntime.settings()).thenReturn(PaymentSettings.builder().id((short) 1).defaultProvider(PaymentProvider.MOCK).build());
        when(providerConfigs.findAllByOrderByPriorityAscProviderAsc()).thenReturn(List.of(
                PaymentProviderConfig.builder().provider(PaymentProvider.MOCK).priority(1).build()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createSubscriptionCheckout(10L, 22L, 1, "attempt-4"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        assertTrue(ex.getReason().contains("No hay una forma de pago disponible"));
        verifyNoInteractions(transactions);
    }
}
