package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.config.CatalogQualityProperties;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PensionCatalogQualityServiceTest {

    @Test
    void duplicateAtSameNormalizedAddressIsOnlyAQualitySignal() {
        PensionRepository pensions = mock(PensionRepository.class);
        CatalogQualityProperties properties = new CatalogQualityProperties();
        PensionCatalogQualityService service = new PensionCatalogQualityService(properties, pensions);

        User owner = User.builder().id(5L).build();
        Pension pension = Pension.builder()
                .id(11L)
                .owner(owner)
                .addressLine1("  18 de Julio   1234 ")
                .city(" Montevideo ")
                .build();

        when(pensions.existsPotentialDuplicate(11L, 5L, "18 de julio 1234", "montevideo"))
                .thenReturn(true);

        assertThat(service.hasPotentialDuplicate(pension)).isTrue();
    }

    @Test
    void publicVisibilityExpiresAfterConfiguredAvailabilityAge() {
        PensionRepository pensions = mock(PensionRepository.class);
        CatalogQualityProperties properties = new CatalogQualityProperties();
        properties.setMaxPublicAvailabilityAgeDays(30);
        PensionCatalogQualityService service = new PensionCatalogQualityService(properties, pensions);

        Pension fresh = Pension.builder()
                .availabilityUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2))
                .build();
        Pension expired = Pension.builder()
                .availabilityUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(31))
                .build();

        assertThat(service.hasFreshPublicAvailability(fresh)).isTrue();
        assertThat(service.hasFreshPublicAvailability(expired)).isFalse();
    }
}
