package uy.pensiones.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Defensa en memoria contra password spraying y ráfagas de login.
 * Se combina con el lockout persistente por cuenta de BackofficeAuthenticationService.
 * Nunca confía directamente en X-Forwarded-For: con forward-headers-strategy=framework,
 * getRemoteAddr() ya representa el cliente resuelto por la infraestructura confiable.
 */
@Service
public class BackofficeLoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(BackofficeLoginRateLimiter.class);
    private static final String GENERIC_LIMIT_MESSAGE =
            "No se pudo iniciar sesión. Verifica tus credenciales o inténtalo nuevamente más tarde.";

    private final int ipMaxAttempts;
    private final Duration ipWindow;
    private final int usernameMaxAttempts;
    private final Duration usernameWindow;
    private final ConcurrentHashMap<String, AttemptWindow> ipBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AttemptWindow> usernameBuckets = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    public BackofficeLoginRateLimiter(
            @Value("${app.backoffice.login-rate-limit.ip-max-attempts:30}") int ipMaxAttempts,
            @Value("${app.backoffice.login-rate-limit.ip-window:PT10M}") Duration ipWindow,
            @Value("${app.backoffice.login-rate-limit.username-max-attempts:10}") int usernameMaxAttempts,
            @Value("${app.backoffice.login-rate-limit.username-window:PT15M}") Duration usernameWindow
    ) {
        this.ipMaxAttempts = positive(ipMaxAttempts, "ip-max-attempts");
        this.ipWindow = positive(ipWindow, "ip-window");
        this.usernameMaxAttempts = positive(usernameMaxAttempts, "username-max-attempts");
        this.usernameWindow = positive(usernameWindow, "username-window");
    }

    public void checkAndRecord(HttpServletRequest request, String rawUsername) {
        Instant now = Instant.now();
        String clientKey = fingerprint("ip:" + clientAddress(request));
        boolean ipAllowed = consume(ipBuckets, clientKey, ipMaxAttempts, ipWindow, now);

        // Si la IP ya quedó bloqueada no creamos buckets ilimitados para usernames arbitrarios.
        String usernameKey = fingerprint("username:" + normalizeUsername(rawUsername));
        boolean usernameAllowed = ipAllowed
                && consume(usernameBuckets, usernameKey, usernameMaxAttempts, usernameWindow, now);

        if ((operations.incrementAndGet() & 127L) == 0L) {
            cleanup(ipBuckets, ipWindow, now);
            cleanup(usernameBuckets, usernameWindow, now);
        }

        if (!ipAllowed || !usernameAllowed) {
            log.warn("Backoffice login throttled client={} username={}", shortFingerprint(clientKey), shortFingerprint(usernameKey));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, GENERIC_LIMIT_MESSAGE);
        }
    }

    private boolean consume(ConcurrentHashMap<String, AttemptWindow> buckets,
                            String key,
                            int maxAttempts,
                            Duration window,
                            Instant now) {
        AtomicBoolean allowed = new AtomicBoolean(true);
        buckets.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.startedAt().plus(window))) {
                return new AttemptWindow(now, 1);
            }
            int next = current.attempts() + 1;
            if (next > maxAttempts) allowed.set(false);
            return new AttemptWindow(current.startedAt(), next);
        });
        return allowed.get();
    }

    private void cleanup(ConcurrentHashMap<String, AttemptWindow> buckets, Duration window, Instant now) {
        buckets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().startedAt().plus(window)));
    }

    private String clientAddress(HttpServletRequest request) {
        if (request == null || request.getRemoteAddr() == null || request.getRemoteAddr().isBlank()) return "unknown";
        return request.getRemoteAddr().trim();
    }

    private String normalizeUsername(String raw) {
        if (raw == null) return "";
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 no está disponible", ex);
        }
    }

    private String shortFingerprint(String value) {
        return value == null || value.length() <= 12 ? value : value.substring(0, 12);
    }

    private int positive(int value, String property) {
        if (value <= 0 || value > 10000) {
            throw new IllegalStateException("app.backoffice.login-rate-limit." + property + " debe estar entre 1 y 10000");
        }
        return value;
    }

    private Duration positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalStateException("app.backoffice.login-rate-limit." + property + " debe ser mayor a 0 y no superar 24 horas");
        }
        return value;
    }

    private record AttemptWindow(Instant startedAt, int attempts) {}
}
