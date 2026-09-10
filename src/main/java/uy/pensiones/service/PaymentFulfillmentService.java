package uy.pensiones.service;

import org.springframework.stereotype.Service;
import uy.pensiones.enums.*;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.*;
import uy.pensiones.repo.AdminSubscriptionUserRepository;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.SubscriptionFeaturedDayUsageRepository;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class PaymentFulfillmentService {

    private final OwnerSubscriptionRepository subscriptions;
    private final AdminSubscriptionUserRepository users;
    private final PensionPromotionRepository promotions;
    private final PensionRepository pensions;
    private final SubscriptionFeaturedDayUsageRepository featuredDayUsage;
    private final OwnerTrialLifecycleService trialLifecycle;
    private final MailService mail;

    public PaymentFulfillmentService(OwnerSubscriptionRepository subscriptions,
                                     AdminSubscriptionUserRepository users,
                                     PensionPromotionRepository promotions,
                                     PensionRepository pensions,
                                     SubscriptionFeaturedDayUsageRepository featuredDayUsage,
                                     OwnerTrialLifecycleService trialLifecycle,
                                     MailService mail) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.promotions = promotions;
        this.pensions = pensions;
        this.featuredDayUsage = featuredDayUsage;
        this.trialLifecycle = trialLifecycle;
        this.mail = mail;
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
        Long targetPlanId = payment.getPlanVersion().getPlan() == null ? null : payment.getPlanVersion().getPlan().getId();
        boolean samePlanActive = targetPlanId != null && active.stream()
                .map(OwnerSubscription::getPlanVersion)
                .filter(java.util.Objects::nonNull)
                .map(PlanVersion::getPlan)
                .filter(java.util.Objects::nonNull)
                .anyMatch(plan -> targetPlanId.equals(plan.getId()));
        if (samePlanActive) {
            return FulfillmentResult.failure("ACTIVE_SAME_PLAN_CONFLICT",
                    "La cuenta ya tiene activo el mismo plan que intentó comprar");
        }
        for (OwnerSubscription previous : active) {
            cancelSubscriptionBenefit(previous, now,
                    "Sustituida automáticamente por cambio de plan mediante pago #" + payment.getId());
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
        trialLifecycle.consumeByPaidSubscription(user, startsAt);

        String previousPlanName = active.size() == 1
                && active.get(0).getPlanVersion() != null
                && active.get(0).getPlanVersion().getPlan() != null
                ? active.get(0).getPlanVersion().getPlan().getName()
                : null;
        Plan targetPlan = payment.getPlanVersion().getPlan();
        mail.sendSubscriptionActivated(new MailService.SubscriptionActivatedMail(
                user.getEmail(),
                user.getName(),
                targetPlan == null ? null : targetPlan.getName(),
                previousPlanName,
                targetPlan == null ? null : targetPlan.getDescription(),
                subscription.getStartedAt(),
                subscription.getExpiresAt(),
                payment.getSubscriptionPeriodMonths(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getMerchantReference(),
                planBenefits(payment.getPlanVersion()),
                null
        ));
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

        mail.sendPromotionActivated(new MailService.PromotionActivatedMail(
                owner.getEmail(),
                owner.getName(),
                pension.getName(),
                product.getName(),
                product.getDescription(),
                promotion.getStartsAt(),
                promotion.getEndsAt(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getMerchantReference(),
                null
        ));
        return FulfillmentResult.success(null, promotion);
    }

    /**
     * Revoca de forma idempotente el beneficio concedido cuando el proveedor confirma
     * un reembolso total. La trazabilidad queda en el pago y en el motivo de cancelación.
     */
    public void revokeAfterFullRefund(PaymentRecord payment, OffsetDateTime now) {
        OwnerSubscription subscription = payment.getFulfilledSubscription();
        if (subscription != null) {
            cancelSubscriptionBenefit(subscription, now,
                    "Cancelada automáticamente por reembolso total del pago #" + payment.getId());
        }

        PensionPromotion promotion = payment.getFulfilledPromotion();
        if (promotion != null && promotion.getStatus() != PensionPromotionStatus.CANCELLED) {
            promotion.setStatus(PensionPromotionStatus.CANCELLED);
            promotion.setCancelledAt(now);
            promotion.setCancellationReason("Cancelada automáticamente por reembolso total del pago #" + payment.getId());
            promotions.save(promotion);
        }
    }

    private void cancelSubscriptionBenefit(OwnerSubscription subscription, OffsetDateTime now, String reason) {
        if (subscription.getStatus() != SubscriptionStatus.CANCELLED) {
            subscription.setStatus(SubscriptionStatus.CANCELLED);
            subscription.setCancelledAt(now);
            subscription.setCancellationReason(reason);
            subscriptions.save(subscription);
        }
        if (subscription.getId() == null) return;
        for (SubscriptionFeaturedDayUsage item : featuredDayUsage.findBySubscription_Id(subscription.getId())) {
            PensionPromotion includedPromotion = item.getPromotion();
            if (includedPromotion == null || includedPromotion.getStatus() == PensionPromotionStatus.CANCELLED) continue;
            includedPromotion.setStatus(PensionPromotionStatus.CANCELLED);
            includedPromotion.setCancelledAt(now);
            includedPromotion.setCancellationReason(reason);
            promotions.save(includedPromotion);
        }
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

    private List<String> planBenefits(PlanVersion version) {
        if (version == null) return List.of();
        List<String> benefits = new java.util.ArrayList<>();
        benefits.add(limitBenefit(version.getMaxPensions(), "pensión publicada", "pensiones publicadas"));
        benefits.add(limitBenefit(version.getMaxCollaborators(), "colaborador por pensión", "colaboradores por pensión"));
        benefits.add(limitBenefit(version.getMaxPhotos(), "foto por pensión", "fotos por pensión"));
        benefits.add(limitBenefit(version.getMaxVideos(), "video por pensión", "videos por pensión"));
        if (version.getFeaturedDays() > 0) {
            benefits.add(version.getFeaturedDays() == 1
                    ? "1 día de destacado incluido"
                    : version.getFeaturedDays() + " días de destacado incluidos");
        }
        if (version.isAdvancedAnalytics()) benefits.add("Analítica avanzada");
        if (version.isInquiryHistory()) benefits.add("Historial avanzado de consultas");
        if (version.isConsolidatedAnalytics()) benefits.add("Analítica consolidada");
        if (version.isExportEnabled()) benefits.add("Exportación de datos");
        return List.copyOf(benefits);
    }

    private String limitBenefit(Integer limit, String singular, String plural) {
        if (limit == null) {
            String label = plural.substring(0, 1).toUpperCase() + plural.substring(1);
            return label + " sin límite";
        }
        return limit == 1 ? "1 " + singular : limit + " " + plural;
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
