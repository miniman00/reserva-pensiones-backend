package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionMediaRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PensionDetailServiceTrustTest {

    @Test
    void exposesOnlyVerifiableAccountAndListingSignals() {
        PensionMediaRepository media = mock(PensionMediaRepository.class);
        when(media.findByPensionIdOrderBySortOrderAscIdAsc(44L)).thenReturn(List.of());

        OffsetDateTime memberSince = OffsetDateTime.of(2025, 4, 12, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime listingCreatedAt = OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, ZoneOffset.UTC);
        User owner = User.builder().id(8L).emailVerified(true).createdAt(memberSince).build();
        Pension pension = Pension.builder()
                .id(44L)
                .owner(owner)
                .createdAt(listingCreatedAt)
                .build();

        var detail = new PensionDetailService(media, mock(PensionMediaDtoMapper.class)).toPublicDetail(pension);

        assertThat(detail.trust().ownerEmailVerified()).isTrue();
        assertThat(detail.trust().ownerAccountCreatedAt()).isEqualTo(memberSince);
        assertThat(detail.trust().listingCreatedAt()).isEqualTo(listingCreatedAt);
    }
}
