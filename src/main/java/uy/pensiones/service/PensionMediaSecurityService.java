package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PensionMediaSecurityService {

    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[] WEBM_SIGNATURE = new byte[] {
            0x1A, 0x45, (byte) 0xDF, (byte) 0xA3
    };
    private static final Pattern YOUTUBE_ID = Pattern.compile("^[A-Za-z0-9_-]{6,32}$");
    private static final Pattern VIMEO_ID = Pattern.compile("^[0-9]{5,16}$");
    private static final Set<String> YOUTUBE_HOSTS = Set.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
            "youtube-nocookie.com", "www.youtube-nocookie.com"
    );
    private static final Set<String> VIMEO_HOSTS = Set.of(
            "vimeo.com", "www.vimeo.com", "player.vimeo.com"
    );
    private static final Set<String> MP4_BRANDS = Set.of(
            "isom", "iso2", "mp41", "mp42", "avc1", "M4V ", "MSNV", "dash"
    );

    private final int maxFilesPerRequest;
    private final long maxImageBytes;
    private final long maxVideoBytes;
    private final int maxImageWidth;
    private final int maxImageHeight;
    private final long maxImagePixels;

    public PensionMediaSecurityService(
            @Value("${app.media-security.max-files-per-request:10}") int maxFilesPerRequest,
            @Value("${app.media-security.max-image-bytes:12582912}") long maxImageBytes,
            @Value("${app.media-security.max-video-bytes:26214400}") long maxVideoBytes,
            @Value("${app.media-security.max-image-width:10000}") int maxImageWidth,
            @Value("${app.media-security.max-image-height:10000}") int maxImageHeight,
            @Value("${app.media-security.max-image-pixels:24000000}") long maxImagePixels) {
        this.maxFilesPerRequest = Math.max(1, maxFilesPerRequest);
        this.maxImageBytes = Math.max(1, maxImageBytes);
        this.maxVideoBytes = Math.max(1, maxVideoBytes);
        this.maxImageWidth = Math.max(1, maxImageWidth);
        this.maxImageHeight = Math.max(1, maxImageHeight);
        this.maxImagePixels = Math.max(1, maxImagePixels);
    }

    public void requireValidFileCount(int count) {
        if (count <= 0) {
            throw badRequest("Selecciona al menos un archivo.");
        }
        if (count > maxFilesPerRequest) {
            throw badRequest("Puedes subir como máximo " + maxFilesPerRequest + " archivos por vez.");
        }
    }

    public DetectedMedia inspect(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw badRequest("El archivo está vacío.");
        }

        byte[] header = readHeader(file, 32);
        if (isJpeg(header)) {
            requireImageSize(file);
            Dimensions dimensions = imageDimensions(file);
            requireSafeDimensions(dimensions);
            return new DetectedMedia(MediaKind.IMAGE, "image/jpeg", ".jpg",
                    dimensions.width(), dimensions.height(), jpegOrientation(file));
        }
        if (isPng(header)) {
            requireImageSize(file);
            Dimensions dimensions = imageDimensions(file);
            requireSafeDimensions(dimensions);
            return new DetectedMedia(MediaKind.IMAGE, "image/png", ".png",
                    dimensions.width(), dimensions.height(), 1);
        }
        if (isWebm(header)) {
            requireVideoSize(file);
            return new DetectedMedia(MediaKind.VIDEO, "video/webm", ".webm", null, null, 1);
        }
        if (isIsoBaseMedia(header)) {
            String brand = ascii(header, 8, Math.min(4, Math.max(0, header.length - 8)));
            boolean quickTime = "qt  ".equals(brand);
            if (!quickTime && !MP4_BRANDS.contains(brand)) {
                throw badRequest("El contenedor ISO-BMFF no corresponde a un video MP4/MOV permitido.");
            }
            requireVideoSize(file);
            return new DetectedMedia(MediaKind.VIDEO,
                    quickTime ? "video/quicktime" : "video/mp4",
                    quickTime ? ".mov" : ".mp4", null, null, 1);
        }

        throw badRequest("Formato no permitido. Usa imágenes JPEG/PNG o videos MP4, MOV o WebM.");
    }

    /**
     * Reescribe la imagen para que solo queden píxeles. Esto elimina EXIF/GPS,
     * perfiles y payloads anexos, y evita publicar SVG u otros formatos activos.
     */
    public NormalizedImageData normalizeImage(MultipartFile file, DetectedMedia detected) throws IOException {
        if (detected.kind() != MediaKind.IMAGE) {
            throw new IllegalArgumentException("El archivo no es una imagen validada.");
        }

        BufferedImage decoded;
        try (InputStream input = file.getInputStream()) {
            decoded = ImageIO.read(input);
        }
        if (decoded == null) {
            throw badRequest("La imagen no se pudo decodificar.");
        }

        BufferedImage oriented = applyOrientation(decoded, detected.orientation());
        requireSafeDimensions(new Dimensions(oriented.getWidth(), oriented.getHeight()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if ("image/png".equals(detected.mimeType())) {
            if (!ImageIO.write(oriented, "png", output)) {
                throw new IOException("No hay encoder PNG disponible");
            }
        } else {
            writeJpeg(oriented, output);
        }
        return new NormalizedImageData(oriented.getWidth(), oriented.getHeight(), output.toByteArray());
    }

    /** Compatibilidad con tests/utilidades locales; la lógica de negocio usa MediaStorage. */
    public NormalizedImage writeNormalizedImage(MultipartFile file, DetectedMedia detected, Path target)
            throws IOException {
        NormalizedImageData normalized = normalizeImage(file, detected);
        Files.createDirectories(target.getParent());
        Files.write(target, normalized.bytes());
        return new NormalizedImage(normalized.width(), normalized.height(), normalized.bytes().length);
    }

    public String normalizeExternalVideoUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw badRequest("Ingresa un enlace de YouTube o Vimeo.");
        }
        String value = rawUrl.trim();
        if (value.length() > 2048) {
            throw badRequest("El enlace de video es demasiado largo.");
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw badRequest("El enlace de video no es válido.");
        }
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        if (!("https".equals(scheme) || "http".equals(scheme)) || host == null || uri.getUserInfo() != null) {
            throw badRequest("Solo se permiten enlaces web de YouTube o Vimeo.");
        }

        if (YOUTUBE_HOSTS.contains(host)) {
            String id = youtubeId(uri, host);
            if (id == null || !YOUTUBE_ID.matcher(id).matches()) {
                throw badRequest("No se pudo identificar el video de YouTube.");
            }
            return "https://www.youtube.com/watch?v=" + id;
        }
        if (VIMEO_HOSTS.contains(host)) {
            String id = vimeoId(uri);
            if (id == null || !VIMEO_ID.matcher(id).matches()) {
                throw badRequest("No se pudo identificar el video de Vimeo.");
            }
            return "https://vimeo.com/" + id;
        }
        throw badRequest("Solo se permiten enlaces de YouTube o Vimeo.");
    }

    private void requireImageSize(MultipartFile file) {
        if (file.getSize() > maxImageBytes) {
            throw payloadTooLarge("Cada imagen puede ocupar como máximo " + humanMiB(maxImageBytes) + " MB.");
        }
    }

    private void requireVideoSize(MultipartFile file) {
        if (file.getSize() > maxVideoBytes) {
            throw payloadTooLarge("Cada video puede ocupar como máximo " + humanMiB(maxVideoBytes) + " MB.");
        }
    }

    private void requireSafeDimensions(Dimensions dimensions) {
        if (dimensions.width() <= 0 || dimensions.height() <= 0
                || dimensions.width() > maxImageWidth || dimensions.height() > maxImageHeight
                || (long) dimensions.width() * dimensions.height() > maxImagePixels) {
            throw badRequest("La imagen tiene dimensiones demasiado grandes.");
        }
    }

    private Dimensions imageDimensions(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream(); ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) throw badRequest("La imagen no se pudo inspeccionar.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw badRequest("Formato de imagen no soportado.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                return new Dimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private int jpegOrientation(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            if (input.read() != 0xFF || input.read() != 0xD8) return 1;
            while (true) {
                int prefix;
                do {
                    prefix = input.read();
                } while (prefix != -1 && prefix != 0xFF);
                if (prefix == -1) return 1;

                int marker;
                do {
                    marker = input.read();
                } while (marker == 0xFF);
                if (marker == -1 || marker == 0xDA || marker == 0xD9) return 1;
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;

                int hi = input.read();
                int lo = input.read();
                if (hi < 0 || lo < 0) return 1;
                int length = (hi << 8) | lo;
                if (length < 2) return 1;
                byte[] segment = input.readNBytes(length - 2);
                if (segment.length != length - 2) return 1;
                if (marker == 0xE1) {
                    int orientation = parseExifOrientation(segment);
                    if (orientation >= 1 && orientation <= 8) return orientation;
                }
            }
        } catch (Exception ignored) {
            return 1;
        }
    }

    private int parseExifOrientation(byte[] segment) {
        byte[] exif = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        if (segment.length < 14) return 1;
        for (int i = 0; i < exif.length; i++) {
            if (segment[i] != exif[i]) return 1;
        }
        int base = 6;
        boolean little;
        if (segment[base] == 'I' && segment[base + 1] == 'I') little = true;
        else if (segment[base] == 'M' && segment[base + 1] == 'M') little = false;
        else return 1;
        if (u16(segment, base + 2, little) != 42) return 1;
        long ifdOffset = u32(segment, base + 4, little);
        long ifd = base + ifdOffset;
        if (ifd < 0 || ifd + 2 > segment.length) return 1;
        int count = u16(segment, (int) ifd, little);
        int entry = (int) ifd + 2;
        for (int i = 0; i < count; i++, entry += 12) {
            if (entry < 0 || entry + 12 > segment.length) return 1;
            int tag = u16(segment, entry, little);
            if (tag == 0x0112) {
                int type = u16(segment, entry + 2, little);
                long values = u32(segment, entry + 4, little);
                if (type == 3 && values >= 1) return u16(segment, entry + 8, little);
                return 1;
            }
        }
        return 1;
    }

    private BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) return source;
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = orientation >= 5 && orientation <= 8;
        int targetWidth = swap ? height : width;
        int targetHeight = swap ? width : height;

        AffineTransform transform = new AffineTransform();
        switch (orientation) {
            case 2 -> {
                transform.scale(-1, 1);
                transform.translate(-width, 0);
            }
            case 3 -> {
                transform.translate(width, height);
                transform.rotate(Math.PI);
            }
            case 4 -> {
                transform.scale(1, -1);
                transform.translate(0, -height);
            }
            case 5 -> {
                transform.rotate(-Math.PI / 2);
                transform.scale(-1, 1);
            }
            case 6 -> {
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
            }
            case 7 -> {
                transform.scale(-1, 1);
                transform.translate(-height, 0);
                transform.translate(0, width);
                transform.rotate(3 * Math.PI / 2);
            }
            case 8 -> {
                transform.translate(0, width);
                transform.rotate(3 * Math.PI / 2);
            }
            default -> { return source; }
        }

        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private void writeJpeg(BufferedImage source, OutputStream target) throws IOException {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No hay encoder JPEG disponible");
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.90f);
            }
            writer.write(null, new IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
    }

    private String youtubeId(URI uri, String host) {
        String path = safePath(uri);
        if ("youtu.be".equals(host)) return firstPathPart(path);
        if (path.equals("/watch")) return queryParameter(uri.getRawQuery(), "v");
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("embed".equals(parts[i]) || "shorts".equals(parts[i]) || "live".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private String vimeoId(URI uri) {
        String[] parts = safePath(uri).split("/");
        for (String part : parts) {
            if (VIMEO_ID.matcher(part).matches()) return part;
        }
        return null;
    }

    private String queryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            if (name.equals(key)) return idx >= 0 ? pair.substring(idx + 1) : "";
        }
        return null;
    }

    private String firstPathPart(String path) {
        for (String part : path.split("/")) {
            if (!part.isBlank()) return part;
        }
        return null;
    }

    private String safePath(URI uri) {
        return uri.getPath() == null ? "" : uri.getPath();
    }

    private byte[] readHeader(MultipartFile file, int max) throws IOException {
        try (InputStream input = file.getInputStream()) {
            return input.readNBytes(max);
        }
    }

    private boolean isJpeg(byte[] header) {
        return header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] header) {
        if (header.length < PNG_SIGNATURE.length) return false;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) if (header[i] != PNG_SIGNATURE[i]) return false;
        return true;
    }

    private boolean isWebm(byte[] header) {
        if (header.length < WEBM_SIGNATURE.length) return false;
        for (int i = 0; i < WEBM_SIGNATURE.length; i++) if (header[i] != WEBM_SIGNATURE[i]) return false;
        return true;
    }

    private boolean isIsoBaseMedia(byte[] header) {
        return header.length >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    }

    private String ascii(byte[] bytes, int offset, int length) {
        if (offset < 0 || length <= 0 || offset + length > bytes.length) return "";
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private int u16(byte[] bytes, int offset, boolean little) {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        int a = bytes[offset] & 0xFF;
        int b = bytes[offset + 1] & 0xFF;
        return little ? a | (b << 8) : (a << 8) | b;
    }

    private long u32(byte[] bytes, int offset, boolean little) {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        long a = bytes[offset] & 0xFFL;
        long b = bytes[offset + 1] & 0xFFL;
        long c = bytes[offset + 2] & 0xFFL;
        long d = bytes[offset + 3] & 0xFFL;
        return little ? a | (b << 8) | (c << 16) | (d << 24)
                : (a << 24) | (b << 16) | (c << 8) | d;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private long humanMiB(long bytes) {
        return Math.max(1, bytes / (1024L * 1024L));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException payloadTooLarge(String message) {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    public enum MediaKind { IMAGE, VIDEO }

    public record DetectedMedia(MediaKind kind, String mimeType, String extension,
                                Integer width, Integer height, int orientation) {}

    public record NormalizedImageData(int width, int height, byte[] bytes) {}

    public record NormalizedImage(int width, int height, long sizeBytes) {}

    private record Dimensions(int width, int height) {}
}
