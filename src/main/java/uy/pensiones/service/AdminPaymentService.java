package uy.pensiones.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.*;
import uy.pensiones.repo.PaymentRepository;
import uy.pensiones.repo.PaymentStatusHistoryRepository;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Service
public class AdminPaymentService {
    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository history;
    private final PaymentTransactionService tx;
    private final PaymentGatewayRegistry registry;
    private final MockPaymentGateway mockGateway;
    private final PaymentRuntimeConfigurationService paymentRuntime;
    private final PaymentRefundService refundService;
    private final AdminAuditService audit;

    public AdminPaymentService(PaymentRepository payments, PaymentStatusHistoryRepository history,
                               PaymentTransactionService tx, PaymentGatewayRegistry registry,
                               MockPaymentGateway mockGateway, PaymentRuntimeConfigurationService paymentRuntime,
                               PaymentRefundService refundService, AdminAuditService audit) {
        this.payments=payments; this.history=history; this.tx=tx; this.registry=registry;
        this.mockGateway=mockGateway; this.paymentRuntime=paymentRuntime; this.refundService=refundService; this.audit=audit;
    }

    public PaymentConfigDTO config() {
        List<ProviderDTO> providers = Arrays.stream(PaymentProvider.values()).map(p -> new ProviderDTO(
                p, p.configKey(), registry.isConfiguredEnabled(p), registry.isImplemented(p), p==PaymentProvider.MOCK,
                paymentRuntime.provider(p).getMode(), registry.isAvailableForNewPayments(p, null)
        )).toList();
        return new PaymentConfigDTO(paymentRuntime.infrastructureAllowed(), paymentRuntime.databasePaymentsEnabled(), paymentRuntime.paymentsEnabled(), providers);
    }

    public Page<PaymentDTO> list(String q, PaymentProvider provider, PaymentPurpose purpose, PaymentStatus status, int page, int size) {
        String term=q==null?"":q.trim(); if(term.length()>180) throw bad("La búsqueda es demasiado larga");
        return payments.adminList(term, provider, purpose, status, PageRequest.of(Math.max(0,page), Math.min(100,Math.max(1,size)))).map(this::dto);
    }

    public PaymentSummaryDTO summary() {
        var r=payments.summary();
        return new PaymentSummaryDTO(r==null?0:n(r.getTotal()), r==null?0:n(r.getPending()), r==null?0:n(r.getApproved()),
                r==null?0:n(r.getRejected()), r==null?0:n(r.getApprovedUnfulfilled()), r==null||r.getApprovedAmount()==null?BigDecimal.ZERO:r.getApprovedAmount());
    }

    public PaymentDetailDTO detail(Long id) {
        PaymentRecord p=tx.detailEntity(id);
        List<HistoryDTO> h=history.findByPayment_IdOrderByCreatedAtAscIdAsc(p.getId()).stream()
                .map(x->new HistoryDTO(x.getId(),x.getFromStatus(),x.getToStatus(),x.getProviderStatus(),x.getEventSource(),x.getNote(),x.getCreatedAt())).toList();
        return new PaymentDetailDTO(dto(p), h, refundService.list(p.getId()), refundService.refundableAmount(p));
    }

    public PaymentDetailDTO createMock(PaymentTransactionService.CreateInput input, BackofficeUser actor) {
        return createTestCheckout(PaymentProvider.MOCK, input, actor);
    }

    public PaymentDetailDTO createTestCheckout(PaymentProvider provider, PaymentTransactionService.CreateInput input, BackofficeUser actor) {
        if (provider == null) throw bad("Proveedor obligatorio");
        if (provider != PaymentProvider.MOCK && paymentRuntime.provider(provider).getMode() == PaymentProviderMode.LIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El Backoffice no crea checkouts de prueba contra un proveedor en modo LIVE");
        }
        var prepared=tx.prepare(provider,input,actor);
        PaymentRecord current=tx.detailEntity(prepared.paymentId());
        if(current.getProviderCheckoutId()==null && current.getProviderPaymentId()==null){
            var gateway=registry.requireEnabled(provider, input.purpose());
            var result=gateway.createPayment(prepared.gatewayRequest());
            tx.attachProviderResult(prepared.paymentId(),result);
        }
        return detail(prepared.paymentId());
    }

