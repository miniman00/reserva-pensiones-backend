package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.PensionPromotionSource;
import uy.pensiones.enums.PensionPromotionStatus;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.LaunchCampaign;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FounderFeaturedBenefitServiceTest {

    private LaunchCampaignBeneficiaryRepository beneficiaries;
    private PensionRepository pensions;
    private PensionPromotionRepository promotions;
    private FounderFeaturedBenefitService service;

    @BeforeEach
    void setUp() {
        beneficiaries = mock(LaunchCampaignBeneficiaryRepository.class);
        pensions = mock(PensionRepository.class);
        promotions = mock(PensionPromotionRepository.class);
        service = new FounderFeaturedBenefitService(beneficiaries, pensions, promotions);
    }

    @Test
    void currentUsageReportsContinuousFeaturedSlots() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-03T12:00:00Z");
        LaunchCampaignBeneficiary beneficiary = beneficiary(now, 3);
        when(beneficiaries.findEffectiveDetailed(
                eq(10L), eq(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE),
                eq(LaunchCampaignBeneficiaryStatus.ACTIVE), eq(now)))
                .thenReturn(Optional.of(beneficiary));
        when(promotions.countEffectiveActiveForOwnerBySource(10L, "LAUNCH_CAMPAIGN", now)).thenReturn(2L);

        var result = service.currentUsage(10L, now);

        assertTrue(result.available());
        assertEquals(3, result.maxFeaturedPensions());
        assertEquals(2, result.activeFeaturedPensions());
        assertEquals(1, result.remainingFeaturedSlots());
        assertTrue(result.canActivate());
        assertEquals(beneficiary.getExpiresAt(), result.expiresAt());
        assertNotNull(result.plan());
        assertEquals("PRO", result.plan().planCode());
        assertEquals("Pro", result.plan().planName());
        assertEquals(22L, result.plan().planVersionId());
        assertEquals(5, result.plan().maxPensions());
        assertTrue(result.plan().advancedAnalytics());
    }

    @Test
    void activationCreatesZeroCostGlobalPromotionUntilFounderExpiration() {
        OffsetDateTime now = OffsetDateTime.now();
        LaunchCampaignBeneficiary beneficiary = beneficiary(now, 3);
        User owner = beneficiary.getUser();
        Pension pension = Pension.builder().id(80L).name("Pensión Centro").owner(owner).createdBy(owner)
                .status(PensionStatus.PUBLISHED).moderationBlocked(false).build();

        when(beneficiaries.findEffectiveForUpdate(eq(10L), anyString(), eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any()))
                .thenReturn(Optional.of(beneficiary));
        when(promotions.countEffectiveActiveForOwnerBySource(eq(10L), eq("LAUNCH_CAMPAIGN"), any())).thenReturn(1L);
        when(pensions.findByIdForEntitlementUpdate(80L)).thenReturn(Optional.of(pension));
        when(promotions.countOverlapping(eq(80L), eq("GLOBAL"), isNull(), any(), eq(beneficiary.getExpiresAt())))
                .thenReturn(0L);
        when(promotions.save(any(PensionPromotion.class))).thenAnswer(invocation -> {
            PensionPromotion promotion = invocation.getArgument(0);
            promotion.setId(90L);
            return promotion;
        });

        var result = service.activate(10L, 80L);

        assertEquals(90L, result.promotionId());
        assertEquals(2, result.usage().activeFeaturedPensions());
        assertEquals(1, result.usage().remainingFeaturedSlots());
        verify(promotions).save(argThat(promotion ->
                promotion.getSource() == PensionPromotionSource.LAUNCH_CAMPAIGN
                        && promotion.getPrice().signum() == 0
                        && promotion.getEndsAt().equals(beneficiary.getExpiresAt())
                        && promotion.getPayment() == null));
    }

    @Test
    void activationRejectsWhenAllFounderSlotsAreAlreadyInUse() {
        OffsetDateTime now = OffsetDateTime.now();
        LaunchCampaignBeneficiary beneficiary = beneficiary(now, 3);
        when(beneficiaries.findEffectiveForUpdate(eq(10L), anyString(), eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any()))
                .thenReturn(Optional.of(beneficiary));
        when(promotions.countEffectiveActiveForOwnerBySource(eq(10L), eq("LAUNCH_CAMPAIGN"), any())).thenReturn(3L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.activate(10L, 80L));

        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(pensions);
    }

    @Test
    void deactivationCancelsOnlyAnOwnedActiveFounderPromotionAndFreesSlot() {
        OffsetDateTime now = OffsetDateTime.now();
        LaunchCampaignBeneficiary beneficiary = beneficiary(now, 3);
        Pension pension = Pension.builder().id(80L).name("Pensión Centro").owner(beneficiary.getUser())
                .createdBy(beneficiary.getUser()).status(PensionStatus.PUBLISHED).build();
        PensionPromotion promotion = PensionPromotion.builder()
                .id(90L).pension(pension).source(PensionPromotionSource.LAUNCH_CAMPAIGN)
                .status(PensionPromotionStatus.ACTIVE).startsAt(now.minusDays(1)).endsAt(now.plusDays(10)).build();

        when(beneficiaries.findEffectiveForUpdate(eq(10L), anyString(), eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any()))
                .thenReturn(Optional.of(beneficiary));
        when(promotions.findFounderPromotionForOwnerForUpdate(
                eq(90L), eq(10L), eq(PensionPromotionSource.LAUNCH_CAMPAIGN), any()))
                .thenReturn(Optional.of(promotion));
        when(promotions.saveAndFlush(promotion)).thenReturn(promotion);
        when(promotions.countEffectiveActiveForOwnerBySource(eq(10L), eq("LAUNCH_CAMPAIGN"), any())).thenReturn(1L);

        var result = service.deactivate(10L, 90L);

        assertEquals(PensionPromotionStatus.CANCELLED, promotion.getStatus());
        assertNotNull(promotion.getCancelledAt());
        assertEquals(1, result.usage().activeFeaturedPensions());
        assertEquals(2, result.usage().remainingFeaturedSlots());
    }

    @Test
    void ownershipTransferCancelsOnlyEffectiveFounderPromotionForThatPension() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-03T12:00:00Z");
        Pension pension = Pension.builder().id(80L).build();
        when(promotions.cancelEffectiveForPensionBySource(
                80L, PensionPromotionSource.LAUNCH_CAMPAIGN, now,
                "Destacado Fundador cancelado por transferencia de responsable"))
                .thenReturn(1);

        service.cancelForOwnershipTransfer(pension, now);

        verify(promotions).cancelEffectiveForPensionBySource(
                80L, PensionPromotionSource.LAUNCH_CAMPAIGN, now,
                "Destacado Fundador cancelado por transferencia de responsable");
    }

    @Test
    void initialGrantDoesNotFailWhenSourcePensionAlreadyHasAnOverlappingPromotion() {
        OffsetDateTime now = OffsetDateTime.now();
        LaunchCampaignBeneficiary beneficiary = beneficiary(now, 3);
        Pension pension = Pension.builder().id(80L).name("Pensión Centro").owner(beneficiary.getUser())
                .createdBy(beneficiary.getUser()).status(PensionStatus.PUBLISHED).build();
        when(promotions.countOverlapping(eq(80L), eq("GLOBAL"), isNull(), eq(now), eq(beneficiary.getExpiresAt())))
                .thenReturn(1L);

        PensionPromotion result = service.activateInitialIfPossible(beneficiary, pension, now);

        assertNull(result);
        verify(promotions, never()).save(any());
    }

    private LaunchCampaignBeneficiary beneficiary(OffsetDateTime now, int maxFeatured) {
        User owner = User.builder().id(10L).email("owner@example.com").suspended(false).build();
        PlanVersion version = PlanVersion.builder()
                .id(22L).plan(Plan.builder().id(2L).code("PRO").name("Pro")
                        .description("Beneficios premium para propietarios").active(true).build())
                .version(1).currency("UYU")
                .maxPensions(5).maxCollaborators(4).maxPhotos(30).maxVideos(3)
                .featuredDays(5).advancedAnalytics(true).inquiryHistory(true)
                .consolidatedAnalytics(true).exportEnabled(true).build();
        LaunchCampaign campaign = LaunchCampaign.builder()
                .id(1L).code(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE)
                .name("Propietarios Fundadores").maxFeaturedPensions(maxFeatured).build();
        return LaunchCampaignBeneficiary.builder()
                .id(70L).campaign(campaign).user(owner).planVersion(version).grantedOrder(7)
                .grantedAt(now.minusDays(30)).expiresAt(now.plusDays(335))
                .status(LaunchCampaignBeneficiaryStatus.ACTIVE).build();
    }
}
