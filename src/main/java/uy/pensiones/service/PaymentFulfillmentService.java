package uy.pensiones.service;

import org.springframework.stereotype.Service;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.repo.AdminSubscriptionUserRepository;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class PaymentFulfillmentService {

    private final OwnerSubscriptionRepository subscriptions;
    private final AdminSubscriptionUserRepository users;
    private final PensionPromotionRepository promotions;
    private final PensionRepository pensions;

    public PaymentFulfillmentService(OwnerSubscriptionRepository subscriptions,
                                     AdminSubscriptionUserRepository users,
                                     PensionPromotionRepository promotions,
                                     PensionRepository pensions) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.promotions = promotions;
        this.pensions = pensions;
    }

    /**
     * Debe ejecutarse dentro de la misma transacción que bloquea PaymentRecord.
     * Los conflictos de negocio se devuelven como resultado para conservar el pago APPROVED
     * y dejarlo visible en conciliación, en lugar de perder el estado del proveedor.
     */
    public FulfillmentResult fulfill(PaymentRecord payment, OffsetDateTime now) {
        if (payment.getFulfilledAt() != null) {
            return FulfillmentResult.success(payment.getFulfilledSubscription(), payment.getFulfilledPromotion());
        }
        if (payment.getPurpose() == PaymentPurpose.SUBSCRIPTION) {
            return fulfillSubscription(payment, now);
        }
        if (payment.getPurpose() == PaymentPurpose.PROMOTION) {
            return fulfillPromotion(payment, now);
        }
        return FulfillmentResult.failure("UNSUPPORTED_PURPOSE", "El propósito del pago no tiene fulfillment configurado");
    }

    private FulfillmentResult fulfillSubscription(PaymentRecord payment, OffsetDateTime now) {
        if (payment.getPlanVersion() == null || payment.getSubscriptionPeriodMonths() == null) {
            return FulfillmentResult.failure("SUBSCRIPTION_CONFIGURATION_INVALID",
                    "El pago no conserva una versión de plan y período válidos");
        }

        User user = users.findByIdForUpdate(payment.getUser().getId()).orElse(null);
        if (!isMarketplaceUser(user)) {
            return FulfillmentResult.failure("MARKETPLACE_USER_NOT_FOUND",
                    "El usuario asociado al pago ya no pertenece al marketplace");
        }

        expireDueForUser(user.getId(), now);
        List<OwnerSubscription> active = subscriptions.findEffectiveActiveDetailed(
                user.getId(), now, SubscriptionStatus.ACTIVE);
        if (!active.isEmpty()) {
            return FulfillmentResult.failure("ACTIVE_SUBSCRIPTION_CONFLICT",
                    "La cuenta ya tiene otra suscripción efectiva; se requiere conciliación manual antes de aplicar el pago");
        }

        OffsetDateTime startsAt = payment.getApprovedAt() == null ? now : payment.getApprovedAt();
        OwnerSubscription subscription = subscriptions.save(OwnerSubscription.builder()
                .user(user)
                .planVersion(payment.getPlanVersion())
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(startsAt)
                .expiresAt(startsAt.plusMonths(payment.getSubscriptionPeriodMonths()))
                .provider(payment.getProvider().name())
                .providerSubscriptionId(payment.getProviderSubscriptionId())
                .source(SubscriptionSource.PAYMENT)
                .build());
        return FulfillmentResult.success(subscription, null);
    }

    private FulfillmentResult fulfillPromotion(PaymentRecord payment, OffsetDateTime now) {
        if (payment.getPension() == null || payment.getPromotionProductVersion() == null) {
            return FulfillmentResult.failure("PROMOTION_CONFIGURATION_INVALID",
                    "El pago no conserva pensión y versión de destacado válidas");
        }

        Pension pension = pensions.findByIdForEntitlementUpdate(payment.getPension().getId()).orElse(null);
        if (pension == null) {
            return FulfillmentResult.failure("PENSION_NOT_FOUND", "La pensión asociada al pago ya no existe");
        }
        User owner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        if (owner == null || !owner.getId().equals(payment.getUser().getId())) {
            return FulfillmentResult.failure("PENSION_OWNER_CHANGED",
                    "La pensión cambió de responsable después de crear el pago");
        }
        if (pension.getStatus() != PensionStatus.PUBLISHED || Boolean.TRUE.equals(pension.getModerationBlocked())
                || owner.isSuspended()) {
            return FulfillmentResult.failure("PENSION_NOT_ELIGIBLE",
                    "La pensión ya no está publicada y operativa; el pago requiere conciliación antes de otorgar el destacado");
        }

        PromotionProductVersion version = payment.getPromotionProductVersion();
        PromotionProduct product = version.getProduct();
        if (product == null || product.getDurationDays() <= 0) {
            return FulfillmentResult.failure("PROMOTION_PRODUCT_INVALID", "El producto de destacado es inválido");
        }
        StudyCenterCatalog center = payment.getStudyCenter();
        if (product.getTargetType() == PromotionTargetType.STUDY_CENTER && center == null) {
            return FulfillmentResult.failure("STUDY_CENTER_REQUIRED", "El destacado requiere un centro de estudio");
        }
        if (product.getTargetType() != PromotionTargetType.STUDY_CENTER && center != null) {
            return FulfillmentResult.failure("STUDY_CENTER_NOT_ALLOWED", "El pago contiene un centro para un target que no lo utiliza");
        }

        OffsetDateTime startsAt = payment.getApprovedAt() == null ? now : payment.getApprovedAt();
        OffsetDateTime endsAt = startsAt.plusDays(product.getDurationDays());
        Long centerId = center == null ? null : center.getId();
        long overlapping = promotions.countOverlapping(
                pension.getId(), product.getTargetType().name(), centerId, startsAt, endsAt);
        if (overlapping > 0) {
            return FulfillmentResult.failure("PROMOTION_OVERLAP",
                    "Ya existe una promoción que se solapa con el target comprado; se requiere conciliación manual");
        }

        PensionPromotion promotion = promotions.save(PensionPromotion.builder()
                .pension(pension)
                .productVersion(version)
                .type(PensionPromotionType.FEATURED)
                .targetType(product.getTargetType())
                .studyCenter(center)
                .targetValue(null)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status(PensionPromotionStatus.ACTIVE)
                .price(payment.getAmount())
                .currency(payment.getCurrency())
                .payment(payment)
                .source(PensionPromotionSource.PAYMENT)
                .createdByBackoffice(null)
                .build());
        return FulfillmentResult.success(null, promotion);
    }

    private void expireDueForUser(Long userId, OffsetDateTime now) {
        List<OwnerSubscription> active = subscriptions.findByUserIdAndStatusOrderByExpiresAtDesc(
                userId, SubscriptionStatus.ACTIVE);
        for (OwnerSubscription subscription : active) {
            if (!subscription.getExpiresAt().isAfter(now)) {
                subscription.setStatus(SubscriptionStatus.EXPIRED);
            }
        }
    }

    private boolean isMarketplaceUser(User user) {
        return user != null && (user.getRole() == UserRole.SEEKER || user.getRole() == UserRole.OWNER);
    }

    public record FulfillmentResult(boolean fulfilled,
                                    String errorCode,
                                    String errorMessage,
                                    OwnerSubscription subscription,
                                    PensionPromotion promotion) {
        public static FulfillmentResult success(OwnerSubscription subscription, PensionPromotion promotion) {
            return new FulfillmentResult(true, null, null, subscription, promotion);
        }

        public static FulfillmentResult failure(String code, String message) {
            return new FulfillmentResult(false, code, message, null, null);
        }
    }
}
