package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdmissionType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.ResidentProfile;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMedia;
import uy.pensiones.repo.PensionMediaRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PensionPublicationServiceTest {

    @Mock
    private PensionMediaRepository mediaRepository;

    @Mock
    private PensionCatalogQualityService catalogQuality;

    private PensionPublicationService service;

    @BeforeEach
    void setUp() {
        service = new PensionPublicationService(mediaRepository, catalogQuality);
    }

    @Test
    void completePensionIsPublishable() {
        Pension pension = completePension();
        when(mediaRepository.findByPensionIdOrderBySortOrderAscIdAsc(10L))
                .thenReturn(List.of(image("cover.jpg")));

        var report = service.report(pension);

        assertThat(report.publishable()).isTrue();
        assertThat(report.issues()).isEmpty();
        assertThat(report.completenessPercent()).isGreaterThan(0);
    }

    @Test
    void admissionTypeIsRequiredToPublish() {
        Pension pension = completePension();
        pension.setAdmissionType(null);
        when(mediaRepository.findByPensionIdOrderBySortOrderAscIdAsc(10L))
                .thenReturn(List.of(image("cover.jpg")));

        var report = service.report(pension);

        assertThat(report.publishable()).isFalse();
        assertThat(report.issues()).anyMatch(issue -> issue.toLowerCase().contains("residencia es mixta"));
    }

    @Test
    void residentProfileIsRequiredToPublish() {
        Pension pension = completePension();
        pension.setResidentProfile(null);
        when(mediaRepository.findByPensionIdOrderBySortOrderAscIdAsc(10L))
                .thenReturn(List.of(image("cover.jpg")));

        var report = service.report(pension);

        assertThat(report.publishable()).isFalse();
        assertThat(report.issues()).anyMatch(issue -> issue.toLowerCase().contains("estudiantes"));
    }


    @Test
    void studentOrientedPensionCanPublishWithoutStudyCentersButGetsQualityRecommendation() {
        Pension pension = completePension();
        pension.setResidentProfile(ResidentProfile.STUDENTS_PREFERRED);
        when(mediaRepository.findByPensionIdOrderBySortOrderAscIdAsc(10L))
                .thenReturn(List.of(image("cover.jpg")));

        var report = service.report(pension);

        assertThat(report.publishable()).isTrue();
        assertThat(report.checklist()).anyMatch(item ->
                item.key().equals("studyCenters") && !item.complete() && !item.required());
    }

    @Test
    void publishedPensionMustKeepPriceForConfiguredRoomType() {
        Pension pension = completePension();
        pension.setPriceSimple(BigDecimal.ZERO);
        when(mediaRepository.findByPensionIdOrderBySortOrderAscIdAsc(10L))
                .thenReturn(List.of(image("cover.jpg")));

        assertThatThrownBy(() -> service.requirePublishable(pension))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("precio mayor que 0");
    }

    @Test
    void moderationBlockPreventsPublishing() {
        Pension pension = completePension();
        pension.setModerationBlocked(true);
        when(mediaRepository.findByPensionIdOrderBySortOrderAscIdAsc(10L))
                .thenReturn(List.of(image("cover.jpg")));

        var report = service.report(pension);

        assertThat(report.publishable()).isFalse();
        assertThat(report.issues()).anyMatch(issue -> issue.toLowerCase().contains("moderación"));
    }

    @Test
    void publishedPensionCannotDeleteItsLastImage() {
        Pension pension = completePension();
        pension.setStatus(PensionStatus.PUBLISHED);
        PensionMedia target = image("cover.jpg");
        when(mediaRepository.countByPensionIdAndKind(10L, PensionMedia.Kind.IMAGE)).thenReturn(1L);

        assertThatThrownBy(() -> service.requireCanDeleteMedia(pension, target))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("conservar al menos una imagen");
    }

    private Pension completePension() {
        return Pension.builder()
                .id(10L)
                .name("Pensión Centro")
                .description("Habitaciones cómodas, ambiente tranquilo y excelente ubicación para estudiantes.")
                .countryCode("UY")
                .city("Montevideo")
                .addressLine1("18 de Julio 1234")
                .lat(-34.9011)
                .lng(-56.1645)
                .capacitySimple(4)
                .capacityMatrimonial(0)
                .priceSimple(new BigDecimal("14500"))
                .admissionType(AdmissionType.MIXED)
                .residentProfile(ResidentProfile.OPEN_TO_ALL)
                .featuredImage("cover.jpg")
                .status(PensionStatus.DRAFT)
                .build();
    }

    private PensionMedia image(String filename) {
        PensionMedia media = new PensionMedia();
        media.setKind(PensionMedia.Kind.IMAGE);
        media.setFilename(filename);
        media.setUrl("/media/" + filename);
        return media;
    }
}
