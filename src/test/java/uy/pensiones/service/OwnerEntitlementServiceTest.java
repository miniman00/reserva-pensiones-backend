package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.EntitlementSource;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.enums.SubscriptionSource;
import uy.pensiones.enums.SubscriptionStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.LaunchCampaign;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.OwnerSubscription;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;
import uy.pensiones.repo.OwnerEntitlementQueryRepository;
import uy.pensiones.repo.OwnerEntitlementUserLockRepository;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PlanVersionRepository;
import uy.pensiones.repo.UserRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class OwnerEntitlementServiceTest {

    private AppProperties properties;
    private UserRepository users;
    private OwnerSubscriptionRepository subscriptions;
    private LaunchCampaignBeneficiaryRepository launchBenefits;
    private PlanVersionRepository planVersions;
    private OwnerEntitlementQueryRepository usage;
    private OwnerEntitlementUserLockRepository locks;
    private PensionRepository pensions;
    private OwnerEntitlementService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        users = mock(UserRepository.class);
        subscriptions = mock(OwnerSubscriptionRepository.class);
        launchBenefits = mock(LaunchCampaignBeneficiaryRepository.class);
        planVersions = mock(PlanVersionRepository.class);
        usage = mock(OwnerEntitlementQueryRepository.class);
        locks = mock(OwnerEntitlementUserLockRepository.class);
        pensions = mock(PensionRepository.class);
        service = new OwnerEntitlementService(properties, users, subscriptions, launchBenefits, planVersions, usage, locks, pensions);
    }

    @Test
    void monetizationDisabledReturnsBypassWithoutReadingCommercialState() {
        User user = marketplaceUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(usage.countResponsiblePensions(10L)).thenReturn(3L);
        when(usage.findPensionUsage(eq(10L), any())).thenReturn(List.of());

        var result = service.resolve(10L);

        assertEquals(EntitlementSource.BYPASS, result.source());
        assertFalse(result.monetizationEnabled());
        assertFalse(result.enforcementEnabled());
        assertTrue(result.configurationReady());
        assertEquals(3L, result.usage().responsiblePensions());
        verifyNoInteractions(subscriptions, launchBenefits, planVersions);
    }

    @Test
    void enabledWithoutEffectiveFreePlanIsReportedAsMisconfigured() {
        properties.getMonetization().setEnabled(true);
        User user = marketplaceUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(usage.countResponsiblePensions(10L)).thenReturn(0L);
        when(usage.findPensionUsage(eq(10L), any())).thenReturn(List.of());
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(List.of());
        when(launchBenefits.findEffectiveDetailed(eq(10L), eq(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE),
                eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any())).thenReturn(Optional.empty());
        when(planVersions.findEffectiveFreeVersions(eq("FREE"), eq(PlanVersionStatus.PUBLISHED), any())).thenReturn(List.of());

        var result = service.resolve(10L);

        assertEquals(EntitlementSource.MISCONFIGURED, result.source());
        assertFalse(result.configurationReady());
        assertEquals("FREE_PLAN_NOT_CONFIGURED", result.issueCode());
    }

    @Test
    void activeSubscriptionWinsOverFreeFallbackAndKeepsContractedVersion() {
        properties.getMonetization().setEnabled(true);
        User user = marketplaceUser(10L);
        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(false).build();
        PlanVersion version = PlanVersion.builder()
                .id(20L).plan(plan).version(1).status(PlanVersionStatus.RETIRED)
                .maxPensions(3).maxCollaborators(2).maxPhotos(20).maxVideos(1)
                .advancedAnalytics(true).build();
        OwnerSubscription subscription = OwnerSubscription.builder()
                .id(30L).user(user).planVersion(version).status(SubscriptionStatus.ACTIVE)
                .source(SubscriptionSource.ADMIN_GRANT)
                .startedAt(OffsetDateTime.now().minusDays(1))
                .expiresAt(OffsetDateTime.now().plusDays(30)).build();

        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(usage.countResponsiblePensions(10L)).thenReturn(1L);
        when(usage.findPensionUsage(eq(10L), any())).thenReturn(List.of());
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(List.of(subscription));

        var result = service.resolve(10L);

        assertEquals(EntitlementSource.SUBSCRIPTION, result.source());
        assertEquals("PRO", result.plan().planCode());
        assertEquals(1, result.plan().planVersion());
        assertEquals(3, result.plan().maxPensions());
        assertEquals(30L, result.subscription().id());
        verify(planVersions, never()).findEffectiveFreeVersions(anyString(), any(PlanVersionStatus.class), any());
    }

    @Test
    void activeFounderBenefitUsesSnapshottedPlanWhenThereIsNoPaidSubscription() {
        properties.getMonetization().setEnabled(true);
        User user = marketplaceUser(10L);
        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(false).build();
        PlanVersion version = PlanVersion.builder()
                .id(22L).plan(plan).version(3).status(PlanVersionStatus.RETIRED)
                .maxPensions(5).maxCollaborators(4).maxPhotos(30).maxVideos(3)
                .advancedAnalytics(true).inquiryHistory(true).build();
        LaunchCampaign campaign = LaunchCampaign.builder()
                .id(1L).code(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE)
                .name("Propietarios Fundadores").maxFeaturedPensions(3).build();
        OffsetDateTime grantedAt = OffsetDateTime.now().minusDays(20);
        LaunchCampaignBeneficiary benefit = LaunchCampaignBeneficiary.builder()
                .id(70L).campaign(campaign).user(user).planVersion(version).grantedOrder(7)
                .grantedAt(grantedAt).expiresAt(grantedAt.plusDays(365))
                .status(LaunchCampaignBeneficiaryStatus.ACTIVE).build();

        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(usage.countResponsiblePensions(10L)).thenReturn(1L);
        when(usage.findPensionUsage(eq(10L), any())).thenReturn(List.of());
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(List.of());
        when(launchBenefits.findEffectiveDetailed(eq(10L), eq(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE),
                eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any())).thenReturn(Optional.of(benefit));

        var result = service.resolve(10L);

        assertEquals(EntitlementSource.LAUNCH_CAMPAIGN, result.source());
        assertEquals("PRO", result.plan().planCode());
        assertEquals(3, result.plan().planVersion());
        assertNull(result.subscription());
        assertNotNull(result.launchCampaignBenefit());
        assertEquals(70L, result.launchCampaignBenefit().beneficiaryId());
        assertEquals(7, result.launchCampaignBenefit().grantedOrder());
        assertEquals(3, result.launchCampaignBenefit().maxFeaturedPensions());
        verify(planVersions, never()).findEffectiveFreeVersions(anyString(), any(PlanVersionStatus.class), any());
    }

    @Test
    void paidSubscriptionTakesPrecedenceOverFounderBenefitWithoutCreatingAConflict() {
        properties.getMonetization().setEnabled(true);
        User user = marketplaceUser(10L);
        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(true).build();
        PlanVersion version = PlanVersion.builder().id(20L).plan(plan).version(1).maxPensions(3).build();
        OwnerSubscription subscription = OwnerSubscription.builder()
                .id(30L).user(user).planVersion(version).status(SubscriptionStatus.ACTIVE)
                .source(SubscriptionSource.PAYMENT).startedAt(OffsetDateTime.now().minusDays(1))
                .expiresAt(OffsetDateTime.now().plusDays(30)).build();
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(usage.countResponsiblePensions(10L)).thenReturn(0L);
        when(usage.findPensionUsage(eq(10L), any())).thenReturn(List.of());
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(List.of(subscription));

        var result = service.resolve(10L);

        assertEquals(EntitlementSource.SUBSCRIPTION, result.source());
        assertEquals(30L, result.subscription().id());
        assertNull(result.launchCampaignBenefit());
        verifyNoInteractions(launchBenefits);
    }

    @Test
    void createPensionGuardRejectsWhenFreeLimitIsReached() {
        properties.getMonetization().setEnabled(true);
        User user = marketplaceUser(10L);
        Plan free = Plan.builder().id(1L).code("FREE").name("Free").active(true).build();
        PlanVersion version = PlanVersion.builder()
                .id(11L).plan(free).version(1).status(PlanVersionStatus.PUBLISHED)
                .maxPensions(1).build();

        when(locks.lockUser(10L)).thenReturn(Optional.of(user));
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(List.of());
        when(launchBenefits.findEffectiveDetailed(eq(10L), eq(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE),
                eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any())).thenReturn(Optional.empty());
        when(planVersions.findEffectiveFreeVersions(eq("FREE"), eq(PlanVersionStatus.PUBLISHED), any())).thenReturn(List.of(version));
        when(usage.countResponsiblePensions(10L)).thenReturn(1L);

        EntitlementLimitExceededException ex = assertThrows(
                EntitlementLimitExceededException.class,
                () -> service.requireCanCreatePension(10L)
        );

        assertEquals("MAX_PENSIONS", ex.getLimitCode());
        assertEquals(1, ex.getLimit());
        assertEquals(1L, ex.getCurrentUsage());
    }

    @Test
    void advancedAnalyticsGuardRejectsPlanWithoutFeature() {
        properties.getMonetization().setEnabled(true);
        User user = marketplaceUser(10L);
        Plan free = Plan.builder().id(1L).code("FREE").name("Free").active(true).build();
        PlanVersion version = PlanVersion.builder().id(11L).plan(free).version(1)
                .status(PlanVersionStatus.PUBLISHED).advancedAnalytics(false).build();
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(usage.countResponsiblePensions(10L)).thenReturn(0L);
        when(usage.findPensionUsage(eq(10L), any())).thenReturn(List.of());
        when(subscriptions.findEffectiveActiveDetailed(eq(10L), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(List.of());
        when(launchBenefits.findEffectiveDetailed(eq(10L), eq(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE),
                eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any())).thenReturn(Optional.empty());
        when(planVersions.findEffectiveFreeVersions(eq("FREE"), eq(PlanVersionStatus.PUBLISHED), any())).thenReturn(List.of(version));

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.requireAdvancedAnalytics(10L));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void guardsAreNoOpsWhileMasterSwitchIsDisabled() {
        service.requireCanCreatePension(10L);
        service.requireCanAddCollaborator(99L);
        service.requireCanAddMedia(99L, 10, 2);

        verifyNoInteractions(locks, subscriptions, launchBenefits, planVersions, usage, pensions);
    }

    private User marketplaceUser(Long id) {
        return User.builder().id(id).email("owner@example.com").role(UserRole.OWNER).build();
    }
}
