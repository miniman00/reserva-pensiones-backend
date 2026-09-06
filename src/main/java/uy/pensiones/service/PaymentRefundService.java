package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentRefundService {
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final PaymentTransactionService transactions;
    private final PaymentBenefitReconciliationService benefitReconciliation;
    private final PaymentRuntimeConfigurationService runtime;
    private final AdminAuditService audit;

    public PaymentRefundService(PaymentRepository payments, PaymentRefundRepository refunds,
                                PaymentTransactionService transactions, PaymentBenefitReconciliationService benefitReconciliation,
                                PaymentRuntimeConfigurationService runtime, AdminAuditService audit) {
        this.payments = payments; this.refunds = refunds; this.transactions = transactions;
        this.benefitReconciliation = benefitReconciliation; this.runtime = runtime; this.audit = audit;
    }

    @Transactional
    public PreparedRefund prepare(Long paymentId, BigDecimal partialAmount, String idempotencyKey, String reason, BackofficeUser actor) {
        String key = cleanKey(idempotencyKey);
        String why = audit.requireReason(reason);
        PaymentRefund existing = refunds.findByIdempotencyKeyForUpdate(key).orElse(null);
        if (existing != null) {
            assertSame(existing, paymentId, partialAmount);
            if (existing.getStatus() == PaymentRefundStatus.ACCEPTED) return prepared(existing, true);
            return prepareRetryLocked(existing, actor);
        }

        PaymentRecord payment = lockPayment(paymentId);
        requireRefundablePayment(payment);
        if (payment.getProvider() == PaymentProvider.MOCK && runtime.isProd()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No se admiten devoluciones MOCK en producción");
        }
        if (refunds.existsByPayment_IdAndStatusIn(payment.getId(), List.of(PaymentRefundStatus.REQUESTED, PaymentRefundStatus.UNKNOWN))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Existe una devolución con resultado incierto o en proceso; reintentala con su misma clave antes de crear otra");
        }

        BigDecimal acceptedLocal = refunds.sumAmountByPaymentAndStatus(payment.getId(), PaymentRefundStatus.ACCEPTED);
        BigDecimal alreadyRefunded = providerRefunded(payment).max(acceptedLocal == null ? BigDecimal.ZERO : acceptedLocal);
        BigDecimal remaining = payment.getAmount().subtract(alreadyRefunded).setScale(2, RoundingMode.HALF_UP);
        if (remaining.signum() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "El pago ya no tiene saldo reembolsable");

        PaymentRefundType type;
        BigDecimal requested;
        if (partialAmount == null) {
            if (alreadyRefunded.signum() > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Después de un reembolso parcial, devolvé el saldo restante como reembolso parcial explícito");
            }
            type = PaymentRefundType.FULL;
            requested = payment.getAmount();
        } else {
            requested = money(partialAmount);
            if (requested.signum() <= 0) throw bad("El monto parcial debe ser mayor que cero");
            if (requested.compareTo(remaining) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El monto solicitado supera el saldo reembolsable de " + remaining.toPlainString() + " " + payment.getCurrency());
            type = PaymentRefundType.PARTIAL;
        }

        PaymentRefund refund = refunds.save(PaymentRefund.builder()
                .payment(payment).provider(payment.getProvider()).refundType(type).requestedAmount(requested)
                .currency(payment.getCurrency()).idempotencyKey(key).status(PaymentRefundStatus.REQUESTED)
                .reason(why).attemptCount(1).requestedByBackoffice(actor).build());
        audit.record(actor, AdminAuditAction.ADMIN_REQUEST_PAYMENT_REFUND, AdminAuditEntityType.PAYMENT_REFUND,
                refund.getId(), null, snapshot(refund), why);
        return prepared(refund, false);
    }

    @Transactional
    public PreparedRefund prepareRetry(Long paymentId, Long refundId, BackofficeUser actor) {
        PaymentRefund refund = refunds.findByIdForUpdate(requireId(refundId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reembolso no encontrado"));
        if (!refund.getPayment().getId().equals(requireId(paymentId))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reembolso no encontrado para ese pago");
        if (refund.getStatus() == PaymentRefundStatus.ACCEPTED) return prepared(refund, true);
        return prepareRetryLocked(refund, actor);
    }

    private PreparedRefund prepareRetryLocked(PaymentRefund refund, BackofficeUser actor) {
        PaymentRecord payment = lockPayment(refund.getPayment().getId());
        if (payment.getStatus() != PaymentStatus.APPROVED && payment.getStatus() != PaymentStatus.REFUNDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El estado actual del pago no permite reintentar la devolución");
        }
        refund.setStatus(PaymentRefundStatus.REQUESTED);
        refund.setAttemptCount(refund.getAttemptCount() + 1);
        refund.setLastError(null);
        refund.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        refund.setLastReconciliationError(null);
        refunds.save(refund);
        audit.record(actor, AdminAuditAction.ADMIN_RETRY_PAYMENT_REFUND, AdminAuditEntityType.PAYMENT_REFUND,
                refund.getId(), null, snapshot(refund), refund.getReason());
        return prepared(refund, false);
    }

    @Transactional
    public void accept(Long refundId, PaymentGateway.PaymentStatusResult result) {
        PaymentRefund refund = refunds.findByIdForUpdate(requireId(refundId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reembolso no encontrado"));
        if (refund.getStatus() == PaymentRefundStatus.ACCEPTED) return;
        PaymentRecord payment = transactions.applyProviderStatusRefund(refund.getPayment().getId(), result,
                "Devolución solicitada desde Backoffice #" + refund.getId(), refund.getRequestedAmount());
        refund.setStatus(PaymentRefundStatus.ACCEPTED);
        refund.setProviderStatus(result.providerStatus());
        refund.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        refund.setLastError(null);
        refund.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        refund.setLastReconciliationError(null);
        refunds.save(refund);
    }

    @Transactional
    public void fail(Long refundId, RuntimeException error) {
        PaymentRefund refund = refunds.findByIdForUpdate(requireId(refundId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reembolso no encontrado"));
        if (refund.getStatus() == PaymentRefundStatus.ACCEPTED) return;
        refund.setStatus(isUncertain(error) ? PaymentRefundStatus.UNKNOWN : PaymentRefundStatus.FAILED);
        refund.setLastError(safeError(error));
        refund.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        refund.setLastReconciliationError(safeError(error));
        refunds.save(refund);
    }

    @Transactional
    public PaymentRecord reconcileBenefit(Long paymentId, RefundBenefitDecision decision, String reason, BackofficeUser actor) {
        if (decision == null) throw bad("La decisión sobre el beneficio es obligatoria");
        String why = audit.requireReason(reason);
        PaymentRecord payment = lockPayment(paymentId);
        if (payment.getFulfilledAt() == null || payment.getFulfillmentErrorCode() == null
                || !(payment.getFulfillmentErrorCode().equals("REFUND_REQUIRES_BENEFIT_REVIEW")
                || payment.getFulfillmentErrorCode().equals("PARTIAL_REFUND_REQUIRES_REVIEW"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El pago no tiene una revisión de beneficio pendiente por devolución");
        }
        Map<String, Object> beforePayment = paymentSnapshot(payment);

        if (decision == RefundBenefitDecision.REVOKE_BENEFIT) {
            benefitReconciliation.revokeBenefit(payment, "Reembolso del pago #" + payment.getId(), why, actor,
                    AdminAuditAction.ADMIN_RECONCILE_REFUND_BENEFIT);
        }
        payment.setRefundBenefitDecision(decision);
        payment.setRefundBenefitDecidedAt(OffsetDateTime.now(ZoneOffset.UTC));
        payment.setRefundBenefitDecidedByBackoffice(actor);
        payment.setRefundBenefitReason(why);
        payment.setFulfillmentErrorCode(null);
        payment.setFulfillmentErrorMessage(null);
        payment = payments.save(payment);
        audit.record(actor, AdminAuditAction.ADMIN_RECONCILE_REFUND_BENEFIT, AdminAuditEntityType.PAYMENT,
                payment.getId(), beforePayment, paymentSnapshot(payment), why);
        return payment;
    }

    @Transactional(readOnly = true)
    public AutomaticTarget automaticTarget(Long refundId) {
        PaymentRefund r = refunds.findById(requireId(refundId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reembolso no encontrado"));
        PaymentRecord p = r.getPayment();
        return new AutomaticTarget(r.getId(), p.getId(), r.getProvider(), new PaymentGateway.PaymentLookupRequest(
                p.getProviderPaymentId(), p.getProviderSubscriptionId(), p.getProviderCheckoutId(),
                p.getMerchantReference(), p.getIdempotencyKey() + ":refund-auto-reconcile"));
    }

    @Transactional
    public boolean reconcileAutomaticObservation(Long refundId, PaymentGateway.PaymentStatusResult result) {
        PaymentRefund r = refunds.findByIdForUpdate(requireId(refundId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reembolso no encontrado"));
        r.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        r.setLastReconciliationError(null);
        if (r.getStatus() == PaymentRefundStatus.ACCEPTED) { refunds.save(r); return false; }
        if (r.getStatus() != PaymentRefundStatus.REQUESTED && r.getStatus() != PaymentRefundStatus.UNKNOWN) { refunds.save(r); return false; }
        r.setProviderStatus(result.providerStatus());
        BigDecimal providerAmount = result.refundedAmount();
        BigDecimal accepted = refunds.sumAmountByPaymentAndStatus(r.getPayment().getId(), PaymentRefundStatus.ACCEPTED);
        if (accepted == null) accepted = BigDecimal.ZERO;
        BigDecimal expected = accepted.add(r.getRequestedAmount()).setScale(2, RoundingMode.HALF_UP);
        if (providerAmount != null && providerAmount.setScale(2, RoundingMode.HALF_UP).compareTo(expected) >= 0) {
            transactions.applyProviderStatusRefund(r.getPayment().getId(), result,
                    "Devolución confirmada por conciliación preventiva #" + r.getId(), r.getRequestedAmount());
            r.setStatus(PaymentRefundStatus.ACCEPTED);
            r.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
            r.setLastError(null);
            refunds.save(r);
            return true;
        }
        refunds.save(r);
        return false;
    }

    @Transactional
    public void recordAutomaticFailure(Long refundId, RuntimeException error) {
        PaymentRefund r = refunds.findByIdForUpdate(requireId(refundId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reembolso no encontrado"));
        r.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        r.setLastReconciliationError(safeError(error));
        refunds.save(r);
    }

    @Transactional(readOnly = true)
    public List<RefundDTO> list(Long paymentId) {
        return refunds.findByPayment_IdOrderByCreatedAtDescIdDesc(requireId(paymentId)).stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public BigDecimal refundableAmount(PaymentRecord payment) {
        if (payment.getStatus() == PaymentStatus.REFUNDED) return BigDecimal.ZERO.setScale(2);
        BigDecimal provider = providerRefunded(payment);
        BigDecimal accepted = refunds.sumAmountByPaymentAndStatus(payment.getId(), PaymentRefundStatus.ACCEPTED);
        BigDecimal known = provider.max(accepted == null ? BigDecimal.ZERO : accepted);
        BigDecimal remaining = payment.getAmount().subtract(known);
        return (remaining.signum() < 0 ? BigDecimal.ZERO : remaining).setScale(2, RoundingMode.HALF_UP);
    }

    private void requireRefundablePayment(PaymentRecord payment) {
        if (payment.getStatus() != PaymentStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo un pago aprobado puede reembolsarse");
        }
        if (payment.getProviderCheckoutId() == null && payment.getProviderPaymentId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El pago no conserva una referencia externa reembolsable");
        }
    }

    private PreparedRefund prepared(PaymentRefund r, boolean accepted) {
        PaymentRecord p = r.getPayment();
        BigDecimal gatewayAmount = r.getRefundType() == PaymentRefundType.FULL ? null : r.getRequestedAmount();
        PaymentGateway.PaymentLookupRequest lookup = new PaymentGateway.PaymentLookupRequest(
                p.getProviderPaymentId(), p.getProviderSubscriptionId(), p.getProviderCheckoutId(), p.getMerchantReference(), r.getIdempotencyKey());
        return new PreparedRefund(r.getId(), p.getId(), p.getProvider(),
                new PaymentGateway.PaymentRefundRequest(lookup, gatewayAmount, p.getAmount(), p.getCurrency(), r.getIdempotencyKey()), accepted);
    }

    private void assertSame(PaymentRefund existing, Long paymentId, BigDecimal partialAmount) {
        if (!existing.getPayment().getId().equals(requireId(paymentId))) throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave de idempotencia pertenece a otro pago");
        PaymentRefundType expectedType = partialAmount == null ? PaymentRefundType.FULL : PaymentRefundType.PARTIAL;
        BigDecimal expectedAmount = partialAmount == null ? existing.getPayment().getAmount() : money(partialAmount);
        if (existing.getRefundType() != expectedType || existing.getRequestedAmount().compareTo(expectedAmount) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave de idempotencia ya fue utilizada con una devolución diferente");
        }
    }

    private PaymentRecord lockPayment(Long id) {
        return payments.findByIdForUpdate(requireId(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no encontrado"));
    }
    private BigDecimal providerRefunded(PaymentRecord p) { return p.getProviderRefundedAmount() == null ? BigDecimal.ZERO : p.getProviderRefundedAmount(); }
    private BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private Long requireId(Long id) { if (id == null || id <= 0) throw bad("Identificador inválido"); return id; }
    private String cleanKey(String v) { if (v == null || v.isBlank()) throw bad("La clave de idempotencia es obligatoria"); String x=v.trim(); if(x.length()>120) throw bad("La clave de idempotencia es demasiado larga"); return x; }
    private ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private boolean isUncertain(RuntimeException error) {
        if (error instanceof ResponseStatusException r) {
            int code = r.getStatusCode().value();
            return code >= 500 || code == 408 || code == 429;
        }
        return true;
    }
    private String safeError(RuntimeException error) {
        String value = error instanceof ResponseStatusException r ? r.getReason() : error.getMessage();
        if (value == null || value.isBlank()) value = "No se pudo confirmar el resultado del reembolso";
        return value.length() <= 600 ? value : value.substring(0, 600);
    }

    private Map<String,Object> snapshot(PaymentRefund r) { Map<String,Object> m=new LinkedHashMap<>(); m.put("paymentId",r.getPayment().getId());m.put("provider",r.getProvider());m.put("type",r.getRefundType());m.put("amount",r.getRequestedAmount());m.put("currency",r.getCurrency());m.put("status",r.getStatus());m.put("attemptCount",r.getAttemptCount());return m; }
    private Map<String,Object> paymentSnapshot(PaymentRecord p) { Map<String,Object> m=new LinkedHashMap<>();m.put("status",p.getStatus());m.put("providerRefundedAmount",p.getProviderRefundedAmount());m.put("refundBenefitDecision",p.getRefundBenefitDecision());m.put("fulfillmentErrorCode",p.getFulfillmentErrorCode());m.put("subscriptionId",p.getFulfilledSubscription()==null?null:p.getFulfilledSubscription().getId());m.put("promotionId",p.getFulfilledPromotion()==null?null:p.getFulfilledPromotion().getId());return m; }

    private RefundDTO dto(PaymentRefund r) { BackofficeUser a=r.getRequestedByBackoffice(); return new RefundDTO(r.getId(),r.getRefundType(),r.getRequestedAmount(),r.getCurrency(),r.getStatus(),r.getProviderStatus(),r.getReason(),r.getAttemptCount(),r.getLastError(),a==null?null:a.getId(),a==null?null:a.getDisplayName(),r.getAcceptedAt(),r.getCreatedAt(),r.getUpdatedAt()); }

    public record PreparedRefund(Long refundId, Long paymentId, PaymentProvider provider, PaymentGateway.PaymentRefundRequest gatewayRequest, boolean alreadyAccepted) {}
    public record AutomaticTarget(Long refundId, Long paymentId, PaymentProvider provider, PaymentGateway.PaymentLookupRequest lookup) {}
    public record RefundDTO(Long id, PaymentRefundType refundType, BigDecimal requestedAmount, String currency,
                            PaymentRefundStatus status, String providerStatus, String reason, int attemptCount,
                            String lastError, Long requestedById, String requestedByName,
                            OffsetDateTime acceptedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
