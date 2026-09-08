package uy.pensiones.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.LaunchCampaignStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.LaunchCampaign;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;
import uy.pensiones.repo.LaunchCampaignEventRepository;
import uy.pensiones.repo.LaunchCampaignRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PlanVersionRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminFounderCampaignServiceTest {

    @Mock LaunchCampaignRepository campaigns;
    @Mock LaunchCampaignBeneficiaryRepository beneficiaries;
    @Mock LaunchCampaignEventRepository events;
    @Mock PensionRepository pensions;
    @Mock PlanVersionRepository planVersions;
    @Mock FounderLaunchCampaignService founderCampaign;
    @Mock FounderFeaturedBenefitService featuredBenefits;
    @Mock OwnerTrialLifecycleService trialLifecycle;
    @Mock AdminAuditService audit;

    AdminFounderCampaignService service;
    BackofficeUser actor;

    @BeforeEach
    void setUp() {
        service = new AdminFounderCampaignService(campaigns, beneficiaries, events, pensions, planVersions,
                founderCampaign, featuredBenefits, trialLifecycle, audit, new ObjectMapper().findAndRegisterModules());
        actor = BackofficeUser.builder().id(99L).username("soporte").displayName("Soporte").build();
        when(audit.requireReason(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void configurationCannotReduceCapacityBelowAlreadyGrantedOwners() {
        LaunchCampaign campaign = campaign();
        campaign.setGrantedCount(12);
        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));

        var input = new AdminFounderCampaignService.ConfigInput(10, 365, 3, null,
                OffsetDateTime.now().minusDays(1), null, false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateConfig(input, "Ajuste", actor));

        assertThat(error.getStatusCode().value()).isEqualTo(409);
        verify(campaigns, never()).save(any());
    }

    @Test
    void manualFounderGrantConsumesTrialOpportunityAndRestoresCommercialAccess() {
        User owner = User.builder().id(10L).email("owner@example.com").name("Owner").build();
        Pension pension = Pension.builder().id(20L).name("Pensión").owner(owner).createdBy(owner)
                .status(uy.pensiones.enums.PensionStatus.PUBLISHED).build();
        OffsetDateTime grantedAt = OffsetDateTime.now().minusMinutes(1);
        when(pensions.findWithOwnerById(20L)).thenReturn(Optional.of(pension));
        when(founderCampaign.grantManually(eq(pension), eq(actor), eq("Excepción comercial")))
                .thenReturn(new FounderLaunchCampaignService.GrantResult(
                        FounderLaunchCampaignService.GrantDecision.GRANTED, 70L, 1, grantedAt,
                        grantedAt.plusDays(365), null, null));
        LaunchCampaignBeneficiary beneficiary = LaunchCampaignBeneficiary.builder()
                .id(70L).campaign(campaign()).user(owner).sourcePension(pension).grantedOrder(1)
                .grantedAt(grantedAt).expiresAt(grantedAt.plusDays(365))
                .status(LaunchCampaignBeneficiaryStatus.ACTIVE).maxFeaturedPensionsSnapshot(3).build();
        when(beneficiaries.findAdminDetailById(70L)).thenReturn(Optional.of(beneficiary));

        service.grantManually(20L, "Excepción comercial", actor);

        verify(trialLifecycle).consumeByFounderBenefit(owner, pension, grantedAt);
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_GRANT_LAUNCH_CAMPAIGN_BENEFIT),
                eq(AdminAuditEntityType.LAUNCH_CAMPAIGN_BENEFICIARY), eq(70L), isNull(), any(),
                eq("Excepción comercial"));
    }

    @Test
    void revokeCancelsFounderPromotionsButDoesNotReopenHistoricalSlot() {
        LaunchCampaign campaign = campaign();
        campaign.setGrantedCount(7);
        User owner = User.builder().id(10L).email("owner@example.com").name("Owner").build();
        Pension pension = Pension.builder().id(20L).name("Pensión").owner(owner).createdBy(owner).build();
        LaunchCampaignBeneficiary beneficiary = LaunchCampaignBeneficiary.builder()
                .id(70L).campaign(campaign).user(owner).sourcePension(pension).grantedOrder(7)
                .grantedAt(OffsetDateTime.now().minusDays(10)).expiresAt(OffsetDateTime.now().plusDays(355))
                .status(LaunchCampaignBeneficiaryStatus.ACTIVE).maxFeaturedPensionsSnapshot(3).build();
        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));
        when(beneficiaries.findByIdForUpdate(70L)).thenReturn(Optional.of(beneficiary));
        when(featuredBenefits.cancelActiveForBeneficiary(eq(beneficiary), any(), anyString())).thenReturn(2);

        var result = service.revoke(70L, "Incumplimiento comercial", actor);

        assertThat(result.status()).isEqualTo(LaunchCampaignBeneficiaryStatus.REVOKED);
        assertThat(campaign.getGrantedCount()).isEqualTo(7);
        verify(featuredBenefits).cancelActiveForBeneficiary(eq(beneficiary), any(), anyString());
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_REVOKE_LAUNCH_CAMPAIGN_BENEFIT),
                eq(AdminAuditEntityType.LAUNCH_CAMPAIGN_BENEFICIARY), eq(70L), any(), any(),
                eq("Incumplimiento comercial"));
    }

    @Test
    void extensionAlsoMovesFounderPromotionsToTheNewExpiration() {
        LaunchCampaign campaign = campaign();
        User owner = User.builder().id(10L).email("owner@example.com").name("Owner").build();
        Pension pension = Pension.builder().id(20L).name("Pensión").owner(owner).createdBy(owner).build();
        OffsetDateTime previousExpiration = OffsetDateTime.now().plusDays(10);
        LaunchCampaignBeneficiary beneficiary = LaunchCampaignBeneficiary.builder()
                .id(71L).campaign(campaign).user(owner).sourcePension(pension).grantedOrder(1)
                .grantedAt(OffsetDateTime.now().minusDays(355)).expiresAt(previousExpiration)
                .status(LaunchCampaignBeneficiaryStatus.ACTIVE).maxFeaturedPensionsSnapshot(3).build();
        when(campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE))
                .thenReturn(Optional.of(campaign));
        when(beneficiaries.findByIdForUpdate(71L)).thenReturn(Optional.of(beneficiary));
        when(featuredBenefits.extendForBeneficiary(eq(beneficiary), eq(previousExpiration), any(), any())).thenReturn(2);

        var result = service.extend(71L, 30, "Extensión comercial", actor);

        assertThat(result.expiresAt()).isAfter(previousExpiration);
        verify(featuredBenefits).extendForBeneficiary(eq(beneficiary), eq(previousExpiration),
                eq(result.expiresAt()), any());
        verify(trialLifecycle).consumeByFounderBenefit(owner, pension, beneficiary.getGrantedAt());
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_EXTEND_LAUNCH_CAMPAIGN_BENEFIT),
                eq(AdminAuditEntityType.LAUNCH_CAMPAIGN_BENEFICIARY), eq(71L), any(), any(),
                eq("Extensión comercial"));
    }

    private LaunchCampaign campaign() {
        return LaunchCampaign.builder().id(1L).code(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE)
                .name("Propietarios Fundadores").status(LaunchCampaignStatus.PAUSED)
                .maxBeneficiaries(20).grantedCount(0).benefitDurationDays(365).maxFeaturedPensions(3)
                .enrollmentStartsAt(OffsetDateTime.now().minusDays(1)).build();
    }
}
