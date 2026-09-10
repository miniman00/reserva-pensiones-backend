package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uy.pensiones.enums.*;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.*;
import uy.pensiones.repo.AdminSubscriptionUserRepository;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.SubscriptionFeaturedDayUsageRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentFulfillmentPlanLifecycleTest {

    private OwnerSubscriptionRepository subscriptions;
    private AdminSubscriptionUserRepository users;
    private PensionPromotionRepository promotions;
    private SubscriptionFeaturedDayUsageRepository featuredDayUsage;
    private PaymentFulfillmentService service;
    private MailService mail;

    @BeforeEach
    void setUp() {
        subscriptions = mock(OwnerSubscriptionRepository.class);
        users = mock(AdminSubscriptionUserRepository.class);
        promotions = mock(PensionPromotionRepository.class);
        featuredDayUsage = mock(SubscriptionFeaturedDayUsageRepository.class);
        mail = mock(MailService.class);
        when(featuredDayUsage.findBySubscription_Id(anyLong())).thenReturn(List.of());
        service = new PaymentFulfillmentService(
                subscriptions, users, promotions, mock(PensionRepository.class), featuredDayUsage, mock(OwnerTrialLifecycleService.class), mail);
    }

    @Test
    void approvedDifferentPlanCancelsPreviousSubscriptionAndActivatesNewOne() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-08T22:00:00Z");
        User owner = User.builder().id(10L).role(UserRole.OWNER).build();
        Plan oldPlan = Plan.builder().id(1L).code("ESENCIAL").name("Esencial").build();
        PlanVersion oldVersion = PlanVersion.builder().id(11L).plan(oldPlan).version(1).build();
        OwnerSubscription previous = OwnerSubscription.builder()
                .id(70L).user(owner).planVersion(oldVersion).status(SubscriptionStatus.ACTIVE)
                .startedAt(now.minusDays(5)).expiresAt(now.plusDays(25)).source(SubscriptionSource.PAYMENT).build();

        Plan newPlan = Plan.builder().id(2L).code("PRO").name("Pro").build();
        PlanVersion newVersion = PlanVersion.builder().id(22L).plan(newPlan).version(1).build();
        PaymentRecord payment = PaymentRecord.builder()
                .id(7L).user(owner).purpose(PaymentPurpose.SUBSCRIPTION).provider(PaymentProvider.MERCADO_PAGO)
                .planVersion(newVersion).subscriptionPeriodMonths(1).amount(new BigDecimal("590.00")).currency("UYU")
                .status(PaymentStatus.APPROVED).approvedAt(now).build();

        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));
        when(subscriptions.findByUserIdAndStatusOrderByExpiresAtDesc(10L, SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(previous));
        when(subscriptions.findEffectiveActiveDetailed(10L, now, SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(previous));
        when(subscriptions.save(any(OwnerSubscription.class))).thenAnswer(invocation -> {
            OwnerSubscription value = invocation.getArgument(0);
            if (value.getId() == null) value.setId(71L);
            return value;
        });

        var result = service.fulfill(payment, now);

        assertTrue(result.fulfilled());
        assertEquals(SubscriptionStatus.CANCELLED, previous.getStatus());
        assertEquals(now, previous.getCancelledAt());
        assertTrue(previous.getCancellationReason().contains("cambio de plan"));
        assertNotNull(result.subscription());
        assertEquals(71L, result.subscription().getId());
        assertEquals(SubscriptionStatus.ACTIVE, result.subscription().getStatus());
        assertEquals(22L, result.subscription().getPlanVersion().getId());
        verify(mail).sendSubscriptionActivated(argThat(details ->
                details != null
                        && "Pro".equals(details.planName())
                        && "Esencial".equals(details.previousPlanName())
                        && details.benefits() != null));
    }

    @Test
    void fullRefundCancelsFulfilledSubscriptionIdempotently() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-08T22:00:00Z");
        OwnerSubscription subscription = OwnerSubscription.builder()
                .id(70L).status(SubscriptionStatus.ACTIVE)
                .startedAt(now.minusDays(1)).expiresAt(now.plusDays(29)).source(SubscriptionSource.PAYMENT).build();
        PensionPromotion included = PensionPromotion.builder().id(91L).status(PensionPromotionStatus.ACTIVE).build();
        SubscriptionFeaturedDayUsage usage = SubscriptionFeaturedDayUsage.builder()
                .id(5L).subscription(subscription).promotion(included).days(3).build();
        when(featuredDayUsage.findBySubscription_Id(70L)).thenReturn(List.of(usage));
        PaymentRecord payment = PaymentRecord.builder().id(7L).fulfilledSubscription(subscription).build();

        service.revokeAfterFullRefund(payment, now);
        service.revokeAfterFullRefund(payment, now.plusMinutes(1));

        assertEquals(SubscriptionStatus.CANCELLED, subscription.getStatus());
        assertEquals(now, subscription.getCancelledAt());
        assertTrue(subscription.getCancellationReason().contains("reembolso total"));
        assertEquals(PensionPromotionStatus.CANCELLED, included.getStatus());
        assertEquals(now, included.getCancelledAt());
        verify(subscriptions, times(1)).save(subscription);
        verify(promotions, times(1)).save(included);
    }
}
