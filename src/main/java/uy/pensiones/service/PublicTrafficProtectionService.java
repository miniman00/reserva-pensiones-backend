package uy.pensiones.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Protección de primera línea para endpoints públicos de lectura/telemetría.
 * Es deliberadamente generosa: busca frenar scraping o loops accidentales sin
 * penalizar navegación humana ni redes compartidas. En despliegues multi-instancia
 * debe complementarse con límites en el reverse proxy/WAF.
 */
@Service
public class PublicTrafficProtectionService {

    private final int searchMax;
    private final int mapMax;
    private final int detailMax;
    private final int catalogMax;
    private final int favoriteMax;
    private final int mediaMax;
    private final int telemetryIpMax;
    private final int telemetryVisitorMax;
    private final Duration window;
    private final int maxSearchQueryLength;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong checks = new AtomicLong();

    public PublicTrafficProtectionService(
            @Value("${app.public-traffic.rate-limit.search-max:120}") int searchMax,
            @Value("${app.public-traffic.rate-limit.map-max:60}") int mapMax,
            @Value("${app.public-traffic.rate-limit.detail-max:90}") int detailMax,
            @Value("${app.public-traffic.rate-limit.catalog-max:180}") int catalogMax,
            @Value("${app.public-traffic.rate-limit.favorite-max:120}") int favoriteMax,
            @Value("${app.public-traffic.rate-limit.media-max:1200}") int mediaMax,
            @Value("${app.public-traffic.rate-limit.telemetry-ip-max:600}") int telemetryIpMax,
            @Value("${app.public-traffic.rate-limit.telemetry-visitor-max:300}") int telemetryVisitorMax,
            @Value("${app.public-traffic.rate-limit.window:PT5M}") Duration window,
            @Value("${app.public-traffic.max-search-query-length:4096}") int maxSearchQueryLength) {
        this.searchMax = positive(searchMax, 120);
        this.mapMax = positive(mapMax, 60);
        this.detailMax = positive(detailMax, 90);
        this.catalogMax = positive(catalogMax, 180);
        this.favoriteMax = positive(favoriteMax, 120);
        this.mediaMax = positive(mediaMax, 1200);
        this.telemetryIpMax = positive(telemetryIpMax, 600);
        this.telemetryVisitorMax = positive(telemetryVisitorMax, 300);
        this.window = window == null || window.isZero() || window.isNegative() ? Duration.ofMinutes(5) : window;
        this.maxSearchQueryLength = Math.max(512, maxSearchQueryLength);
    }

    public void checkSearch(HttpServletRequest request, boolean map) {
        String query = request == null ? null : request.getQueryString();
        if (query != null && query.length() > maxSearchQueryLength) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "La búsqueda contiene demasiados criterios. Reduce la cantidad de filtros e intenta nuevamente."
            );
        }
        enforce("search:" + (map ? "map:" : "list:") + networkKey(request),
                map ? mapMax : searchMax,
                "Se realizaron demasiadas búsquedas en poco tiempo. Intenta nuevamente en unos minutos.");
    }

    public void checkDetail(HttpServletRequest request) {
        enforce("detail:" + networkKey(request), detailMax,
                "Se realizaron demasiadas consultas de anuncios en poco tiempo. Intenta nuevamente más tarde.");
    }

    public void checkCatalog(HttpServletRequest request) {
        enforce("catalog:" + networkKey(request), catalogMax,
                "Se realizaron demasiadas consultas al catálogo en poco tiempo. Intenta nuevamente más tarde.");
    }

    public void checkFavorite(HttpServletRequest request, Long userId) {
        if (userId == null) return;
        enforce("favorite:user:" + userId, favoriteMax,
                "Se realizaron demasiados cambios de favoritos en poco tiempo. Intenta nuevamente más tarde.");

        // Una cuenta individual ya queda limitada arriba. El segundo bucket evita
        // automatizar altas/bajas desde muchas cuentas detrás del mismo origen sin
        // castigar redes compartidas: permite hasta 3x el límite por usuario.
        int networkMax = favoriteMax > Integer.MAX_VALUE / 3 ? Integer.MAX_VALUE : favoriteMax * 3;
        enforce("favorite:network:" + networkKey(request), networkMax,
                "Se realizaron demasiados cambios de favoritos desde esta red. Intenta nuevamente más tarde.");
    }

    public boolean allowMedia(HttpServletRequest request) {
        return allow("media:" + networkKey(request), mediaMax);
    }

    /**
     * Para telemetría preferimos descartar silenciosamente sobre devolver 429: una
     * métrica nunca debe romper la navegación principal del visitante.
     */
    public boolean allowTelemetry(HttpServletRequest request, String visitorKey) {
        if (!allow("telemetry:ip:" + networkKey(request), telemetryIpMax)) return false;
        String normalized = visitorKey == null ? "" : visitorKey.trim();
        if (normalized.length() >= 16 && normalized.length() <= 120) {
            return allow("telemetry:visitor:" + sha256(normalized), telemetryVisitorMax);
        }
        return true;
    }

    private void enforce(String key, int max, String message) {
        long retryAfter = acquire(key, max);
        if (retryAfter > 0) {
            throw new InquiryRateLimitExceededException(message, retryAfter);
        }
    }

    private boolean allow(String key, int max) {
        return acquire(key, max) == 0;
    }

    private long acquire(String key, int max) {
        long now = System.currentTimeMillis();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(now));
        long retryAfter = bucket.tryAcquire(now, max, window.toMillis());
        if ((checks.incrementAndGet() & 1023L) == 0L) cleanup(now);
        return retryAfter;
    }

    private void cleanup(long now) {
        long staleAfter = window.toMillis() * 2L;
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > staleAfter);
    }

    private String networkKey(HttpServletRequest request) {
        String address = request == null ? null : request.getRemoteAddr();
        String normalized = address == null ? "unknown" : address.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) normalized = "unknown";
        return sha256(normalized);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private static final class Bucket {
        private long started;
        private int count;
        private volatile long lastSeen;

        private Bucket(long now) {
            this.started = now;
            this.lastSeen = now;
        }

        private synchronized long tryAcquire(long now, int max, long windowMillis) {
            lastSeen = now;
            if (now - started >= windowMillis) {
                started = now;
                count = 0;
            }
            if (count >= max) {
                long remaining = Math.max(1L, windowMillis - (now - started));
                return Math.max(1L, (remaining + 999L) / 1000L);
            }
            count++;
            return 0L;
        }
    }
}
