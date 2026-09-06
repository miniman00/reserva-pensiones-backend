package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.PensionPromotionStatus;
import uy.pensiones.enums.SubscriptionStatus;
import uy.pensiones.model.*;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.PensionPromotionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Único punto para revocar un beneficio ya concedido como consecuencia de una
 * incidencia financiera (reembolso, contracargo, etc.). La decisión de revocar
 * siempre se toma en el servicio que conoce el contexto; aquí solo se ejecuta
 * de forma consistente y auditada.
 */
@Service
public class PaymentBenefitReconciliationService {
    private final OwnerSubscriptionRepository subscriptions;
    private final PensionPromotionRepository promotions;
    private final AdminAuditService audit;

    public PaymentBenefitReconciliationService(OwnerSubscriptionRepository subscriptions,
                                               PensionPromotionRepository promotions,
                                               AdminAuditService audit) {
        this.subscriptions = subscriptions;
        this.promotions = promotions;
        this.audit = audit;
    }

    @Transactional
    public void revokeBenefit(PaymentRecord payment, String context, String reason,
                              BackofficeUser actor, AdminAuditAction action) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OwnerSubscription subscription = payment.getFulfilledSubscription();
        if (subscription != null) {
            Map<String, Object> before = subscriptionSnapshot(subscription);
            if (subscription.getStatus() != SubscriptionStatus.CANCELLED) {
                subscription.setStatus(SubscriptionStatus.CANCELLED);
                subscription.setCancelledAt(now);
                subscription.setCancellationReason(context + ": " + reason);
                subscriptions.save(subscription);
            }
            audit.record(actor, action, AdminAuditEntityType.SUBSCRIPTION,
                    subscription.getId(), before, subscriptionSnapshot(subscription), reason);
        }

        PensionPromotion promotion = payment.getFulfilledPromotion();
        if (promotion != null) {
            Map<String, Object> before = promotionSnapshot(promotion);
            if (promotion.getStatus() != PensionPromotionStatus.CANCELLED) {
                promotion.setStatus(PensionPromotionStatus.CANCELLED);
                promotion.setCancelledAt(now);
                promotion.setCancellationReason(context + ": " + reason);
                promotions.save(promotion);
            }
            audit.record(actor, action, AdminAuditEntityType.PROMOTION,
                    promotion.getId(), before, promotionSnapshot(promotion), reason);
        }
    }

    private Map<String,Object> subscriptionSnapshot(OwnerSubscription s) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("status",s.getStatus());m.put("expiresAt",s.getExpiresAt());m.put("cancelledAt",s.getCancelledAt());
        return m;
    }

    private Map<String,Object> promotionSnapshot(PensionPromotion p) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("status",p.getStatus());m.put("startsAt",p.getStartsAt());m.put("endsAt",p.getEndsAt());m.put("cancelledAt",p.getCancelledAt());
        return m;
    }
}
