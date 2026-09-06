package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMedia;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.storage.LocalMediaStorage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PensionImageVariantServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesStableResponsiveUrlsForFeaturedImage() {
        Pension pension = new Pension();
        pension.setId(42L);
        pension.setFeaturedImage("cover.png");

        assertThat(PensionImageVariantService.cardUrl(pension))
                .isEqualTo("/api/public/media/pensions/42/card/cover.png");
        assertThat(PensionImageVariantService.detailUrl(pension))
                .isEqualTo("/api/public/media/pensions/42/detail/cover.png");
        assertThat(PensionImageVariantService.largeUrl(pension))
                .isEqualTo("/api/public/media/pensions/42/large/cover.png");
        assertThat(PensionImageVariantService.imageUrl(42L, "cover.png", PensionImageVariantService.Variant.THUMB))
                .isEqualTo("/api/public/media/pensions/42/thumb/cover.png");
    }

    @Test
    void generatesBoundedJpegVariantsForExistingImage() throws Exception {
        PensionMediaRepository media = mock(PensionMediaRepository.class);
        when(media.existsByPensionIdAndKindAndFilename(42L, PensionMedia.Kind.IMAGE, "cover.png"))
                .thenReturn(true);

        PensionImageVariantService service = new PensionImageVariantService(media, mock(PensionRepository.class), new LocalMediaStorage(tempDir.toString()));

        Path original = tempDir.resolve("pensions/42/cover.png");
        Files.createDirectories(original.getParent());
        BufferedImage source = new BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.dispose();
        ImageIO.write(source, "png", original.toFile());

        assertVariant(service, PensionImageVariantService.Variant.THUMB,
                PensionImageVariantService.THUMB_MAX_WIDTH, PensionImageVariantService.THUMB_MAX_HEIGHT);
        assertVariant(service, PensionImageVariantService.Variant.CARD,
                PensionImageVariantService.CARD_MAX_WIDTH, PensionImageVariantService.CARD_MAX_HEIGHT);
        assertVariant(service, PensionImageVariantService.Variant.DETAIL,
                PensionImageVariantService.DETAIL_MAX_WIDTH, PensionImageVariantService.DETAIL_MAX_HEIGHT);
        assertVariant(service, PensionImageVariantService.Variant.LARGE,
                PensionImageVariantService.LARGE_MAX_WIDTH, PensionImageVariantService.LARGE_MAX_HEIGHT);
    }


    @Test
    void imageVariantEndpointNeverFallsBackToAStoredVideo() throws Exception {
        PensionMediaRepository media = mock(PensionMediaRepository.class);
        PensionImageVariantService service = new PensionImageVariantService(media, mock(PensionRepository.class), new LocalMediaStorage(tempDir.toString()));

        Path storedVideo = tempDir.resolve("pensions/42/video.mp4");
        Files.createDirectories(storedVideo.getParent());
        Files.write(storedVideo, new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.variantAuthorized(42L, "video.mp4", PensionImageVariantService.Variant.THUMB))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private void assertVariant(PensionImageVariantService service,
                               PensionImageVariantService.Variant variant,
                               int maxWidth,
                               int maxHeight) throws Exception {
        var result = service.variant(42L, "cover.png", variant);
        BufferedImage generated = ImageIO.read(result.resource().getFile());

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(generated.getWidth()).isLessThanOrEqualTo(maxWidth);
        assertThat(generated.getHeight()).isLessThanOrEqualTo(maxHeight);
        assertThat(result.resource().exists()).isTrue();
    }
}
