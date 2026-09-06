package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.SubscriptionFeaturedDayUsageRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionFeaturedBenefitServiceTest {

    private OwnerSubscriptionRepository subscriptions;
    private SubscriptionFeaturedDayUsageRepository usage;
    private PensionRepository pensions;
    private PensionPromotionRepository promotions;
    private SubscriptionFeaturedBenefitService service;

    @BeforeEach
    void setUp() {
        subscriptions = mock(OwnerSubscriptionRepository.class);
        usage = mock(SubscriptionFeaturedDayUsageRepository.class);
        pensions = mock(PensionRepository.class);
        promotions = mock(PensionPromotionRepository.class);
        service = new SubscriptionFeaturedBenefitService(subscriptions, usage, pensions, promotions);
    }

    @Test
    void currentUsageUsesSubscriptionAnchoredMonthlyCycle() {
        OffsetDateTime starts = OffsetDateTime.parse("2026-07-15T10:00:00Z");
        OffsetDateTime expires = OffsetDateTime.parse("2026-10-15T10:00:00Z");
        OffsetDateTime now = OffsetDateTime.parse("2026-09-02T12:00:00Z");
        var snapshot = snapshot(starts, expires, 5);
        when(usage.sumDaysForCycle(70L, OffsetDateTime.parse("2026-08-15T10:00:00Z"))).thenReturn(2L);

        var result = service.currentUsage(snapshot, now);

        assertTrue(result.available());
        assertEquals(5, result.includedDays());
        assertEquals(2, result.usedDays());
        assertEquals(3, result.remainingDays());
        assertEquals(3, result.maxActivatableDays());
        assertEquals(OffsetDateTime.parse("2026-08-15T10:00:00Z"), result.cycleStart());
        assertEquals(OffsetDateTime.parse("2026-09-15T10:00:00Z"), result.cycleEnd());
        assertTrue(result.canActivate());
    }

    @Test
    void activationConsumesDaysAndCreatesGlobalPromotionFromSubscriptionBenefit() {
        User owner = User.builder().id(10L).email("owner@example.com").role(UserRole.OWNER).suspended(false).build();
        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(true).build();
        PlanVersion version = PlanVersion.builder().id(22L).plan(plan).version(1).featuredDays(5).currency("UYU").build();
        OffsetDateTime starts = OffsetDateTime.now().minusDays(5);
        OwnerSubscription subscription = OwnerSubscription.builder()
                .id(70L).user(owner).planVersion(version).status(SubscriptionStatus.ACTIVE)
                .startedAt(starts).expiresAt(starts.plusMonths(2)).source(SubscriptionSource.PAYMENT).build();
        Pension pension = Pension.builder().id(80L).name("Pensión Centro").owner(owner).createdBy(owner)
                .status(PensionStatus.PUBLISHED).moderationBlocked(false).build();

        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(List.of(subscription));
        when(subscriptions.findByIdForUpdate(70L)).thenReturn(Optional.of(subscription));
        when(usage.sumDaysForCycle(eq(70L), any())).thenReturn(2L);
        when(pensions.findByIdForEntitlementUpdate(80L)).thenReturn(Optional.of(pension));
        when(promotions.countOverlapping(eq(80L), eq("GLOBAL"), isNull(), any(), any())).thenReturn(0L);
        when(promotions.save(any(PensionPromotion.class))).thenAnswer(invocation -> {
            PensionPromotion promotion = invocation.getArgument(0);
            promotion.setId(90L);
            return promotion;
        });

        var result = service.activate(10L, 80L, 2);

        assertEquals(90L, result.promotionId());
        assertEquals(1, result.usage().remainingDays());
        verify(promotions).save(argThat(promotion ->
                promotion.getSource() == PensionPromotionSource.SUBSCRIPTION_BENEFIT
                        && promotion.getTargetType() == PromotionTargetType.GLOBAL
                        && promotion.getPrice().signum() == 0));
        verify(usage).save(argThat(entry -> entry.getDays() == 2
                && entry.getSubscription().getId().equals(70L)
                && entry.getPension().getId().equals(80L)));
    }

    @Test
    void activationRejectsMoreDaysThanRemain() {
        User owner = User.builder().id(10L).email("owner@example.com").role(UserRole.OWNER).build();
        PlanVersion version = PlanVersion.builder().id(22L).plan(Plan.builder().id(2L).code("PRO").name("Pro").build())
                .version(1).featuredDays(5).currency("UYU").build();
        OffsetDateTime starts = OffsetDateTime.now().minusDays(3);
        OwnerSubscription subscription = OwnerSubscription.builder().id(70L).user(owner).planVersion(version)
                .status(SubscriptionStatus.ACTIVE).startedAt(starts).expiresAt(starts.plusMonths(1))
                .source(SubscriptionSource.PAYMENT).build();
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(List.of(subscription));
        when(subscriptions.findByIdForUpdate(70L)).thenReturn(Optional.of(subscription));
        when(usage.sumDaysForCycle(eq(70L), any())).thenReturn(4L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.activate(10L, 80L, 2));

        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(pensions, promotions);
    }

    private OwnerEntitlementService.EntitlementSnapshot snapshot(OffsetDateTime starts,
                                                                 OffsetDateTime expires,
                                                                 int featuredDays) {
        return new OwnerEntitlementService.EntitlementSnapshot(
                10L, true, true, true, EntitlementSource.SUBSCRIPTION,
                null, null,
                new OwnerEntitlementService.EffectivePlan(
                        2L, "PRO", "Pro", 22L, 1,
                        5, 4, 20, 2, featuredDays,
                        true, true, true, true),
                new OwnerEntitlementService.EffectiveSubscription(
                        70L, SubscriptionSource.PAYMENT, starts, expires),
                null,
                new OwnerEntitlementService.UsageSummary(1L, 4, false),
                List.of(), false
        );
    }
}
