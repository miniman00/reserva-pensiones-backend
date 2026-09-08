package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.OwnerTrialConsumptionReason;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.enums.SubscriptionSource;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.LaunchCampaign;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.OwnerTrialLifecycle;
import uy.pensiones.model.OwnerTrialSettings;
import uy.pensiones.model.Pension;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;
import uy.pensiones.repo.OwnerEntitlementUserLockRepository;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.OwnerTrialLifecycleRepository;
import uy.pensiones.repo.OwnerTrialSettingsRepository;
import uy.pensiones.repo.PensionRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerTrialLifecycleServiceTest {

    @Mock OwnerTrialSettingsRepository settings;
    @Mock OwnerTrialLifecycleRepository lifecycles;
    @Mock OwnerEntitlementUserLockRepository userLocks;
    @Mock OwnerSubscriptionRepository subscriptions;
    @Mock LaunchCampaignBeneficiaryRepository founderBenefits;
    @Mock PensionRepository pensions;
    @Mock PensionPublicationService publication;
    @Mock NotificationService notifications;
    @Mock MailService mail;

    OwnerTrialLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new OwnerTrialLifecycleService(settings, lifecycles, userLocks, subscriptions,
                founderBenefits, pensions, publication, notifications, mail, new AppProperties(), "America/Montevideo");
        lenient().when(lifecycles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void firstPublishedPensionStartsExactlyOneTrialWithSnapshottedDates() {
        OffsetDateTime now = OffsetDateTime.now();
        User owner = User.builder().id(10L).email("owner@example.com").build();
        Plan free = Plan.builder().id(1L).code("FREE").name("Prueba gratuita").active(true).build();
        PlanVersion freeVersion = PlanVersion.builder().id(11L).plan(free).version(1)
                .status(PlanVersionStatus.PUBLISHED).effectiveFrom(now.minusDays(1)).build();
        OwnerTrialSettings config = OwnerTrialSettings.builder().id((short) 1).enabled(true)
                .durationDays(90).graceDays(7).trialPlanVersion(freeVersion).build();
        Pension pension = Pension.builder().id(50L).owner(owner).status(PensionStatus.PUBLISHED).build();

        when(userLocks.lockUser(10L)).thenReturn(Optional.of(owner));
        when(lifecycles.findByUserIdForUpdate(10L)).thenReturn(Optional.empty());
        when(settings.findDetailedById((short) 1)).thenReturn(Optional.of(config));
        when(subscriptions.existsByUserIdAndSource(10L, SubscriptionSource.PAYMENT)).thenReturn(false);
        when(founderBenefits.findHistoryDetailed(10L, FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(List.of());

        OwnerTrialLifecycle result = service.onFirstValidPublication(pension, null);

        assertThat(result).isNotNull();
        assertThat(result.getConsumptionReason()).isEqualTo(OwnerTrialConsumptionReason.TRIAL_STARTED);
        assertThat(result.getTrialPlanVersion()).isEqualTo(freeVersion);
        assertThat(result.getDurationDaysSnapshot()).isEqualTo(90);
        assertThat(result.getGraceDaysSnapshot()).isEqualTo(7);
        assertThat(result.getTrialExpiresAt()).isAfter(result.getTrialStartedAt());
        assertThat(result.getGraceExpiresAt()).isAfter(result.getTrialExpiresAt());
    }

    @Test
    void founderGrantReplacesExistingTrialAndReactivatesOnlyCommerciallyPausedPensions() {
        OffsetDateTime now = OffsetDateTime.now();
        User owner = User.builder().id(10L).email("owner@example.com").build();
        Pension source = Pension.builder().id(50L).owner(owner).status(PensionStatus.PUBLISHED).build();
        Pension paused = Pension.builder().id(51L).owner(owner).status(PensionStatus.PAUSED)
                .commercialPauseReason(OwnerTrialLifecycleService.COMMERCIAL_PAUSE_REASON).build();
        OwnerTrialLifecycle existing = OwnerTrialLifecycle.builder()
                .id(90L).user(owner).consumptionReason(OwnerTrialConsumptionReason.TRIAL_STARTED)
                .sourcePension(source).consumedAt(now.minusDays(20)).trialStartedAt(now.minusDays(20))
                .trialExpiresAt(now.plusDays(70)).graceExpiresAt(now.plusDays(77))
                .durationDaysSnapshot(90).graceDaysSnapshot(7).build();
        when(userLocks.lockUser(10L)).thenReturn(Optional.of(owner));
        when(lifecycles.findByUserIdForUpdate(10L)).thenReturn(Optional.of(existing));
        when(settings.findDetailedById((short) 1)).thenReturn(Optional.of(
                OwnerTrialSettings.builder().id((short) 1).enabled(true).durationDays(90).graceDays(7).build()));
        when(pensions.findCommerciallyPausedForResponsibleUser(10L, OwnerTrialLifecycleService.COMMERCIAL_PAUSE_REASON))
                .thenReturn(List.of(paused));

        OwnerTrialLifecycle result = service.consumeByFounderBenefit(owner, source, now);

        assertThat(result.getConsumptionReason()).isEqualTo(OwnerTrialConsumptionReason.FOUNDER_GRANTED);
        assertThat(result.getTrialStartedAt()).isNull();
        assertThat(result.getTrialExpiresAt()).isNull();
        assertThat(result.getDurationDaysSnapshot()).isNull();
        assertThat(result.getAccessSuspendedAt()).isNull();
        assertThat(paused.getCommercialPauseReason()).isNull();
        verify(publication).requirePublishable(paused);
        assertThat(paused.getStatus()).isEqualTo(PensionStatus.PUBLISHED);
    }

    @Test
    void activeFounderPreventsTrialSchedulerFromPausingOwnerEvenForHistoricalMixedLifecycle() {
        OffsetDateTime now = OffsetDateTime.now();
        User owner = User.builder().id(10L).email("owner@example.com").build();
        OwnerTrialLifecycle lifecycle = OwnerTrialLifecycle.builder()
                .id(90L).user(owner).consumptionReason(OwnerTrialConsumptionReason.TRIAL_STARTED)
                .consumedAt(now.minusDays(100)).trialStartedAt(now.minusDays(100))
                .trialExpiresAt(now.minusDays(10)).graceExpiresAt(now.minusDays(3))
                .durationDaysSnapshot(90).graceDaysSnapshot(7).build();
        LaunchCampaign campaign = LaunchCampaign.builder().id(1L)
                .code(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE).build();
        LaunchCampaignBeneficiary founder = LaunchCampaignBeneficiary.builder()
                .id(70L).campaign(campaign).user(owner).grantedAt(now.minusDays(20))
                .expiresAt(now.plusDays(345)).status(LaunchCampaignBeneficiaryStatus.ACTIVE).build();
        when(lifecycles.findByAccessSuspendedAtIsNullOrderByConsumedAtAsc()).thenReturn(List.of(lifecycle));
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), any())).thenReturn(List.of());
        when(founderBenefits.findEffectiveDetailed(eq(10L), eq(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE),
                eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any())).thenReturn(Optional.of(founder));

        service.processLifecycle(now);

        verify(pensions, never()).findPublishedForResponsibleUser(10L);
        assertThat(lifecycle.getAccessSuspendedAt()).isNull();
    }

    @Test
    void payingBeforeTrialMarksOpportunityConsumedAndDoesNotCreateAnotherTrial() {
        User owner = User.builder().id(10L).email("owner@example.com").build();
        when(userLocks.lockUser(10L)).thenReturn(Optional.of(owner));
        when(lifecycles.findByUserIdForUpdate(10L)).thenReturn(Optional.empty());
        when(settings.findDetailedById((short) 1)).thenReturn(Optional.of(
                OwnerTrialSettings.builder().id((short) 1).enabled(true).durationDays(90).graceDays(7).build()));
        when(pensions.findCommerciallyPausedForResponsibleUser(10L, OwnerTrialLifecycleService.COMMERCIAL_PAUSE_REASON))
                .thenReturn(List.of());

        OwnerTrialLifecycle result = service.consumeByPaidSubscription(owner, OffsetDateTime.now());

        assertThat(result.getConsumptionReason()).isEqualTo(OwnerTrialConsumptionReason.PAID_DIRECT);
        assertThat(result.getTrialStartedAt()).isNull();
        assertThat(result.getGraceDaysSnapshot()).isEqualTo(7);
    }
}