    public PaymentDetailDTO createCertificationLiveCheckout(PaymentTransactionService.CreateInput input, String reason, BackofficeUser actor) {
        PaymentProvider provider = PaymentProvider.MERCADO_PAGO;
        if (paymentRuntime.provider(provider).getMode() != PaymentProviderMode.LIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El checkout controlado de certificación solo está disponible con Mercado Pago en LIVE");
        }
        var prepared = tx.prepareCertificationLive(provider, input, actor, reason);
        PaymentRecord current = tx.detailEntity(prepared.paymentId());
        if (current.getProviderCheckoutId() == null && current.getProviderPaymentId() == null) {
            var gateway = registry.requireImplemented(provider);
            var result = gateway.createPayment(prepared.gatewayRequest());
            tx.attachProviderResult(prepared.paymentId(), result);
        }
        return detail(prepared.paymentId());
    }

    public PaymentDetailDTO simulate(Long id, PaymentStatus status, BackofficeUser actor) {
        paymentRuntime.requirePaymentsEnabled();
        PaymentRecord p=tx.detailEntity(id);
        if(p.getProvider()!=PaymentProvider.MOCK) throw new ResponseStatusException(HttpStatus.CONFLICT,"Solo los pagos MOCK admiten simulación");
        registry.requireEnabled(PaymentProvider.MOCK);
        var result=mockGateway.simulate(p.getProviderPaymentId(),status);
        tx.applyProviderStatusAdmin(id,result,PaymentEventSource.MOCK_SIMULATION,actor,AdminAuditAction.ADMIN_SIMULATE_PAYMENT_STATUS,"Simulación controlada del proveedor MOCK");
        PaymentDetailDTO out=detail(id);
        return out;
    }

    public PaymentDetailDTO refresh(Long id, BackofficeUser actor) {
        PaymentRecord p=tx.detailEntity(id);
        var gateway=registry.requireImplemented(p.getProvider());
        var result=gateway.getStatus(lookup(p, "refresh"));
        tx.applyProviderStatusAdmin(id,result,PaymentEventSource.PROVIDER_SYNC,actor,AdminAuditAction.ADMIN_RECONCILE_PAYMENT,"Conciliación manual contra el proveedor");
        PaymentDetailDTO out=detail(id);
        return out;
    }

    public PaymentDetailDTO cancel(Long id, String reason, BackofficeUser actor) {
        String why=audit.requireReason(reason);
        PaymentRecord p=tx.detailEntity(id);
        if(p.getStatus()!=PaymentStatus.CREATED && p.getStatus()!=PaymentStatus.PENDING) throw new ResponseStatusException(HttpStatus.CONFLICT,"Solo un pago pendiente puede cancelarse");
        var gateway=registry.requireImplemented(p.getProvider());
        var result=gateway.cancel(lookup(p, "cancel"));
        tx.applyProviderStatusAdmin(id,result,PaymentEventSource.CANCEL,actor,AdminAuditAction.ADMIN_CANCEL_PAYMENT,why);
        PaymentDetailDTO out=detail(id);
        return out;
    }

    public PaymentDetailDTO refund(Long id, BigDecimal amount, String idempotencyKey, String reason, BackofficeUser actor) {
        var prepared = refundService.prepare(id, amount, idempotencyKey, reason, actor);
        if (!prepared.alreadyAccepted()) executeRefund(prepared);
        return detail(id);
    }

    public PaymentDetailDTO retryRefund(Long id, Long refundId, BackofficeUser actor) {
        var prepared = refundService.prepareRetry(id, refundId, actor);
        if (!prepared.alreadyAccepted()) executeRefund(prepared);
        return detail(id);
    }

    public PaymentDetailDTO reconcileRefundBenefit(Long id, RefundBenefitDecision decision, String reason, BackofficeUser actor) {
        refundService.reconcileBenefit(id, decision, reason, actor);
        return detail(id);
    }

    private void executeRefund(PaymentRefundService.PreparedRefund prepared) {
        try {
            PaymentGateway gateway = registry.requireImplemented(prepared.provider());
            PaymentGateway.PaymentStatusResult result = gateway.refund(prepared.gatewayRequest());
            refundService.accept(prepared.refundId(), result);
        } catch (RuntimeException e) {
            refundService.fail(prepared.refundId(), e);
            throw e;
        }
    }

