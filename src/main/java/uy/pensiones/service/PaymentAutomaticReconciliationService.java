package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uy.pensiones.enums.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.PaymentChargeback;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentGatewayRegistry;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PaymentChargebackRepository;
import uy.pensiones.repo.PaymentRefundRepository;
import uy.pensiones.repo.PaymentRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class PaymentAutomaticReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(PaymentAutomaticReconciliationService.class);
    private final PaymentReconciliationStateService state;
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final PaymentChargebackRepository chargebacks;
    private final PaymentTransactionService transactions;
    private final PaymentRefundService refundService;
    private final PaymentChargebackService chargebackService;
    private final PaymentGatewayRegistry registry;
    private final PaymentRuntimeConfigurationService runtime;
    private final TaskExecutor executor;

    public PaymentAutomaticReconciliationService(PaymentReconciliationStateService state, PaymentRepository payments,
            PaymentRefundRepository refunds, PaymentChargebackRepository chargebacks,
            PaymentTransactionService transactions, PaymentRefundService refundService,
            PaymentChargebackService chargebackService, PaymentGatewayRegistry registry,
            PaymentRuntimeConfigurationService runtime,
            @Qualifier("paymentReconciliationExecutor") TaskExecutor executor) {
        this.state=state; this.payments=payments; this.refunds=refunds; this.chargebacks=chargebacks;
        this.transactions=transactions; this.refundService=refundService; this.chargebackService=chargebackService;
        this.registry=registry; this.runtime=runtime; this.executor=executor;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void schedulerTick() {
        try {
            var start = state.tryStartAutomatic();
            if (start != null) {
                try { executor.execute(() -> execute(start)); }
                catch (RuntimeException e) {
                    state.finish(start.runId(), start.owner(), new PaymentReconciliationStateService.RunStats(0,0,0,0,0,0,1,0,"No se pudo encolar la ejecución automática: " + e.getMessage()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo iniciar la conciliación automática: {}", e.getMessage());
        }
    }

    public PaymentReconciliationStateService.RunDTO triggerManual(BackofficeUser actor, String reason) {
        var start = state.startManual(actor, reason);
        try { executor.execute(() -> execute(start)); }
        catch (RuntimeException e) {
            state.finish(start.runId(), start.owner(), new PaymentReconciliationStateService.RunStats(0,0,0,0,0,0,1,0,"No se pudo encolar la ejecución manual: " + e.getMessage()));
            throw e;
        }
        return state.run(start.runId());
    }

    private void execute(PaymentReconciliationStateService.StartRun start) {
        int pc=0,pchg=0,rc=0,rchg=0,cc=0,cchg=0,errors=0,successful=0;
        List<String> errorMessages = new ArrayList<>();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(start.intervalMinutes());
        try {
            ensureLease(start);
            List<Long> paymentIds = payments.findAutomaticReconciliationCandidates(
                    List.of(PaymentStatus.CREATED, PaymentStatus.PENDING), PaymentStatus.APPROVED,
                    runtime.paymentsEnabled(), before, PageRequest.of(0,start.batchSize()));
            for (Long id : paymentIds) {
                ensureLease(start);
                pc++;
                try {
                    if (reconcilePayment(id, start)) pchg++;
                    successful++;
                } catch (LeaseLostException e) {
                    throw e;
                } catch (RuntimeException e) {
                    ensureLease(start);
                    errors++; remember(errorMessages,"Pago #"+id,e); safe(() -> transactions.recordAutomaticFailure(id,e));
                }
            }

            ensureLease(start);
            List<Long> refundIds = refunds.findAutomaticReconciliationCandidates(
                    List.of(PaymentRefundStatus.REQUESTED, PaymentRefundStatus.UNKNOWN), before, PageRequest.of(0,start.batchSize()));
            for (Long id : refundIds) {
                ensureLease(start);
                rc++;
                try {
                    if (reconcileRefund(id, start)) rchg++;
                    successful++;
                } catch (LeaseLostException e) {
                    throw e;
                } catch (RuntimeException e) {
                    ensureLease(start);
                    errors++; remember(errorMessages,"Refund #"+id,e); safe(() -> refundService.recordAutomaticFailure(id,e));
                }
            }

            ensureLease(start);
            List<Long> chargebackIds = chargebacks.findAutomaticReconciliationCandidates(
                    PaymentChargebackStatus.OPEN, before, PageRequest.of(0,start.batchSize()));
            for (Long id : chargebackIds) {
                ensureLease(start);
                cc++;
                try {
                    if (reconcileChargeback(id, start)) cchg++;
                    successful++;
                } catch (LeaseLostException e) {
                    throw e;
                } catch (RuntimeException e) {
                    ensureLease(start);
                    errors++; remember(errorMessages,"Contracargo #"+id,e); safe(() -> chargebackService.recordAutomaticFailure(id,e));
                }
            }
        } catch (LeaseLostException lost) {
            errors++;
            remember(errorMessages,"Ejecución",lost);
            log.warn("La conciliación {} perdió el lease y se detuvo para evitar escrituras obsoletas", start.runId());
        } catch (RuntimeException fatal) {
            errors++;
            remember(errorMessages,"Ejecución",fatal);
        } finally {
            String message = "Pagos " + pc + " (" + pchg + " cambios), refunds " + rc + " (" + rchg
                    + " cambios), contracargos " + cc + " (" + cchg + " cambios), errores " + errors;
            if (!errorMessages.isEmpty()) message += " · " + String.join(" | ", errorMessages);
            state.finish(start.runId(), start.owner(), new PaymentReconciliationStateService.RunStats(pc,pchg,rc,rchg,cc,cchg,errors,successful,message));
        }
    }

    private boolean reconcilePayment(Long id, PaymentReconciliationStateService.StartRun start) {
        var target = transactions.automaticTarget(id);
        if (target.provider() == PaymentProvider.MOCK && runtime.isProd()) throw new IllegalStateException("MOCK no se reconcilia en producción");
        PaymentGateway gateway = registry.requireImplemented(target.provider());
        PaymentGateway.PaymentStatusResult result = gateway.getStatus(target.lookup());
        ensureLease(start);
        return transactions.applyProviderStatusAutomatic(id, result);
    }

    private boolean reconcileRefund(Long id, PaymentReconciliationStateService.StartRun start) {
        var target = refundService.automaticTarget(id);
        if (target.provider() == PaymentProvider.MOCK && runtime.isProd()) throw new IllegalStateException("MOCK no se reconcilia en producción");
        PaymentGateway gateway = registry.requireImplemented(target.provider());
        PaymentGateway.PaymentStatusResult result = gateway.getStatus(target.lookup());
        ensureLease(start);
        return refundService.reconcileAutomaticObservation(id, result);
    }

    private boolean reconcileChargeback(Long id, PaymentReconciliationStateService.StartRun start) {
        PaymentChargeback before = chargebacks.findById(id).orElseThrow();
        var target = chargebackService.refreshTarget(id);
        if (target.provider() == PaymentProvider.MOCK && runtime.isProd()) throw new IllegalStateException("MOCK no se reconcilia en producción");
        PaymentGateway gateway = registry.requireImplemented(target.provider());
        var result = gateway.getDispute(new PaymentGateway.PaymentDisputeLookupRequest(target.providerChargebackId(), target.providerPaymentId()));
        ensureLease(start);
        PaymentChargeback after = chargebackService.sync(target.provider(), result);
        return before.getStatus()!=after.getStatus()
                || !java.util.Objects.equals(before.getCoverageApplied(),after.getCoverageApplied())
                || !java.util.Objects.equals(before.getDocumentationStatus(),after.getDocumentationStatus())
                || !java.util.Objects.equals(before.getDocumentationDeadline(),after.getDocumentationDeadline());
    }

    private void ensureLease(PaymentReconciliationStateService.StartRun start) {
        if (!state.heartbeat(start.runId(), start.owner())) {
            throw new LeaseLostException("La ejecución perdió el lease activo; se canceló para evitar una conciliación duplicada");
        }
    }

    private static final class LeaseLostException extends RuntimeException {
        private LeaseLostException(String message) { super(message); }
    }

    private void remember(List<String> messages, String label, RuntimeException e) {
        if (messages.size() >= 3) return;
        String value = e.getMessage(); if (value == null || value.isBlank()) value = e.getClass().getSimpleName();
        if (value.length() > 180) value = value.substring(0,180);
        messages.add(label + ": " + value);
    }
    private void safe(Runnable r) { try { r.run(); } catch (RuntimeException ignored) { } }
}
