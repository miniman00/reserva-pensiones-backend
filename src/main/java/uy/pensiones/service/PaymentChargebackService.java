package uy.pensiones.service;

import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.repo.PaymentChargebackRepository;
import uy.pensiones.repo.PaymentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentChargebackService {
    private final PaymentChargebackRepository chargebacks;
    private final PaymentRepository payments;
    private final PaymentBenefitReconciliationService benefits;
    private final AdminAuditService audit;

    public PaymentChargebackService(PaymentChargebackRepository chargebacks, PaymentRepository payments,
                                    PaymentBenefitReconciliationService benefits, AdminAuditService audit) {
        this.chargebacks = chargebacks; this.payments = payments; this.benefits = benefits; this.audit = audit;
    }

    @Transactional
    public PaymentChargeback sync(PaymentProvider provider, PaymentGateway.PaymentDisputeResult result) {
        if (provider == null || result == null || result.providerDisputeId() == null || result.providerDisputeId().isBlank()) {
            throw bad("Contracargo inválido");
        }
        PaymentChargeback chargeback = chargebacks.findByProviderAndProviderChargebackIdForUpdate(provider, result.providerDisputeId()).orElse(null);
        if (chargeback == null) {
            chargeback = PaymentChargeback.builder().provider(provider).providerChargebackId(clean(result.providerDisputeId(),190)).build();
        }

        PaymentRecord linked = findPayment(provider, result.providerPaymentIds());
        if (linked != null) validateIdentity(linked, result);
        if (chargeback.getPayment() == null && linked != null) chargeback.setPayment(linked);
        if (chargeback.getPayment() != null) {
            validateIdentity(chargeback.getPayment(), result);
            if (linked != null && !chargeback.getPayment().getId().equals(linked.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El contracargo cambió de pago asociado en el proveedor");
            }
            String currentProviderPaymentId = chargeback.getPayment().getProviderPaymentId();
            if (currentProviderPaymentId != null && result.providerPaymentIds() != null && !result.providerPaymentIds().isEmpty()
                    && result.providerPaymentIds().stream().noneMatch(currentProviderPaymentId::equals)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El payment ID del contracargo no coincide con el pago interno asociado");
            }
        }

        String paymentId = first(result.providerPaymentIds());
        if (paymentId != null) chargeback.setProviderPaymentId(clean(paymentId,190));
        chargeback.setAmount(moneyOrNull(result.amount()));
        chargeback.setCurrency(upper(result.currency(),3));
        chargeback.setReason(clean(result.reason(),160));
        chargeback.setCoverageEligible(result.coverageEligible());
        chargeback.setCoverageApplied(result.coverageApplied());
        chargeback.setDocumentationRequired(result.documentationRequired());
        chargeback.setDocumentationStatus(clean(result.documentationStatus(),80));
        chargeback.setDocumentationDeadline(result.documentationDeadline());
        chargeback.setLiveMode(result.liveMode());
        chargeback.setProviderCreatedAt(result.providerCreatedAt());
        chargeback.setProviderUpdatedAt(result.providerUpdatedAt());
        chargeback.setStatus(status(result.coverageApplied()));
        chargeback.setLastSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        chargeback.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        chargeback.setLastReconciliationError(null);
        return chargebacks.save(chargeback);
    }

    @Transactional(readOnly = true)
    public Page<ChargebackDTO> list(String q, PaymentChargebackStatus status, Boolean documentationRequired,
                                    Boolean reviewRequired, int page, int size) {
        int safePage=Math.max(0,page), safeSize=Math.min(100,Math.max(1,size));
        return chargebacks.adminList(q==null?"":q.trim(),status,documentationRequired,reviewRequired,PaymentChargebackStatus.LOST,
                PageRequest.of(safePage,safeSize,Sort.by(Sort.Direction.DESC,"createdAt"))).map(this::dto);
    }

    @Transactional(readOnly = true)
    public ChargebackDTO detail(Long id) { return dto(requireDetailed(id)); }

    @Transactional(readOnly = true)
    public SummaryDTO summary() {
        var s=chargebacks.summary();
        return new SummaryDTO(n(s.getTotal()),n(s.getOpen()),n(s.getWon()),n(s.getLost()),n(s.getDocumentationRequired()),n(s.getUnmatched()),n(s.getBenefitReviewRequired()));
    }

    @Transactional(readOnly = true)
    public RefreshTarget refreshTarget(Long id) {
        PaymentChargeback c=requireDetailed(id);
        return new RefreshTarget(c.getId(),c.getProvider(),c.getProviderChargebackId(),c.getProviderPaymentId());
    }

    @Transactional
    public void recordAutomaticFailure(Long id, RuntimeException error) {
        PaymentChargeback c=chargebacks.findByIdForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Contracargo no encontrado"));
        c.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        String value = error instanceof ResponseStatusException r ? r.getReason() : error.getMessage();
        if (value == null || value.isBlank()) value = "No se pudo consultar el contracargo en el proveedor";
        c.setLastReconciliationError(value.length() <= 600 ? value : value.substring(0,600));
        chargebacks.save(c);
    }

    @Transactional
    public ChargebackDTO reconcileBenefit(Long id, ChargebackBenefitDecision decision, String reason, BackofficeUser actor) {
        if (decision == null) throw bad("La decisión sobre el beneficio es obligatoria");
        String why=audit.requireReason(reason);
        PaymentChargeback c=chargebacks.findByIdForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Contracargo no encontrado"));
        if (c.getStatus()!=PaymentChargebackStatus.LOST) throw new ResponseStatusException(HttpStatus.CONFLICT,"Solo un contracargo resuelto contra la empresa requiere esta decisión");
        if (c.getBenefitDecision()!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"El contracargo ya tiene una decisión sobre el beneficio");
        PaymentRecord payment=c.getPayment();
        if (payment==null || payment.getFulfilledAt()==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"El contracargo no tiene un beneficio aplicado que conciliar");
        Map<String,Object> before=snapshot(c);
        if (decision==ChargebackBenefitDecision.REVOKE_BENEFIT) {
            benefits.revokeBenefit(payment,"Contracargo #"+c.getProviderChargebackId(),why,actor,AdminAuditAction.ADMIN_RECONCILE_CHARGEBACK_BENEFIT);
        }
        c.setBenefitDecision(decision);
        c.setBenefitDecidedAt(OffsetDateTime.now(ZoneOffset.UTC));
        c.setBenefitDecidedByBackoffice(actor);
        c.setBenefitReason(why);
        c=chargebacks.save(c);
        audit.record(actor,AdminAuditAction.ADMIN_RECONCILE_CHARGEBACK_BENEFIT,AdminAuditEntityType.PAYMENT_CHARGEBACK,c.getId(),before,snapshot(c),why);
        return dto(c);
    }

    private PaymentRecord findPayment(PaymentProvider provider, List<String> ids) {
        if (ids==null) return null;
        for (String id:ids) {
            if (id==null || id.isBlank()) continue;
            PaymentRecord p=payments.findByProviderAndProviderPaymentId(provider,id.trim()).orElse(null);
            if (p!=null) return p;
        }
        return null;
    }

    private void validateIdentity(PaymentRecord p, PaymentGateway.PaymentDisputeResult r) {
        if (r.currency()!=null && p.getCurrency()!=null && !p.getCurrency().equalsIgnoreCase(r.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"La moneda del contracargo no coincide con el pago interno");
        }
        if (r.amount()!=null && (r.amount().signum()<0 || r.amount().compareTo(p.getAmount())>0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El importe del contracargo no es compatible con el pago interno");
        }
    }

    private PaymentChargeback requireDetailed(Long id) {
        if (id==null || id<=0) throw bad("Identificador inválido");
        return chargebacks.findDetailedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Contracargo no encontrado"));
    }

    private ChargebackDTO dto(PaymentChargeback c) {
        PaymentRecord p=c.getPayment(); User u=p==null?null:p.getUser(); BackofficeUser actor=c.getBenefitDecidedByBackoffice();
        BackofficeUser docActor=c.getDocumentationSubmittedByBackoffice();
        boolean review=c.getStatus()==PaymentChargebackStatus.LOST && p!=null && p.getFulfilledAt()!=null && c.getBenefitDecision()==null;
        String uploadBlock=documentationUploadBlockReason(c);
        return new ChargebackDTO(c.getId(),c.getProvider(),c.getProviderChargebackId(),c.getProviderPaymentId(),c.getStatus(),c.getAmount(),c.getCurrency(),c.getReason(),
                c.getCoverageEligible(),c.getCoverageApplied(),c.getDocumentationRequired(),c.getDocumentationStatus(),c.getDocumentationDeadline(),
                c.getDocumentationSubmissionState(),c.getDocumentationSubmittedAt(),c.getDocumentationFileCount(),c.getDocumentationTotalBytes(),
                docActor==null?null:docActor.getId(),docActor==null?null:docActor.getDisplayName(),uploadBlock==null,uploadBlock,c.getLiveMode(),
                c.getProviderCreatedAt(),c.getProviderUpdatedAt(),c.getLastSyncedAt(),p==null?null:p.getId(),p==null?null:p.getMerchantReference(),p==null?null:p.getPurpose(),
                u==null?null:u.getId(),u==null?null:u.getName(),u==null?null:u.getEmail(),p==null?null:p.getFulfilledSubscription()==null?null:p.getFulfilledSubscription().getId(),
                p==null?null:p.getFulfilledPromotion()==null?null:p.getFulfilledPromotion().getId(),review,c.getBenefitDecision(),c.getBenefitReason(),c.getBenefitDecidedAt(),
                actor==null?null:actor.getId(),actor==null?null:actor.getDisplayName(),c.getCreatedAt(),c.getUpdatedAt());
    }


    private String documentationUploadBlockReason(PaymentChargeback c) {
        if (c.getStatus()!=PaymentChargebackStatus.OPEN) return "El contracargo ya no está abierto";
        if (!Boolean.TRUE.equals(c.getCoverageEligible())) return "El caso no es elegible para cobertura";
        if (!Boolean.TRUE.equals(c.getDocumentationRequired())) return "Mercado Pago no requiere documentación";
        if (c.getDocumentationDeadline()==null || !c.getDocumentationDeadline().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) return "El plazo para presentar evidencia venció";
        if (c.getDocumentationSubmissionState()==ChargebackDocumentationSubmissionState.IN_PROGRESS) return "Hay una carga de evidencia en curso";
        if (c.getDocumentationSubmissionState()==ChargebackDocumentationSubmissionState.UNKNOWN) return "El resultado de la carga anterior es incierto; sincronizá antes de reintentar";
        if (c.getDocumentationSubmissionState()==ChargebackDocumentationSubmissionState.SUBMITTED) return "La evidencia ya fue enviada";
        if (c.getDocumentationStatus()==null || !"not_supplied".equalsIgnoreCase(c.getDocumentationStatus().trim())) return "El estado de documentación del proveedor no permite una nueva carga";
        return null;
    }

    private Map<String,Object> snapshot(PaymentChargeback c){Map<String,Object>m=new LinkedHashMap<>();m.put("providerChargebackId",c.getProviderChargebackId());m.put("paymentId",c.getPayment()==null?null:c.getPayment().getId());m.put("status",c.getStatus());m.put("coverageApplied",c.getCoverageApplied());m.put("benefitDecision",c.getBenefitDecision());return m;}
    private PaymentChargebackStatus status(Boolean coverageApplied){return coverageApplied==null?PaymentChargebackStatus.OPEN:(coverageApplied?PaymentChargebackStatus.WON:PaymentChargebackStatus.LOST);}
    private String first(List<String> values){return values==null?null:values.stream().filter(v->v!=null&&!v.isBlank()).findFirst().orElse(null);}
    private BigDecimal moneyOrNull(BigDecimal v){return v==null?null:v.setScale(2,RoundingMode.HALF_UP);}
    private String clean(String v,int max){if(v==null)return null;String x=v.trim();return x.length()<=max?x:x.substring(0,max);}
    private String upper(String v,int max){String x=clean(v,max);return x==null?null:x.toUpperCase(java.util.Locale.ROOT);}
    private long n(Long v){return v==null?0:Math.max(0,v);}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}

    public record RefreshTarget(Long id,PaymentProvider provider,String providerChargebackId,String providerPaymentId){}
    public record SummaryDTO(long total,long open,long won,long lost,long documentationRequired,long unmatched,long benefitReviewRequired){}
    public record ChargebackDTO(Long id,PaymentProvider provider,String providerChargebackId,String providerPaymentId,PaymentChargebackStatus status,
            BigDecimal amount,String currency,String reason,Boolean coverageEligible,Boolean coverageApplied,Boolean documentationRequired,String documentationStatus,
            OffsetDateTime documentationDeadline,ChargebackDocumentationSubmissionState documentationSubmissionState,OffsetDateTime documentationSubmittedAt,
            Integer documentationFileCount,Long documentationTotalBytes,Long documentationSubmittedById,String documentationSubmittedByName,
            boolean documentationUploadAllowed,String documentationUploadBlockReason,Boolean liveMode,OffsetDateTime providerCreatedAt,OffsetDateTime providerUpdatedAt,OffsetDateTime lastSyncedAt,
            Long paymentId,String merchantReference,PaymentPurpose purpose,Long userId,String userName,String userEmail,Long fulfilledSubscriptionId,Long fulfilledPromotionId,
            boolean benefitReviewRequired,ChargebackBenefitDecision benefitDecision,String benefitReason,OffsetDateTime benefitDecidedAt,Long benefitDecidedById,
            String benefitDecidedByName,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
}
