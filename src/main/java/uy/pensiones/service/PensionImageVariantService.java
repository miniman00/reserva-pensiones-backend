package uy.pensiones.service;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMedia;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.storage.MediaStorage;
import uy.pensiones.storage.MediaStorageKeys;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

@Service
public class PensionImageVariantService {

    public static final int THUMB_MAX_WIDTH = 320;
    public static final int THUMB_MAX_HEIGHT = 240;
    public static final int CARD_MAX_WIDTH = 640;
    public static final int CARD_MAX_HEIGHT = 480;
    public static final int DETAIL_MAX_WIDTH = 1280;
    public static final int DETAIL_MAX_HEIGHT = 960;
    public static final int LARGE_MAX_WIDTH = 1920;
    public static final int LARGE_MAX_HEIGHT = 1440;

    public enum Variant {
        THUMB("thumb", THUMB_MAX_WIDTH, THUMB_MAX_HEIGHT, 0.78f),
        CARD("card", CARD_MAX_WIDTH, CARD_MAX_HEIGHT, 0.82f),
        DETAIL("detail", DETAIL_MAX_WIDTH, DETAIL_MAX_HEIGHT, 0.86f),
        LARGE("large", LARGE_MAX_WIDTH, LARGE_MAX_HEIGHT, 0.88f);

        private final String path;
        private final int maxWidth;
        private final int maxHeight;
        private final float jpegQuality;

        Variant(String path, int maxWidth, int maxHeight, float jpegQuality) {
            this.path = path;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.jpegQuality = jpegQuality;
        }

        public String path() { return path; }
        public int maxWidth() { return maxWidth; }
        public int maxHeight() { return maxHeight; }
        public float jpegQuality() { return jpegQuality; }

