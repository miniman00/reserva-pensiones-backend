package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import uy.pensiones.enums.PaymentChargebackStatus;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.model.PaymentChargeback;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentGatewayRegistry;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PaymentChargebackRepository;
import uy.pensiones.repo.PaymentRefundRepository;
import uy.pensiones.repo.PaymentRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentAutomaticReconciliationServiceTest {
    private final PaymentReconciliationStateService state = mock(PaymentReconciliationStateService.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentRefundRepository refunds = mock(PaymentRefundRepository.class);
    private final PaymentChargebackRepository chargebacks = mock(PaymentChargebackRepository.class);
    private final PaymentTransactionService transactions = mock(PaymentTransactionService.class);
    private final PaymentRefundService refundService = mock(PaymentRefundService.class);
    private final PaymentChargebackService chargebackService = mock(PaymentChargebackService.class);
    private final PaymentGatewayRegistry registry = mock(PaymentGatewayRegistry.class);
    private final PaymentRuntimeConfigurationService runtime = mock(PaymentRuntimeConfigurationService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final TaskExecutor directExecutor = Runnable::run;
    private PaymentAutomaticReconciliationService service;
    private PaymentReconciliationStateService.StartRun start;

    @BeforeEach
    void setUp() {
        service = new PaymentAutomaticReconciliationService(state, payments, refunds, chargebacks, transactions,
                refundService, chargebackService, registry, runtime, directExecutor);
        start = new PaymentReconciliationStateService.StartRun(77L, "owner", 5, 50);
        when(state.tryStartAutomatic()).thenReturn(start);
        when(state.heartbeat(77L, "owner")).thenReturn(true);
        when(runtime.paymentsEnabled()).thenReturn(true);
        when(runtime.isProd()).thenReturn(false);
        when(refunds.findAutomaticReconciliationCandidates(anyCollection(), any(), any())).thenReturn(List.of());
        when(chargebacks.findAutomaticReconciliationCandidates(eq(PaymentChargebackStatus.OPEN), any(), any())).thenReturn(List.of());
        when(registry.requireImplemented(PaymentProvider.MERCADO_PAGO)).thenReturn(gateway);
    }

    @Test
    void automaticRunReconcilesPaymentRefundAndChargebackAndReportsSuccessfulStats() {
        PaymentGateway.PaymentLookupRequest paymentLookup = lookup("payment");
        PaymentGateway.PaymentLookupRequest refundLookup = lookup("refund");
        when(payments.findAutomaticReconciliationCandidates(anyCollection(), eq(PaymentStatus.APPROVED), eq(true), any(), any()))
                .thenReturn(List.of(10L));
        when(transactions.automaticTarget(10L)).thenReturn(new PaymentTransactionService.AutomaticTarget(
                10L, PaymentProvider.MERCADO_PAGO, paymentLookup));
        when(gateway.getStatus(paymentLookup)).thenReturn(new PaymentGateway.PaymentStatusResult(
                PaymentStatus.APPROVED, "processed:accredited", "mp-10", BigDecimal.ZERO));
        when(transactions.applyProviderStatusAutomatic(eq(10L), any())).thenReturn(true);

        when(refunds.findAutomaticReconciliationCandidates(anyCollection(), any(), any())).thenReturn(List.of(20L));
        when(refundService.automaticTarget(20L)).thenReturn(new PaymentRefundService.AutomaticTarget(
                20L, 10L, PaymentProvider.MERCADO_PAGO, refundLookup));
        when(gateway.getStatus(refundLookup)).thenReturn(new PaymentGateway.PaymentStatusResult(
                PaymentStatus.REFUNDED, "refunded", "mp-10", new BigDecimal("100.00")));
        when(refundService.reconcileAutomaticObservation(eq(20L), any())).thenReturn(true);

        PaymentChargeback before = PaymentChargeback.builder().id(30L).status(PaymentChargebackStatus.OPEN)
                .coverageApplied(false).documentationStatus("pending").build();
        PaymentChargeback after = PaymentChargeback.builder().id(30L).status(PaymentChargebackStatus.WON)
                .coverageApplied(true).documentationStatus("accepted").build();
        when(chargebacks.findAutomaticReconciliationCandidates(eq(PaymentChargebackStatus.OPEN), any(), any())).thenReturn(List.of(30L));
        when(chargebacks.findById(30L)).thenReturn(Optional.of(before));
        when(chargebackService.refreshTarget(30L)).thenReturn(new PaymentChargebackService.RefreshTarget(
                30L, PaymentProvider.MERCADO_PAGO, "cb-30", "mp-10"));
        when(gateway.getDispute(any())).thenReturn(new PaymentGateway.PaymentDisputeResult(
                "cb-30", List.of("mp-10"), new BigDecimal("100.00"), "UYU", "fraud", true, true,
                true, "accepted", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2), null, null, false));
        when(chargebackService.sync(eq(PaymentProvider.MERCADO_PAGO), any())).thenReturn(after);

        service.schedulerTick();

        var stats = captureFinishedStats();
        assertThat(stats.paymentsChecked()).isEqualTo(1);
        assertThat(stats.paymentsChanged()).isEqualTo(1);
        assertThat(stats.refundsChecked()).isEqualTo(1);
        assertThat(stats.refundsChanged()).isEqualTo(1);
        assertThat(stats.chargebacksChecked()).isEqualTo(1);
        assertThat(stats.chargebacksChanged()).isEqualTo(1);
        assertThat(stats.errors()).isZero();
        assertThat(stats.successfulChecks()).isEqualTo(3);
    }

    @Test
    void providerFailureIsRecordedAndNextCandidateStillRuns() {
        PaymentGateway.PaymentLookupRequest firstLookup = lookup("first");
        PaymentGateway.PaymentLookupRequest secondLookup = lookup("second");
        when(payments.findAutomaticReconciliationCandidates(anyCollection(), eq(PaymentStatus.APPROVED), eq(true), any(), any()))
                .thenReturn(List.of(10L, 11L));
        when(transactions.automaticTarget(10L)).thenReturn(new PaymentTransactionService.AutomaticTarget(
                10L, PaymentProvider.MERCADO_PAGO, firstLookup));
        when(transactions.automaticTarget(11L)).thenReturn(new PaymentTransactionService.AutomaticTarget(
                11L, PaymentProvider.MERCADO_PAGO, secondLookup));
        when(gateway.getStatus(firstLookup)).thenThrow(new IllegalStateException("Proveedor temporalmente caído"));
        when(gateway.getStatus(secondLookup)).thenReturn(new PaymentGateway.PaymentStatusResult(
                PaymentStatus.APPROVED, "processed:accredited", "mp-11", BigDecimal.ZERO));
        when(transactions.applyProviderStatusAutomatic(eq(11L), any())).thenReturn(true);

        service.schedulerTick();

        verify(transactions).recordAutomaticFailure(eq(10L), any(IllegalStateException.class));
        verify(transactions).applyProviderStatusAutomatic(eq(11L), any());
        var stats = captureFinishedStats();
        assertThat(stats.paymentsChecked()).isEqualTo(2);
        assertThat(stats.paymentsChanged()).isEqualTo(1);
        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.successfulChecks()).isEqualTo(1);
        assertThat(stats.message()).contains("Pago #10").contains("Proveedor temporalmente caído");
    }

    @Test
    void losingLeaseAfterProviderResponseStopsWorkerBeforeApplyingStaleResult() {
        PaymentGateway.PaymentLookupRequest lookup = lookup("lease-loss");
        when(payments.findAutomaticReconciliationCandidates(anyCollection(), eq(PaymentStatus.APPROVED), eq(true), any(), any()))
                .thenReturn(List.of(10L));
        when(transactions.automaticTarget(10L)).thenReturn(new PaymentTransactionService.AutomaticTarget(
                10L, PaymentProvider.MERCADO_PAGO, lookup));
        when(gateway.getStatus(lookup)).thenReturn(new PaymentGateway.PaymentStatusResult(
                PaymentStatus.APPROVED, "processed:accredited", "mp-10", BigDecimal.ZERO));
        when(state.heartbeat(77L, "owner")).thenReturn(true, true, false);

        service.schedulerTick();

        verify(transactions, never()).applyProviderStatusAutomatic(anyLong(), any());
        verify(transactions, never()).recordAutomaticFailure(anyLong(), any());
        verify(refunds, never()).findAutomaticReconciliationCandidates(anyCollection(), any(), any());
        var stats = captureFinishedStats();
        assertThat(stats.paymentsChecked()).isEqualTo(1);
        assertThat(stats.paymentsChanged()).isZero();
        assertThat(stats.successfulChecks()).isZero();
        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.message()).contains("perdió el lease");
    }

    @Test
    void mockProviderInProductionIsRecordedAsCandidateFailureWithoutCallingGateway() {
        PaymentGateway.PaymentLookupRequest lookup = lookup("mock");
        when(runtime.isProd()).thenReturn(true);
        when(payments.findAutomaticReconciliationCandidates(anyCollection(), eq(PaymentStatus.APPROVED), eq(true), any(), any()))
                .thenReturn(List.of(10L));
        when(transactions.automaticTarget(10L)).thenReturn(new PaymentTransactionService.AutomaticTarget(
                10L, PaymentProvider.MOCK, lookup));

        service.schedulerTick();

        verify(registry, never()).requireImplemented(PaymentProvider.MOCK);
        verify(transactions).recordAutomaticFailure(eq(10L), any(IllegalStateException.class));
        var stats = captureFinishedStats();
        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.successfulChecks()).isZero();
        assertThat(stats.message()).contains("MOCK no se reconcilia en producción");
    }

    private PaymentGateway.PaymentLookupRequest lookup(String suffix) {
        return new PaymentGateway.PaymentLookupRequest("mp-" + suffix, null, "order-" + suffix,
                "ref-" + suffix, "idempotency-" + suffix);
    }

    private PaymentReconciliationStateService.RunStats captureFinishedStats() {
        var captor = org.mockito.ArgumentCaptor.forClass(PaymentReconciliationStateService.RunStats.class);
        verify(state).finish(eq(77L), eq("owner"), captor.capture());
        return captor.getValue();
    }
}
