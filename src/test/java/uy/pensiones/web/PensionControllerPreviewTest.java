package uy.pensiones.web;

import org.junit.jupiter.api.Test;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.Pension;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.security.Authz;
import uy.pensiones.service.PensionDetailService;
import uy.pensiones.service.PensionPublicationService;
import uy.pensiones.service.PensionService;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PensionControllerPreviewTest {

    @Test
    void previewLoadsOwnerGraphBeforeMappingTrustSignals() {
        PensionService service = mock(PensionService.class);
        PensionRepository pensions = mock(PensionRepository.class);
        UserRepository users = mock(UserRepository.class);
        Authz authz = mock(Authz.class);
        PensionPublicationService publication = mock(PensionPublicationService.class);
        PensionDetailService details = mock(PensionDetailService.class);

        Pension pension = Pension.builder()
                .id(1L)
                .status(PensionStatus.DRAFT)
                .build();
        when(pensions.findWithOwnerById(1L)).thenReturn(Optional.of(pension));

        PensionController controller = new PensionController(
                service, pensions, users, authz, publication, details);

        controller.preview(null, 1L);

        verify(pensions).findWithOwnerById(1L);
        verify(pensions, never()).findById(1L);
        verify(publication).report(pension);
        verify(details).toPublicDetail(pension);
    }
}
