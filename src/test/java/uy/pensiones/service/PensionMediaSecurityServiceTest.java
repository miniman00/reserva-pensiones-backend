package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PensionMediaSecurityServiceTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final PensionMediaSecurityService service = new PensionMediaSecurityService(
            10, 12 * 1024 * 1024L, 25 * 1024 * 1024L,
            10_000, 10_000, 24_000_000L
    );

    @Test
    void rejectsSvgAndHtmlEvenWhenClientMimeClaimsMedia() {
        MockMultipartFile svg = new MockMultipartFile(
                "files", "photo.svg", "image/svg+xml",
                "<svg><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile htmlAsVideo = new MockMultipartFile(
                "files", "movie.mp4", "video/mp4",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));

        assertThrows(ResponseStatusException.class, () -> service.inspect(svg));
        assertThrows(ResponseStatusException.class, () -> service.inspect(htmlAsVideo));
    }

    @Test
    void detectsImageFromBytesInsteadOfDeclaredMimeAndNormalizesIt() throws Exception {
        byte[] jpeg = jpegBytes(40, 30);
        MockMultipartFile disguised = new MockMultipartFile(
                "files", "anything.bin", "application/octet-stream", jpeg);

        var detected = service.inspect(disguised);
        assertEquals(PensionMediaSecurityService.MediaKind.IMAGE, detected.kind());
        assertEquals("image/jpeg", detected.mimeType());
        assertEquals(".jpg", detected.extension());
        assertEquals(40, detected.width());
        assertEquals(30, detected.height());

        Path target = Files.createTempFile("media-security-", ".jpg");
        try {
            var normalized = service.writeNormalizedImage(disguised, detected, target);
            assertEquals(40, normalized.width());
            assertEquals(30, normalized.height());
            assertTrue(normalized.sizeBytes() > 0);
            assertNotNull(ImageIO.read(target.toFile()));
        } finally {
            Files.deleteIfExists(target);
        }
    }


    @Test
    void normalizesExifOrientationAndRemovesMetadata() throws Exception {
        byte[] source = withExifOrientation(jpegBytes(40, 30), 6);
        MockMultipartFile image = new MockMultipartFile(
                "files", "phone.jpg", "image/jpeg", source);

        var detected = service.inspect(image);
        assertEquals(6, detected.orientation());

        Path target = Files.createTempFile("media-exif-", ".jpg");
        try {
            var normalized = service.writeNormalizedImage(image, detected, target);
            assertEquals(30, normalized.width());
            assertEquals(40, normalized.height());
            String binary = new String(Files.readAllBytes(target), StandardCharsets.ISO_8859_1);
            assertFalse(binary.contains("Exif"));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void rejectsDecompressionBombDimensionsBeforePublishing() throws Exception {
        PensionMediaSecurityService strict = new PensionMediaSecurityService(
                10, 12 * 1024 * 1024L, 25 * 1024 * 1024L,
                100, 100, 10_000L
        );
        MockMultipartFile image = new MockMultipartFile(
                "files", "large.png", "image/png", pngBytes(101, 20));

        assertThrows(ResponseStatusException.class, () -> strict.inspect(image));
    }

    @Test
    void doesNotMistakeHeicForMp4Video() {
        byte[] heic = new byte[] {
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c',
                0, 0, 0, 0, 'h', 'e', 'i', 'c', 'm', 'i', 'f', '1'
        };
        MockMultipartFile file = new MockMultipartFile("files", "photo.heic", "image/heic", heic);

        assertThrows(ResponseStatusException.class, () -> service.inspect(file));
    }

    @Test
    void detectsKnownVideoContainerAndIgnoresFilenameExtension() throws Exception {
        byte[] mp4 = new byte[] {
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'
        };
        MockMultipartFile file = new MockMultipartFile("files", "payload.html", "text/html", mp4);

        var detected = service.inspect(file);
        assertEquals(PensionMediaSecurityService.MediaKind.VIDEO, detected.kind());
        assertEquals("video/mp4", detected.mimeType());
        assertEquals(".mp4", detected.extension());
    }

    @Test
    void normalizesOnlyExactYoutubeAndVimeoHosts() {
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk",
                service.normalizeExternalVideoUrl("https://youtu.be/abcdefghijk?t=2"));
        assertEquals("https://vimeo.com/123456789",
                service.normalizeExternalVideoUrl("https://player.vimeo.com/video/123456789"));

        assertThrows(ResponseStatusException.class,
                () -> service.normalizeExternalVideoUrl("https://youtube.com.evil.example/watch?v=abcdefghijk"));
        assertThrows(ResponseStatusException.class,
                () -> service.normalizeExternalVideoUrl("javascript:alert(1)"));
        assertThrows(ResponseStatusException.class,
                () -> service.normalizeExternalVideoUrl("https://user:pass@youtube.com/watch?v=abcdefghijk"));
    }


    private byte[] withExifOrientation(byte[] jpeg, int orientation) throws Exception {
        byte[] segment = new byte[] {
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8,
                0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1,
                0, (byte) orientation, 0, 0, 0, 0, 0, 0
        };
        int length = segment.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(segment);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private byte[] jpegBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", out));
        return out.toByteArray();
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out));
        return out.toByteArray();
    }
}
