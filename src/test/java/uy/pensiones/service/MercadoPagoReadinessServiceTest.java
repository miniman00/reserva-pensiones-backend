package uy.pensiones.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;
import uy.pensiones.enums.PaymentRefundStatus;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.model.PaymentProviderConfig;
import uy.pensiones.model.PaymentProviderCredential;
import uy.pensiones.model.PaymentProviderEnvironmentCheck;
import uy.pensiones.model.PaymentSettings;
import uy.pensiones.payment.MercadoPagoPaymentGateway;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.payment.PaymentSecretCrypto;
import uy.pensiones.repo.PaymentChargebackRepository;
import uy.pensiones.repo.PaymentProviderConfigRepository;
import uy.pensiones.repo.PaymentProviderCredentialRepository;
import uy.pensiones.repo.PaymentProviderEventRepository;
import uy.pensiones.repo.PaymentProviderEnvironmentCheckRepository;
import uy.pensiones.repo.PaymentRefundRepository;
import uy.pensiones.repo.PaymentRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MercadoPagoReadinessServiceTest {
    private final PaymentRuntimeConfigurationService runtime = mock(PaymentRuntimeConfigurationService.class);
    private final PaymentSecretCrypto crypto = mock(PaymentSecretCrypto.class);
    private final PaymentProviderConfigRepository providers = mock(PaymentProviderConfigRepository.class);
    private final PaymentProviderCredentialRepository credentials = mock(PaymentProviderCredentialRepository.class);
    private final PaymentProviderEnvironmentCheckRepository environmentChecks = mock(PaymentProviderEnvironmentCheckRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentProviderEventRepository events = mock(PaymentProviderEventRepository.class);
    private final PaymentRefundRepository refunds = mock(PaymentRefundRepository.class);
    private final PaymentChargebackRepository chargebacks = mock(PaymentChargebackRepository.class);
    private MercadoPagoReadinessService service;
    private PaymentSettings settings;
    private PaymentProviderConfig provider;

    @BeforeEach
    void setUp() {
        service = new MercadoPagoReadinessService(runtime, crypto, providers, credentials, environmentChecks, payments, events,
                refunds, chargebacks, new ObjectMapper());
        settings = PaymentSettings.builder()
                .id((short) 1)
                .paymentsEnabled(true)
                .defaultProvider(PaymentProvider.MERCADO_PAGO)
                .automaticReconciliationEnabled(true)
                .operationalAlertEmailEnabled(true)
                .operationalAlertEmailRecipients("ops@example.com")
                .build();
        provider = PaymentProviderConfig.builder()
                .provider(PaymentProvider.MERCADO_PAGO)
                .displayName("Mercado Pago")
                .enabled(true)
                .mode(PaymentProviderMode.LIVE)
                .supportedCurrencies("UYU")
                .supportedCountries("UY")
                .configurationJson("{\"notificationUrl\":\"https://api.example.com/api/public/payment-webhooks/mercado-pago\",\"successUrl\":\"https://portal.example.com/payments/success\",\"failureUrl\":\"https://portal.example.com/payments/failure\",\"pendingUrl\":\"https://portal.example.com/payments/pending\"}")
                .lastConnectivityCheckSuccess(true)
                .lastWebhookAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        when(runtime.settings()).thenReturn(settings);
        when(runtime.infrastructureAllowed()).thenReturn(true);
        when(crypto.isReady()).thenReturn(true);
        when(providers.findById(PaymentProvider.MERCADO_PAGO)).thenReturn(Optional.of(provider));
        PaymentProviderCredential credential = mock(PaymentProviderCredential.class);
        when(credential.getFingerprint()).thenReturn("live-fingerprint");
        when(credentials.findByProviderAndCredentialName(PaymentProvider.MERCADO_PAGO, MercadoPagoPaymentGateway.LIVE_ACCESS_TOKEN))
                .thenReturn(Optional.of(credential));
        when(credentials.findByProviderAndCredentialName(PaymentProvider.MERCADO_PAGO, MercadoPagoPaymentGateway.LIVE_WEBHOOK_SECRET))
                .thenReturn(Optional.of(credential));
        when(environmentChecks.findByProviderAndMode(PaymentProvider.MERCADO_PAGO, PaymentProviderMode.LIVE))
                .thenReturn(Optional.of(PaymentProviderEnvironmentCheck.builder()
                        .provider(PaymentProvider.MERCADO_PAGO).mode(PaymentProviderMode.LIVE)
                        .credentialFingerprint("live-fingerprint").success(true).build()));
    }

    @Test
    void reportsAutomaticLiveReadyAndFullObservedFlowWhenAllEvidenceExists() {
        when(payments.countByProviderAndProviderCheckoutIdIsNotNull(PaymentProvider.MERCADO_PAGO)).thenReturn(3L);
        when(payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNull(PaymentProvider.MERCADO_PAGO, PaymentProviderMode.LIVE)).thenReturn(3L);
        when(payments.countByProviderAndStatusAndFulfilledAtIsNotNull(PaymentProvider.MERCADO_PAGO, PaymentStatus.APPROVED)).thenReturn(2L);
        when(payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNull(PaymentProvider.MERCADO_PAGO, PaymentProviderMode.LIVE, PaymentStatus.APPROVED)).thenReturn(2L);
        when(events.countByProviderAndProcessedAtIsNotNull(PaymentProvider.MERCADO_PAGO)).thenReturn(4L);
        when(events.countByProviderAndProcessedAtIsNotNullAndLiveMode(PaymentProvider.MERCADO_PAGO, true)).thenReturn(4L);
        when(refunds.countByProviderAndStatus(PaymentProvider.MERCADO_PAGO, PaymentRefundStatus.ACCEPTED)).thenReturn(1L);
        when(refunds.countByPayment_ProviderAndPayment_ProviderModeAndStatus(PaymentProvider.MERCADO_PAGO, PaymentProviderMode.LIVE, PaymentRefundStatus.ACCEPTED)).thenReturn(1L);
        when(chargebacks.countByProvider(PaymentProvider.MERCADO_PAGO)).thenReturn(1L);
        when(chargebacks.countByProviderAndLiveMode(PaymentProvider.MERCADO_PAGO, true)).thenReturn(1L);

        var result = service.get();

        assertThat(result.automaticLiveReady()).isTrue();
        assertThat(result.liveConfigurationReady()).isTrue();
        assertThat(result.coreE2eObserved()).isTrue();
        assertThat(result.extendedE2eObserved()).isTrue();
        assertThat(result.overallStatus()).isEqualTo("AUTOMATIC_LIVE_READY_AND_FULL_E2E_OBSERVED");
        assertThat(result.checks()).anySatisfy(c -> {
            assertThat(c.code()).isEqualTo("WEBHOOK_TOPICS");
            assertThat(c.status()).isEqualTo("MANUAL");
        });
    }

    @Test
    void sandboxWebhookDoesNotCountAsLiveEvidenceAfterSwitchingToLive() {
        when(payments.countByProviderAndProviderCheckoutIdIsNotNull(PaymentProvider.MERCADO_PAGO)).thenReturn(1L);
        when(payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNull(PaymentProvider.MERCADO_PAGO, PaymentProviderMode.LIVE)).thenReturn(1L);
        when(payments.countByProviderAndStatusAndFulfilledAtIsNotNull(PaymentProvider.MERCADO_PAGO, PaymentStatus.APPROVED)).thenReturn(1L);
        when(payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNull(PaymentProvider.MERCADO_PAGO, PaymentProviderMode.LIVE, PaymentStatus.APPROVED)).thenReturn(1L);
        when(events.countByProviderAndProcessedAtIsNotNull(PaymentProvider.MERCADO_PAGO)).thenReturn(5L);
        when(events.countByProviderAndProcessedAtIsNotNullAndLiveMode(PaymentProvider.MERCADO_PAGO, true)).thenReturn(0L);

        var result = service.get();

        assertThat(result.coreE2eObserved()).isFalse();
        assertThat(result.evidence().processedWebhooks()).isEqualTo(5L);
        assertThat(result.evidence().processedWebhooksCurrentMode()).isZero();
        assertThat(result.checks().stream().filter(c -> c.code().equals("WEBHOOK_OBSERVED")).findFirst().orElseThrow().status())
                .isEqualTo("PENDING");
    }

    @Test
    void unrestrictedCountryAndCurrencyScopesAreAccepted() {
        provider.setSupportedCurrencies(null);
        provider.setSupportedCountries(null);

        var result = service.get();

        assertThat(result.checks().stream().filter(c -> c.code().equals("UYU_SCOPE") || c.code().equals("UY_SCOPE")))
                .allMatch(c -> c.status().equals("PASS"));
    }

    @Test
    void invalidWebhookUrlBlocksAutomaticLiveReadiness() {
        provider.setConfigurationJson("{\"notificationUrl\":\"http://localhost/api/public/payment-webhooks/mercado-pago\"}");

        var result = service.get();

        assertThat(result.automaticLiveReady()).isFalse();
        assertThat(result.checks().stream().filter(c -> c.code().equals("NOTIFICATION_URL")).findFirst().orElseThrow().status())
                .isEqualTo("FAIL");
    }

    @Test
    void invalidOrLegacyPortalReturnUrlBlocksLiveConfiguration() {
        provider.setConfigurationJson("{\"notificationUrl\":\"https://api.example.com/api/public/payment-webhooks/mercado-pago\",\"successUrl\":\"https://portal.example.com/pagos/exito\",\"failureUrl\":\"https://portal.example.com/payments/failure\",\"pendingUrl\":\"https://portal.example.com/payments/pending\"}");

        var result = service.get();

        assertThat(result.liveConfigurationReady()).isFalse();
        assertThat(result.checks().stream().filter(c -> c.code().equals("SUCCESS_URL")).findFirst().orElseThrow().status())
                .isEqualTo("FAIL");
    }

    @Test
    void reservedPaymentIdInConfiguredReturnUrlIsRejectedByReadiness() {
        provider.setConfigurationJson("{\"notificationUrl\":\"https://api.example.com/api/public/payment-webhooks/mercado-pago\",\"successUrl\":\"https://portal.example.com/payments/success?paymentId=99\",\"failureUrl\":\"https://portal.example.com/payments/failure\",\"pendingUrl\":\"https://portal.example.com/payments/pending\"}");

        var result = service.get();

        assertThat(result.checks().stream().filter(c -> c.code().equals("SUCCESS_URL")).findFirst().orElseThrow().status())
                .isEqualTo("FAIL");
    }

    @Test
    void oldSandboxPaymentEvidenceDoesNotCountAsLiveEvidenceAfterModeSwitch() {
        when(payments.countByProviderAndProviderCheckoutIdIsNotNull(PaymentProvider.MERCADO_PAGO)).thenReturn(8L);
        when(payments.countByProviderAndStatusAndFulfilledAtIsNotNull(PaymentProvider.MERCADO_PAGO, PaymentStatus.APPROVED)).thenReturn(5L);
        when(events.countByProviderAndProcessedAtIsNotNull(PaymentProvider.MERCADO_PAGO)).thenReturn(9L);
        when(events.countByProviderAndProcessedAtIsNotNullAndLiveMode(PaymentProvider.MERCADO_PAGO, true)).thenReturn(1L);

        var result = service.get();

        assertThat(result.evidence().checkoutsCreated()).isEqualTo(8L);
        assertThat(result.evidence().checkoutsCreatedCurrentMode()).isZero();
        assertThat(result.evidence().approvedFulfilledPayments()).isEqualTo(5L);
        assertThat(result.evidence().approvedFulfilledPaymentsCurrentMode()).isZero();
        assertThat(result.coreE2eObserved()).isFalse();
    }

    @Test
    void liveConfigurationCanBeReadyWhileGlobalPaymentsStayClosedForSafeCutover() {
        settings.setPaymentsEnabled(false);

        var result = service.get();

        assertThat(result.liveConfigurationReady()).isTrue();
        assertThat(result.automaticLiveReady()).isFalse();
    }
    @Test
    void productionCanBePreparedAndVerifiedWhileSandboxRemainsActive() {
        provider.setMode(PaymentProviderMode.SANDBOX);
        settings.setPaymentsEnabled(false);

        var result = service.get();

        assertThat(result.preLiveProductionReady()).isTrue();
        assertThat(result.liveAccessTokenVerified()).isTrue();
        assertThat(result.automaticLiveReady()).isFalse();
        assertThat(result.checks().stream().filter(c -> c.code().equals("LIVE_MODE")).findFirst().orElseThrow().status())
                .isEqualTo("FAIL");
    }

    @Test
    void rotatedLiveTokenInvalidatesStoredVerification() {
        PaymentProviderCredential rotated = mock(PaymentProviderCredential.class);
        when(rotated.getFingerprint()).thenReturn("new-live-fingerprint");
        when(credentials.findByProviderAndCredentialName(PaymentProvider.MERCADO_PAGO, MercadoPagoPaymentGateway.LIVE_ACCESS_TOKEN))
                .thenReturn(Optional.of(rotated));

        var result = service.get();

        assertThat(result.liveAccessTokenVerified()).isFalse();
        assertThat(result.preLiveProductionReady()).isFalse();
    }

}