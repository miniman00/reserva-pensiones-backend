package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.SubscriptionFeaturedDayUsageRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class SubscriptionFeaturedBenefitService {

    private final OwnerSubscriptionRepository subscriptions;
    private final SubscriptionFeaturedDayUsageRepository usage;
    private final PensionRepository pensions;
    private final PensionPromotionRepository promotions;

    public SubscriptionFeaturedBenefitService(OwnerSubscriptionRepository subscriptions,
                                              SubscriptionFeaturedDayUsageRepository usage,
                                              PensionRepository pensions,
                                              PensionPromotionRepository promotions) {
        this.subscriptions = subscriptions;
        this.usage = usage;
        this.pensions = pensions;
        this.promotions = promotions;
    }

    @Transactional(readOnly = true)
    public BenefitUsage currentUsage(OwnerEntitlementService.EntitlementSnapshot snapshot, OffsetDateTime now) {
        if (snapshot == null || snapshot.source() != EntitlementSource.SUBSCRIPTION
                || snapshot.subscription() == null || snapshot.plan() == null) {
            return BenefitUsage.unavailable();
        }
        int included = Math.max(0, snapshot.plan().featuredDays());
        BenefitCycle cycle = cycle(snapshot.subscription().startedAt(), snapshot.subscription().expiresAt(), now);
        if (cycle == null) return BenefitUsage.unavailable();
        long used = safe(usage.sumDaysForCycle(snapshot.subscription().id(), cycle.start()));
        int remaining = Math.max(0, included - (int) Math.min(Integer.MAX_VALUE, used));
        int maxActivatable = Math.min(remaining, fullDaysUntil(now, snapshot.subscription().expiresAt()));
        return new BenefitUsage(
                included > 0,
                snapshot.subscription().id(),
                cycle.start(), cycle.end(),
                included, used, remaining, maxActivatable,
                included > 0 && maxActivatable > 0 && snapshot.configurationReady()
        );
    }

    @Transactional
    public ActivationResult activate(Long userId, Long pensionId, int days) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No se pudo identificar al usuario autenticado");
        }
        if (pensionId == null || pensionId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La pensión no es válida");
        }
        if (days <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los días a utilizar deben ser mayores a cero");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<OwnerSubscription> active = subscriptions.findEffectiveActiveDetailed(userId, now, SubscriptionStatus.ACTIVE);
        if (active.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Necesitás una única suscripción activa para utilizar días destacados incluidos");
        }

        OwnerSubscription subscription = subscriptions.findByIdForUpdate(active.get(0).getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "La suscripción ya no está disponible"));
        validateSubscription(subscription, userId, now);

        PlanVersion planVersion = subscription.getPlanVersion();
        int included = planVersion == null ? 0 : Math.max(0, planVersion.getFeaturedDays());
        if (included == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tu plan actual no incluye días destacados");
        }

        BenefitCycle cycle = cycle(subscription.getStartedAt(), subscription.getExpiresAt(), now);
        if (cycle == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La suscripción no está vigente");
        }
        long alreadyUsed = safe(usage.sumDaysForCycle(subscription.getId(), cycle.start()));
        long remaining = Math.max(0L, (long) included - alreadyUsed);
        if (days > remaining) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo te quedan " + remaining + " días destacados incluidos en este período");
        }
        if (now.plusDays(days).isAfter(subscription.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El destacado incluido no puede extenderse más allá de la vigencia de tu suscripción");
        }

        Pension pension = pensions.findByIdForEntitlementUpdate(pensionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        User owner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        if (owner == null || !userId.equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada");
        }
        if (pension.getStatus() != PensionStatus.PUBLISHED || Boolean.TRUE.equals(pension.getModerationBlocked())
                || owner.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La pensión debe estar publicada y operativa para utilizar días destacados");
        }

        OffsetDateTime endsAt = now.plusDays(days);
        if (promotions.countOverlapping(pension.getId(), PromotionTargetType.GLOBAL.name(), null, now, endsAt) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La pensión ya tiene un destacado general activo o programado en ese período");
        }

        PensionPromotion promotion = promotions.save(PensionPromotion.builder()
                .pension(pension)
                .productVersion(null)
                .type(PensionPromotionType.FEATURED)
                .targetType(PromotionTargetType.GLOBAL)
                .studyCenter(null)
                .targetValue(null)
                .startsAt(now)
                .endsAt(endsAt)
                .status(PensionPromotionStatus.ACTIVE)
                .price(BigDecimal.ZERO)
                .currency(planVersion.getCurrency())
                .payment(null)
                .source(PensionPromotionSource.SUBSCRIPTION_BENEFIT)
                .createdByBackoffice(null)
                .build());

        usage.save(SubscriptionFeaturedDayUsage.builder()
                .subscription(subscription)
                .pension(pension)
                .promotion(promotion)
                .cycleStart(cycle.start())
                .cycleEnd(cycle.end())
                .days(days)
                .build());

        long updatedUsed = alreadyUsed + days;
        int updatedRemaining = Math.max(0, included - (int) updatedUsed);
        int maxActivatable = Math.min(updatedRemaining, fullDaysUntil(now, subscription.getExpiresAt()));
        BenefitUsage updated = new BenefitUsage(
                true, subscription.getId(), cycle.start(), cycle.end(), included,
                updatedUsed, updatedRemaining, maxActivatable, maxActivatable > 0
        );
        return new ActivationResult(
                promotion.getId(), pension.getId(), pension.getName(), days,
                promotion.getStartsAt(), promotion.getEndsAt(), updated
        );
    }

    private void validateSubscription(OwnerSubscription subscription, Long userId, OffsetDateTime now) {
        if (subscription.getUser() == null || !userId.equals(subscription.getUser().getId())
                || subscription.getStatus() != SubscriptionStatus.ACTIVE
                || subscription.getStartedAt() == null || subscription.getExpiresAt() == null
                || subscription.getStartedAt().isAfter(now) || !subscription.getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La suscripción no está vigente");
        }
        if (subscription.getPlanVersion() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La suscripción no tiene un plan válido");
        }
    }

    private BenefitCycle cycle(OffsetDateTime startsAt, OffsetDateTime expiresAt, OffsetDateTime now) {
        if (startsAt == null || expiresAt == null || now == null || now.isBefore(startsAt) || !now.isBefore(expiresAt)) {
            return null;
        }
        long monthIndex = 0L;
        OffsetDateTime start = startsAt;
        OffsetDateTime next = startsAt.plusMonths(1);
        while (!next.isAfter(now) && next.isBefore(expiresAt)) {
            monthIndex++;
            start = startsAt.plusMonths(monthIndex);
            next = startsAt.plusMonths(monthIndex + 1);
        }
        OffsetDateTime end = next.isAfter(expiresAt) ? expiresAt : next;
        return new BenefitCycle(start, end);
    }

    private int fullDaysUntil(OffsetDateTime now, OffsetDateTime end) {
        if (now == null || end == null || !end.isAfter(now)) return 0;
        long days = Duration.between(now.toInstant(), end.toInstant()).toDays();
        if (days <= 0) return 0;
        return days > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) days;
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private record BenefitCycle(OffsetDateTime start, OffsetDateTime end) {}

    public record BenefitUsage(
            boolean available,
            Long subscriptionId,
            OffsetDateTime cycleStart,
            OffsetDateTime cycleEnd,
            int includedDays,
            long usedDays,
            int remainingDays,
            int maxActivatableDays,
            boolean canActivate
    ) {
        static BenefitUsage unavailable() {
            return new BenefitUsage(false, null, null, null, 0, 0L, 0, 0, false);
        }
    }

    public record ActivationResult(
            Long promotionId,
            Long pensionId,
            String pensionName,
            int days,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BenefitUsage usage
    ) {}
}
