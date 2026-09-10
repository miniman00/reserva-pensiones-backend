package uy.pensiones.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.*;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MercadoPagoCertificationServiceTest {
    private final PaymentProviderCertificationRunRepository runs = mock(PaymentProviderCertificationRunRepository.class);
    private final PaymentProviderCertificationCheckRepository checks = mock(PaymentProviderCertificationCheckRepository.class);
    private final PaymentProviderConfigRepository providers = mock(PaymentProviderConfigRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentProviderEventRepository events = mock(PaymentProviderEventRepository.class);
    private final PaymentRefundRepository refunds = mock(PaymentRefundRepository.class);
    private final PaymentChargebackRepository chargebacks = mock(PaymentChargebackRepository.class);
    private final PaymentRuntimeConfigurationService runtime = mock(PaymentRuntimeConfigurationService.class);
    private final MercadoPagoReadinessService readiness = mock(MercadoPagoReadinessService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminPaymentService adminPayments = mock(AdminPaymentService.class);
    private MercadoPagoCertificationService service;
    private PaymentProviderConfig provider;
    private PaymentSettings settings;
    private BackofficeUser actor;

    @BeforeEach
    void setUp() {
        service = new MercadoPagoCertificationService(runs, checks, providers, payments, events, refunds, chargebacks,
                runtime, readiness, audit, adminPayments, new ObjectMapper().findAndRegisterModules());
        provider = PaymentProviderConfig.builder().provider(PaymentProvider.MERCADO_PAGO).mode(PaymentProviderMode.SANDBOX).build();
        settings = PaymentSettings.builder().id((short) 1).paymentsEnabled(false).build();
        actor = BackofficeUser.builder().id(7L).username("admin").displayName("Admin").build();
        when(providers.findById(PaymentProvider.MERCADO_PAGO)).thenReturn(Optional.of(provider));
        when(runtime.settings()).thenReturn(settings);
        when(audit.requireReason(anyString())).thenAnswer(i -> i.getArgument(0));
        when(readiness.get()).thenReturn(readiness(false, false));
    }

    @Test
    void canStartCertificationBeforeLiveButNotAfterSwitch() {
        when(runs.findFirstByProviderAndStatusOrderByStartedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(runs.findFirstByProviderOrderByStartedAtDesc(any())).thenReturn(Optional.empty());

        assertThat(service.get().canStart()).isTrue();

        provider.setMode(PaymentProviderMode.LIVE);
        assertThat(service.get().canStart()).isFalse();
        assertThatThrownBy(() -> service.start("inicio", actor)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validatesPreLiveOnlyWithSandboxEvidenceManualControlsAndPaymentsClosed() {
        PaymentProviderCertificationRun run = activeRun();
        when(runs.findByIdForUpdate(10L)).thenReturn(Optional.of(run));
        when(checks.findByRun_IdOrderByCheckCodeAsc(10L)).thenReturn(confirmedPreLiveChecks(run));
        when(payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNullAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(PaymentProviderMode.SANDBOX), any())).thenReturn(1L);
        when(payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNullAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(PaymentProviderMode.SANDBOX), eq(PaymentStatus.APPROVED), any())).thenReturn(1L);
        when(events.countByProviderAndProcessedAtIsNotNullAndLiveModeAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(false), any())).thenReturn(1L);
        when(runs.save(any())).thenAnswer(i -> i.getArgument(0));
        when(readiness.get()).thenReturn(readiness(false, true));

        var result = service.validatePreLive(10L, "sandbox validado", actor);

        assertThat(run.getPreLiveValidatedAt()).isNotNull();
        assertThat(result.phase()).isEqualTo("READY_FOR_LIVE_SWITCH");
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_VALIDATE_PAYMENT_PROVIDER_PRE_LIVE),
                eq(AdminAuditEntityType.PAYMENT_PROVIDER_CERTIFICATION), eq(10L), any(), any(), eq("sandbox validado"));
    }

    @Test
    void refusesPreLiveValidationWhileGlobalPaymentsRemainOpen() {
        PaymentProviderCertificationRun run = activeRun();
        settings.setPaymentsEnabled(true);
        when(runs.findByIdForUpdate(10L)).thenReturn(Optional.of(run));
        when(checks.findByRun_IdOrderByCheckCodeAsc(10L)).thenReturn(confirmedPreLiveChecks(run));
        when(payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNullAndCreatedAtGreaterThanEqual(any(), any(), any())).thenReturn(1L);
        when(payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNullAndCreatedAtGreaterThanEqual(any(), any(), any(), any())).thenReturn(1L);
        when(events.countByProviderAndProcessedAtIsNotNullAndLiveModeAndCreatedAtGreaterThanEqual(any(), eq(false), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.validatePreLive(10L, "intento", actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Deshabilitá pagos globalmente");
    }

    @Test
    void liveCertificationRequiresRealLivePaymentEvidenceNotSandboxPayments() {
        PaymentProviderCertificationRun run = activeRun();
        run.setPreLiveValidatedAt(OffsetDateTime.now());
        provider.setMode(PaymentProviderMode.LIVE);
        when(runs.findFirstByProviderAndStatusOrderByStartedAtDesc(PaymentProvider.MERCADO_PAGO, PaymentProviderCertificationStatus.ACTIVE)).thenReturn(Optional.of(run));
        when(checks.findByRun_IdOrderByCheckCodeAsc(10L)).thenReturn(allConfirmedChecks(run));
        when(readiness.get()).thenReturn(readiness(false, true));
        when(events.countByProviderAndProcessedAtIsNotNullAndLiveModeAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(false), any())).thenReturn(4L);
        when(payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNullAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(PaymentProviderMode.SANDBOX), any())).thenReturn(3L);
        when(payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNullAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(PaymentProviderMode.SANDBOX), eq(PaymentStatus.APPROVED), any())).thenReturn(2L);
        when(events.countByProviderAndProcessedAtIsNotNullAndLiveModeAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(true), any())).thenReturn(1L);

        var result = service.get();

        assertThat(result.phase()).isEqualTo("LIVE_VALIDATION");
        assertThat(result.liveEvidence().checkouts()).isZero();
        assertThat(result.completionBlockers()).anyMatch(x -> x.contains("checkout creado realmente en LIVE"));
    }

    @Test
    void createsControlledLiveSmokeCheckoutWhileGlobalMasterRemainsClosed() {
        PaymentProviderCertificationRun run = activeRun();
        run.setPreLiveValidatedAt(OffsetDateTime.now());
        provider.setMode(PaymentProviderMode.LIVE);
        when(runs.findById(10L)).thenReturn(Optional.of(run));
        when(readiness.get()).thenReturn(readiness(false, true));
        var input = new PaymentTransactionService.CreateInput(PaymentPurpose.SUBSCRIPTION, 5L, 8L, 1, null, null, null, "cert-live-1");
        when(adminPayments.createCertificationLiveCheckout(input, "smoke real controlado", actor)).thenReturn(null);

        assertThat(service.createLiveSmokeCheckout(10L, input, "smoke real controlado", actor)).isNull();
        verify(adminPayments).createCertificationLiveCheckout(input, "smoke real controlado", actor);
    }

    @Test
    void refusesControlledLiveSmokeCheckoutIfGlobalMasterWasOpened() {
        PaymentProviderCertificationRun run = activeRun();
        run.setPreLiveValidatedAt(OffsetDateTime.now());
        provider.setMode(PaymentProviderMode.LIVE);
        settings.setPaymentsEnabled(true);
        when(runs.findById(10L)).thenReturn(Optional.of(run));
        when(readiness.get()).thenReturn(readiness(true, true));
        var input = new PaymentTransactionService.CreateInput(PaymentPurpose.SUBSCRIPTION, 5L, 8L, 1, null, null, null, "cert-live-2");

        assertThatThrownBy(() -> service.createLiveSmokeCheckout(10L, input, "intento", actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("master global debe permanecer deshabilitado");
        verifyNoInteractions(adminPayments);
    }

    @Test
    void completesAfterAuthorizedCutoverAndLiveSmokeEvidence() {
        PaymentProviderCertificationRun run = activeRun();
        run.setPreLiveValidatedAt(OffsetDateTime.now());
        provider.setMode(PaymentProviderMode.LIVE);
        when(runs.findByIdForUpdate(10L)).thenReturn(Optional.of(run));
        when(checks.findByRun_IdOrderByCheckCodeAsc(10L)).thenReturn(allConfirmedChecks(run));
        when(readiness.get()).thenReturn(readiness(false, true));
        when(payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNullAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(PaymentProviderMode.LIVE), any())).thenReturn(1L);
        when(payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNullAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(PaymentProviderMode.LIVE), eq(PaymentStatus.APPROVED), any())).thenReturn(1L);
        when(events.countByProviderAndProcessedAtIsNotNullAndLiveModeAndCreatedAtGreaterThanEqual(eq(PaymentProvider.MERCADO_PAGO), eq(true), any())).thenReturn(1L);
        when(runs.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.complete(10L, "certificación final", actor);

        assertThat(run.getStatus()).isEqualTo(PaymentProviderCertificationStatus.CERTIFIED);
        assertThat(run.getCompletionSnapshotJson()).contains("liveEvidence");
        assertThat(result.phase()).isEqualTo("CERTIFIED");
    }

    private PaymentProviderCertificationRun activeRun() {
        return PaymentProviderCertificationRun.builder().id(10L).provider(PaymentProvider.MERCADO_PAGO)
                .status(PaymentProviderCertificationStatus.ACTIVE).startedMode(PaymentProviderMode.SANDBOX)
                .startedByBackoffice(actor).startedAt(OffsetDateTime.now()).build();
    }

    private List<PaymentProviderCertificationCheck> confirmedPreLiveChecks(PaymentProviderCertificationRun run) {
        return Arrays.stream(PaymentProviderCertificationCheckCode.values())
                .map(code -> PaymentProviderCertificationCheck.builder().run(run).checkCode(code)
                        .confirmed(code == PaymentProviderCertificationCheckCode.SANDBOX_ORDER_WEBHOOK_CONFIGURED
                                || code == PaymentProviderCertificationCheckCode.SANDBOX_CHARGEBACK_WEBHOOK_CONFIGURED
                                || code == PaymentProviderCertificationCheckCode.PRODUCTION_CREDENTIALS_ACTIVATED)
                        .confirmedByBackoffice(actor).confirmedAt(OffsetDateTime.now()).build())
                .toList();
    }

    private List<PaymentProviderCertificationCheck> allConfirmedChecks(PaymentProviderCertificationRun run) {
        return Arrays.stream(PaymentProviderCertificationCheckCode.values())
                .map(code -> PaymentProviderCertificationCheck.builder().run(run).checkCode(code).confirmed(true)
                        .confirmedByBackoffice(actor).confirmedAt(OffsetDateTime.now()).build())
                .toList();
    }

    private MercadoPagoReadinessService.ReadinessDTO readiness(boolean automatic, boolean config) {
        return new MercadoPagoReadinessService.ReadinessDTO("TEST", automatic, config, config, config, false, false,
                provider == null ? PaymentProviderMode.SANDBOX : provider.getMode(), OffsetDateTime.now(),
                new MercadoPagoReadinessService.EvidenceDTO(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null), List.of());
    }
}
