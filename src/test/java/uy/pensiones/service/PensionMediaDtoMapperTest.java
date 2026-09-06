package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMedia;
import uy.pensiones.storage.MediaStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PensionMediaDtoMapperTest {

    @Test
    void storedFilesUseActiveStorageDeliveryUrlInsteadOfPersistedLegacyUrl() {
        MediaStorage storage = mock(MediaStorage.class);
        when(storage.deliveryUrl("pensions/42/video.mp4")).thenReturn("https://cdn.example/pensions/42/video.mp4");
        Pension pension = Pension.builder().id(42L).build();
        PensionMedia media = new PensionMedia();
        media.setPension(pension);
        media.setKind(PensionMedia.Kind.VIDEO);
        media.setFilename("video.mp4");
        media.setUrl("/media/pensions/42/video.mp4");

        var dto = new PensionMediaDtoMapper(storage).toDto(media);

        assertThat(dto.url()).isEqualTo("https://cdn.example/pensions/42/video.mp4");
    }

    @Test
    void externalVideoKeepsNormalizedProviderUrl() {
        MediaStorage storage = mock(MediaStorage.class);
        PensionMedia media = new PensionMedia();
        media.setKind(PensionMedia.Kind.YOUTUBE);
        media.setUrl("https://www.youtube.com/watch?v=abcdefghijk");

        var dto = new PensionMediaDtoMapper(storage).toDto(media);

        assertThat(dto.url()).isEqualTo("https://www.youtube.com/watch?v=abcdefghijk");
    }
}
