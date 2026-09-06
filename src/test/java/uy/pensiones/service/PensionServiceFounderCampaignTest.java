package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.MembershipRepository;
import uy.pensiones.repo.OrganizationRepository;
import uy.pensiones.repo.PensionMemberRepository;
import uy.pensiones.repo.PensionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PensionServiceFounderCampaignTest {

    @Mock private PensionRepository pensions;
    @Mock private OrganizationRepository organizations;
    @Mock private OrgService orgService;
    @Mock private MembershipRepository memberships;
    @Mock private PensionMemberRepository pensionMembers;
    @Mock private StudyCenterCatalogService studyCenters;
    @Mock private OwnerEntitlementService entitlements;
    @Mock private PensionPublicationService publication;
    @Mock private FounderLaunchCampaignService founderCampaign;
    @Mock private FounderFeaturedBenefitService founderFeaturedBenefits;

    private PensionService service;

    @BeforeEach
    void setUp() {
        service = new PensionService(pensions, organizations, orgService, memberships, pensionMembers,
                studyCenters, entitlements, publication, founderCampaign, founderFeaturedBenefits);
    }

    @Test
    void firstTransitionToPublishedValidatesPersistsAndAttemptsFounderGrantInSameServiceOperation() {
        Pension pension = Pension.builder().id(15L).status(PensionStatus.DRAFT).draftStep("publish").build();
        when(pensions.saveAndFlush(pension)).thenReturn(pension);

        Pension result = service.changePublication(pension, PensionStatus.PUBLISHED);

        assertThat(result.getStatus()).isEqualTo(PensionStatus.PUBLISHED);
        assertThat(result.getDraftStep()).isNull();
        verify(publication).requirePublishable(pension);
        verify(pensions).saveAndFlush(pension);
        verify(founderCampaign).onFirstValidPublication(pension);
    }

    @Test
    void republishingAlreadyPublishedPensionDoesNotTryToConsumeAnotherFounderSlot() {
        Pension pension = Pension.builder().id(15L).status(PensionStatus.PUBLISHED).build();
        when(pensions.saveAndFlush(pension)).thenReturn(pension);

        service.changePublication(pension, PensionStatus.PUBLISHED);

        verify(publication).requirePublishable(pension);
        verify(founderCampaign, never()).onFirstValidPublication(any());
    }

    @Test
    void transferringPensionCancelsFounderHighlightBeforeChangingOwner() {
        User oldOwner = User.builder().id(1L).name("Fundador").build();
        User newOwner = User.builder().id(2L).name("Nuevo responsable").build();
        Pension pension = Pension.builder().id(15L).status(PensionStatus.PUBLISHED).owner(oldOwner).build();
        when(pensions.save(pension)).thenReturn(pension);

        Pension result = service.transferOwner(pension, newOwner);

        verify(entitlements).requireCanTakeOwnership(2L, 15L);
        verify(founderFeaturedBenefits).cancelForOwnershipTransfer(eq(pension), any());
        assertThat(result.getOwner()).isEqualTo(newOwner);
    }

    @Test
    void pausingPensionDoesNotTouchFounderCampaign() {
        Pension pension = Pension.builder().id(15L).status(PensionStatus.PUBLISHED).build();
        when(pensions.saveAndFlush(pension)).thenReturn(pension);

        service.changePublication(pension, PensionStatus.PAUSED);

        verify(publication, never()).requirePublishable(any());
        verify(founderCampaign, never()).onFirstValidPublication(any());
    }
}