    private PaymentDTO dto(PaymentRecord p){
        User u=p.getUser(); PlanVersion pv=p.getPlanVersion(); PromotionProductVersion ppv=p.getPromotionProductVersion();
        PromotionProduct product=ppv==null?null:ppv.getProduct(); Pension pension=p.getPension(); StudyCenterCatalog sc=p.getStudyCenter();
        return new PaymentDTO(p.getId(),p.getMerchantReference(),p.getIdempotencyKey(),p.getPurpose(),p.getProvider(),p.getProviderMode(),p.getProviderPaymentId(),p.getProviderSubscriptionId(),p.getProviderCheckoutId(),p.getCheckoutUrl(),
                p.getStatus(),p.getProviderStatus(),p.getAmount(),p.getCurrency(),p.getProviderRefundedAmount(),u.getId(),u.getName(),u.getEmail(),
                pv==null?null:pv.getId(),pv==null?null:pv.getPlan().getCode(),pv==null?null:pv.getVersion(),p.getSubscriptionPeriodMonths(),
                pension==null?null:pension.getId(),pension==null?null:pension.getName(),ppv==null?null:ppv.getId(),product==null?null:product.getCode(),product==null?null:product.getTargetType(),
                sc==null?null:sc.getId(),sc==null?null:sc.getName(),p.getFulfilledSubscription()==null?null:p.getFulfilledSubscription().getId(),p.getFulfilledPromotion()==null?null:p.getFulfilledPromotion().getId(),
                p.getFulfilledAt(),p.getFulfillmentErrorCode(),p.getFulfillmentErrorMessage(),p.getRefundBenefitDecision(),p.getRefundBenefitReason(),p.getRefundBenefitDecidedAt(),
                p.getApprovedAt(),p.getRejectedAt(),p.getCancelledAt(),p.getRefundedAt(),p.getExpiredAt(),p.getLastProviderSyncAt(),p.getCreatedAt(),p.getUpdatedAt());
    }
    private PaymentGateway.PaymentLookupRequest lookup(PaymentRecord p, String operation) {
        return new PaymentGateway.PaymentLookupRequest(p.getProviderPaymentId(), p.getProviderSubscriptionId(), p.getProviderCheckoutId(),
                p.getMerchantReference(), p.getIdempotencyKey() + ":" + operation);
    }
    private long n(Long v){return v==null?0:Math.max(0,v);} private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}

    public record PaymentConfigDTO(boolean infrastructureAllowed,boolean databasePaymentsEnabled,boolean paymentsEnabled,List<ProviderDTO> providers){}
    public record ProviderDTO(PaymentProvider provider,String configKey,boolean enabled,boolean implemented,boolean mock,PaymentProviderMode mode,boolean availableForNewPayments){}
    public record PaymentSummaryDTO(long total,long pending,long approved,long rejected,long approvedUnfulfilled,BigDecimal approvedAmount){}
    public record HistoryDTO(Long id,PaymentStatus fromStatus,PaymentStatus toStatus,String providerStatus,PaymentEventSource eventSource,String note,java.time.OffsetDateTime createdAt){}
    public record PaymentDetailDTO(PaymentDTO payment,List<HistoryDTO> history,List<PaymentRefundService.RefundDTO> refunds,BigDecimal refundableAmount){}
    public record PaymentDTO(Long id,String merchantReference,String idempotencyKey,PaymentPurpose purpose,PaymentProvider provider,PaymentProviderMode providerMode,String providerPaymentId,String providerSubscriptionId,String providerCheckoutId,String checkoutUrl,
            PaymentStatus status,String providerStatus,BigDecimal amount,String currency,BigDecimal providerRefundedAmount,Long userId,String userName,String userEmail,
            Long planVersionId,String planCode,Integer planVersion,Integer subscriptionPeriodMonths,
            Long pensionId,String pensionName,Long promotionProductVersionId,String promotionProductCode,PromotionTargetType promotionTargetType,
            Long studyCenterId,String studyCenterName,Long fulfilledSubscriptionId,Long fulfilledPromotionId,
            java.time.OffsetDateTime fulfilledAt,String fulfillmentErrorCode,String fulfillmentErrorMessage,RefundBenefitDecision refundBenefitDecision,String refundBenefitReason,java.time.OffsetDateTime refundBenefitDecidedAt,
            java.time.OffsetDateTime approvedAt,java.time.OffsetDateTime rejectedAt,java.time.OffsetDateTime cancelledAt,java.time.OffsetDateTime refundedAt,java.time.OffsetDateTime expiredAt,
            java.time.OffsetDateTime lastProviderSyncAt,java.time.OffsetDateTime createdAt,java.time.OffsetDateTime updatedAt){}
}
