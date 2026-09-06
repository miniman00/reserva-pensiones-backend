package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uy.pensiones.enums.LaunchCampaignEventType;
import uy.pensiones.enums.LaunchCampaignStatus;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.LaunchCampaign;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.LaunchCampaignEvent;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;
import uy.pensiones.repo.LaunchCampaignEventRepository;
import uy.pensiones.repo.LaunchCampaignRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FounderLaunchCampaignServiceTest {

    @Mock private LaunchCampaignRepository campaigns;
    @Mock private LaunchCampaignBeneficiaryRepository beneficiaries;
    @Mock private LaunchCampaignEventRepository events;
    @Mock private FounderFeaturedBenefitService featuredBenefits;

    private FounderLaunchCampaignService service;

    @BeforeEach
    void setUp() {
        service = new FounderLaunchCampaignService(campaigns, beneficiaries, events, featuredBenefits);
    }

    @Test
    void twentiethDistinctOwnerGetsLastSlotPlanSnapshotAndInitialFeaturedPension() {
        User owner = User.builder().id(20L).name("Residencia 20").suspended(false).build();
        Pension pension = Pension.builder().id(200L).name("Pensión 20").owner(owner)
                .createdBy(owner).status(PensionStatus.PUBLISHED).build();
        LaunchCampaign campaign = activeCampaign(19, 20);

        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));
        when(beneficiaries.existsByCampaignIdAndUserId(1L, 20L)).thenReturn(false);
        when(beneficiaries.save(any())).thenAnswer(invocation -> {
            LaunchCampaignBeneficiary value = invocation.getArgument(0);
            value.setId(700L);
            return value;
        });
        when(featuredBenefits.activateInitialIfPossible(any(), eq(pension), any()))
                .thenReturn(PensionPromotion.builder().id(900L).build());

        var result = service.onFirstValidPublication(pension);

        assertThat(result.granted()).isTrue();
        assertThat(result.grantedOrder()).isEqualTo(20);
        assertThat(result.planVersionId()).isEqualTo(22L);
        assertThat(result.initialFeaturedPromotionId()).isEqualTo(900L);
        assertThat(campaign.getGrantedCount()).isEqualTo(20);

        ArgumentCaptor<LaunchCampaignBeneficiary> beneficiaryCaptor = ArgumentCaptor.forClass(LaunchCampaignBeneficiary.class);
        verify(beneficiaries).save(beneficiaryCaptor.capture());
        assertThat(beneficiaryCaptor.getValue().getUser()).isSameAs(owner);
        assertThat(beneficiaryCaptor.getValue().getSourcePension()).isSameAs(pension);
        assertThat(beneficiaryCaptor.getValue().getPlanVersion()).isSameAs(campaign.getBenefitPlanVersion());
        assertThat(beneficiaryCaptor.getValue().getMaxFeaturedPensionsSnapshot()).isEqualTo(3);
        assertThat(beneficiaryCaptor.getValue().getGrantedOrder()).isEqualTo(20);
        assertThat(beneficiaryCaptor.getValue().getExpiresAt())
                .isEqualTo(beneficiaryCaptor.getValue().getGrantedAt().plusDays(365));

        ArgumentCaptor<LaunchCampaignEvent> eventCaptor = ArgumentCaptor.forClass(LaunchCampaignEvent.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(LaunchCampaignEventType.AUTO_GRANTED);
        assertThat(eventCaptor.getValue().getAfterJson()).contains("\"planVersionId\":22");
        assertThat(eventCaptor.getValue().getAfterJson()).contains("\"initialFeaturedPromotionId\":900");
    }

    @Test
    void manualGrantCanUsePausedCampaignButStillConsumesARealSlot() {
        User owner = User.builder().id(30L).name("Residencia manual").suspended(false).build();
        Pension pension = Pension.builder().id(300L).name("Pensión manual").owner(owner)
                .createdBy(owner).status(PensionStatus.PUBLISHED).build();
        LaunchCampaign campaign = activeCampaign(4, 20);
        campaign.setStatus(LaunchCampaignStatus.PAUSED);
        BackofficeUser actor = BackofficeUser.builder().id(99L).username("soporte").displayName("Soporte").build();

        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));
        when(beneficiaries.existsByCampaignIdAndUserId(1L, 30L)).thenReturn(false);
        when(beneficiaries.save(any())).thenAnswer(invocation -> {
            LaunchCampaignBeneficiary value = invocation.getArgument(0);
            value.setId(701L);
            return value;
        });

        var result = service.grantManually(pension, actor, "Excepción comercial aprobada");

        assertThat(result.granted()).isTrue();
        assertThat(result.grantedOrder()).isEqualTo(5);
        assertThat(campaign.getGrantedCount()).isEqualTo(5);
        verify(beneficiaries).save(argThat(b -> b.getGrantedByBackofficeUser() == actor
                && "Excepción comercial aprobada".equals(b.getGrantReason())
                && Integer.valueOf(3).equals(b.getMaxFeaturedPensionsSnapshot())));
        verify(events).save(argThat(e -> e.getEventType() == LaunchCampaignEventType.MANUAL_GRANTED
                && e.getBackofficeUser() == actor));
    }

    @Test
    void twentyFirstOwnerDoesNotReceiveBenefitWhenCampaignIsFull() {
        User owner = User.builder().id(21L).suspended(false).build();
        Pension pension = Pension.builder().id(201L).owner(owner).createdBy(owner)
                .status(PensionStatus.PUBLISHED).build();
        LaunchCampaign campaign = activeCampaign(20, 20);

        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));
        when(beneficiaries.existsByCampaignIdAndUserId(1L, 21L)).thenReturn(false);

        var result = service.onFirstValidPublication(pension);

        assertThat(result.decision()).isEqualTo(FounderLaunchCampaignService.GrantDecision.CAMPAIGN_FULL);
        assertThat(campaign.getGrantedCount()).isEqualTo(20);
        verify(beneficiaries, never()).save(any());
        verifyNoInteractions(events, featuredBenefits);
    }

    @Test
    void secondPublishedPensionFromSameOwnerDoesNotConsumeAnotherSlot() {
        User owner = User.builder().id(7L).suspended(false).build();
        Pension pension = Pension.builder().id(207L).owner(owner).createdBy(owner)
                .status(PensionStatus.PUBLISHED).build();
        LaunchCampaign campaign = activeCampaign(6, 20);

        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));
        when(beneficiaries.existsByCampaignIdAndUserId(1L, 7L)).thenReturn(true);

        var result = service.onFirstValidPublication(pension);

        assertThat(result.decision()).isEqualTo(FounderLaunchCampaignService.GrantDecision.ALREADY_BENEFICIARY);
        assertThat(campaign.getGrantedCount()).isEqualTo(6);
        verify(beneficiaries, never()).save(any());
        verifyNoInteractions(featuredBenefits);
    }

    @Test
    void pausedCampaignDoesNotGrantBenefits() {
        User owner = User.builder().id(5L).suspended(false).build();
        Pension pension = Pension.builder().id(205L).owner(owner).createdBy(owner)
                .status(PensionStatus.PUBLISHED).build();
        LaunchCampaign campaign = activeCampaign(0, 20);
        campaign.setStatus(LaunchCampaignStatus.PAUSED);

        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));

        var result = service.onFirstValidPublication(pension);

        assertThat(result.decision()).isEqualTo(FounderLaunchCampaignService.GrantDecision.CAMPAIGN_NOT_ACTIVE);
        verifyNoInteractions(beneficiaries, events, featuredBenefits);
    }

    @Test
    void activeCampaignWithoutConfiguredBenefitPlanDoesNotConsumeSlot() {
        User owner = User.builder().id(5L).suspended(false).build();
        Pension pension = Pension.builder().id(205L).owner(owner).createdBy(owner)
                .status(PensionStatus.PUBLISHED).build();
        LaunchCampaign campaign = activeCampaign(0, 20);
        campaign.setBenefitPlanVersion(null);

        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));
        when(beneficiaries.existsByCampaignIdAndUserId(1L, 5L)).thenReturn(false);

        var result = service.onFirstValidPublication(pension);

        assertThat(result.decision()).isEqualTo(FounderLaunchCampaignService.GrantDecision.BENEFIT_PLAN_NOT_CONFIGURED);
        assertThat(campaign.getGrantedCount()).isZero();
        verify(beneficiaries, never()).save(any());
        verifyNoInteractions(events, featuredBenefits);
    }

    @Test
    void draftOrExpiredBenefitPlanCannotBeGranted() {
        User owner = User.builder().id(5L).suspended(false).build();
        Pension pension = Pension.builder().id(205L).owner(owner).createdBy(owner)
                .status(PensionStatus.PUBLISHED).build();
        LaunchCampaign campaign = activeCampaign(0, 20);
        campaign.getBenefitPlanVersion().setStatus(PlanVersionStatus.DRAFT);

        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));
        when(beneficiaries.existsByCampaignIdAndUserId(1L, 5L)).thenReturn(false);

        var result = service.onFirstValidPublication(pension);

        assertThat(result.decision()).isEqualTo(FounderLaunchCampaignService.GrantDecision.BENEFIT_PLAN_NOT_AVAILABLE);
        assertThat(campaign.getGrantedCount()).isZero();
        verify(beneficiaries, never()).save(any());
    }

    private LaunchCampaign activeCampaign(int granted, int max) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(true).build();
        PlanVersion version = PlanVersion.builder()
                .id(22L)
                .plan(plan)
                .version(1)
                .currency("UYU")
                .status(PlanVersionStatus.PUBLISHED)
                .effectiveFrom(now.minusDays(10))
                .effectiveUntil(now.plusMonths(6))
                .build();
        return LaunchCampaign.builder()
                .id(1L)
                .code(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE)
                .name("Propietarios Fundadores")
                .status(LaunchCampaignStatus.ACTIVE)
                .maxBeneficiaries(max)
                .grantedCount(granted)
                .benefitDurationDays(365)
                .maxFeaturedPensions(3)
                .benefitPlanVersion(version)
                .enrollmentStartsAt(now.minusDays(1))
                .enrollmentEndsAt(now.plusDays(30))
                .build();
    }
}