        public static Variant fromPath(String value) {
            if (value == null || value.isBlank()) return null;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Variant variant : values()) {
                if (variant.path.equals(normalized)) return variant;
            }
            return null;
        }
    }

    private final PensionMediaRepository media;
    private final PensionRepository pensions;
    private final MediaStorage storage;


    public PensionImageVariantService(PensionMediaRepository media, PensionRepository pensions, MediaStorage storage) {
        this.media = media;
        this.pensions = pensions;
        this.storage = storage;
    }

    public record ImageResource(Resource resource, String contentType) {}

    public static String imageUrl(Long pensionId, String filename, Variant variant) {
        if (pensionId == null || pensionId <= 0 || filename == null || filename.isBlank() || variant == null) return null;
        return "/api/public/media/pensions/" + pensionId + "/" + variant.path() + "/" + filename;
    }

    public static String cardUrl(Pension pension) {
        return pensionImageUrl(pension, Variant.CARD);
    }

    public static String detailUrl(Pension pension) {
        return pensionImageUrl(pension, Variant.DETAIL);
    }

    public static String largeUrl(Pension pension) {
        return pensionImageUrl(pension, Variant.LARGE);
    }

    private static String pensionImageUrl(Pension pension, Variant variant) {
        if (pension == null || pension.getId() == null || pension.getFeaturedImage() == null
                || pension.getFeaturedImage().isBlank()) {
            return null;
        }
        return imageUrl(pension.getId(), pension.getFeaturedImage(), variant);
    }

    public ImageResource variant(Long pensionId, String filename, Variant variant) throws IOException {
        String safeFilename = safeFilename(filename);
        boolean knownImage = media.existsByPensionIdAndKindAndFilename(
                pensionId, PensionMedia.Kind.IMAGE, safeFilename);
        if (!knownImage) {
            // Compatibilidad con publicaciones históricas que guardaban featured_image
            // antes de que cada archivo tuviera necesariamente una fila pension_media.
            knownImage = pensions.findById(pensionId)
                    .map(pension -> safeFilename.equals(pension.getFeaturedImage()))
                    .orElse(false);
        }
        if (!knownImage) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen no encontrada");
        }
        return variantAuthorized(pensionId, safeFilename, variant);
    }

    /**
     * Sirve la variante después de que el caller ya validó pertenencia/visibilidad.
     * Las variantes se generan de forma perezosa, por lo que las fotos históricas
     * se optimizan sin una migración ni un proceso batch previo.
     */
    public ImageResource variantAuthorized(Long pensionId, String filename, Variant variant) throws IOException {
        if (variant == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variante de imagen no encontrada");
        }
        String safeFilename = safeFilename(filename);
        String originalKey = MediaStorageKeys.original(pensionId, safeFilename);
        if (!storage.exists(originalKey)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen no encontrada");
        }

        String targetKey = MediaStorageKeys.variant(pensionId, safeFilename, variant.path());
        if (!isFresh(targetKey, originalKey)) {
            try {
                generateVariant(originalKey, targetKey, variant);
            } catch (Exception ignored) {
                if (isSafeRasterImage(originalKey)) {
                    return new ImageResource(storage.resource(originalKey), contentType(originalKey));
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen no encontrada");
            }
        }
        return new ImageResource(storage.resource(targetKey), "image/jpeg");
    }

    public ImageResource cardVariant(Long pensionId, String filename) throws IOException {
        return variant(pensionId, filename, Variant.CARD);
    }

    public ImageResource cardVariantAuthorized(Long pensionId, String filename) throws IOException {
        return variantAuthorized(pensionId, filename, Variant.CARD);
    }

    /**
     * Precalienta únicamente las variantes usadas en superficies de alta frecuencia.
     * DETAIL/LARGE permanecen lazy para que una subida con muchas fotos no decodifique
     * varias veces imágenes grandes dentro de la petición de upload.
     */
    public void preGenerateListingVariants(Long pensionId, String filename) {
        preGenerateVariant(pensionId, filename, Variant.THUMB);
        preGenerateVariant(pensionId, filename, Variant.CARD);
    }

    public void preGenerateCardVariant(Long pensionId, String filename) {
        preGenerateVariant(pensionId, filename, Variant.CARD);
    }

    private void preGenerateVariant(Long pensionId, String filename, Variant variant) {
        try {
            String safeFilename = safeFilename(filename);
            String originalKey = MediaStorageKeys.original(pensionId, safeFilename);
            if (storage.exists(originalKey)) {
                generateVariant(originalKey, MediaStorageKeys.variant(pensionId, safeFilename, variant.path()), variant);
            }
        } catch (Exception ignored) {
            // La generación perezosa del endpoint volverá a intentarlo.
        }
    }

    public void deleteVariants(Long pensionId, String filename) {
        if (filename == null || filename.isBlank()) return;
        try {
            String safe = safeFilename(filename);
            for (Variant variant : Variant.values()) {
                storage.delete(MediaStorageKeys.variant(pensionId, safe, variant.path()));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isFresh(String variantKey, String originalKey) throws IOException {
        return storage.exists(variantKey)
                && !storage.lastModified(variantKey).isBefore(storage.lastModified(originalKey));
    }

    private void generateVariant(String originalKey, String targetKey, Variant variant) throws IOException {
        BufferedImage source;
        try (var input = storage.resource(originalKey).getInputStream()) {
            source = ImageIO.read(input);
        }
        if (source == null) throw new IOException("Formato de imagen no soportado");

        double scale = Math.min(1d, Math.min(
                (double) variant.maxWidth() / Math.max(1, source.getWidth()),
                (double) variant.maxHeight() / Math.max(1, source.getHeight())
        ));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        storage.write(targetKey, jpegBytes(resized, variant.jpegQuality()));
    }

    private byte[] jpegBytes(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No hay encoder JPEG disponible");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static String safeFilename(String filename) {
        try {
            return MediaStorageKeys.safeFilename(filename);
        } catch (IllegalArgumentException ex) {
            if (filename == null || filename.isBlank()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen no encontrada");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre de imagen inválido");
        }
    }

    private boolean isSafeRasterImage(String key) {
        try (var input = storage.resource(key).getInputStream()) {
            byte[] header = input.readNBytes(8);
            return isJpeg(header) || isPng(header);
        } catch (IOException ignored) {
            return false;
        }
    }

    private String contentType(String key) {
        try (var input = storage.resource(key).getInputStream()) {
            byte[] header = input.readNBytes(8);
            if (isJpeg(header)) return "image/jpeg";
            if (isPng(header)) return "image/png";
        } catch (IOException ignored) {
        }
        return "application/octet-stream";
    }

    private boolean isJpeg(byte[] header) {
        return header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] header) {
        return header.length >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 0x50 && header[2] == 0x4E
                && header[3] == 0x47 && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A;
    }
}
