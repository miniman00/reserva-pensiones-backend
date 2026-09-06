package uy.pensiones.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.repo.PensionInquiryRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PensionInquiryProtectionService {

    private final PensionInquiryRepository inquiries;
    private final int clientMax;
    private final Duration clientWindow;
    private final int pensionMax;
    private final Duration pensionWindow;
    private final int ipMax;
    private final int ipPensionMax;
    private final Duration duplicateWindow;

    private final ConcurrentHashMap<String, WindowBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong checks = new AtomicLong();

    public PensionInquiryProtectionService(
            PensionInquiryRepository inquiries,
            @Value("${app.inquiries.rate-limit.client-max:10}") int clientMax,
            @Value("${app.inquiries.rate-limit.client-window:PT10M}") Duration clientWindow,
            @Value("${app.inquiries.rate-limit.pension-max:3}") int pensionMax,
            @Value("${app.inquiries.rate-limit.pension-window:PT30M}") Duration pensionWindow,
            @Value("${app.inquiries.rate-limit.ip-max:30}") int ipMax,
            @Value("${app.inquiries.rate-limit.ip-pension-max:10}") int ipPensionMax,
            @Value("${app.inquiries.duplicate-window:PT5M}") Duration duplicateWindow) {
        this.inquiries = inquiries;
        this.clientMax = Math.max(1, clientMax);
        this.clientWindow = positive(clientWindow, Duration.ofMinutes(10));
        this.pensionMax = Math.max(1, pensionMax);
        this.pensionWindow = positive(pensionWindow, Duration.ofMinutes(30));
        this.ipMax = Math.max(this.clientMax, ipMax);
        this.ipPensionMax = Math.max(this.pensionMax, ipPensionMax);
        this.duplicateWindow = positive(duplicateWindow, Duration.ofMinutes(5));
    }

    public void checkRateLimit(Long requesterId,
                               String visitorKey,
                               HttpServletRequest request,
                               Long pensionId) {
        long now = System.currentTimeMillis();
        String ipHash = sha256(normalizeAddress(request == null ? null : request.getRemoteAddr()));
        String clientKey = requesterId != null
                ? "user:" + requesterId
                : hasText(visitorKey)
                    ? "visitor:" + sha256(visitorKey.trim())
                    : "anon-ip:" + ipHash;

        enforce("client:all:" + clientKey, clientMax, clientWindow, now,
                "Has enviado varias consultas en poco tiempo. Intenta nuevamente más tarde.");
        enforce("client:pension:" + clientKey + ':' + pensionId, pensionMax, pensionWindow, now,
                "Ya enviaste varias consultas a esta pensión. Espera un poco antes de volver a intentarlo.");

        // Backstop por dirección remota para evitar el bypass cambiando visitorKey.
        // Los límites son más amplios para no castigar redes compartidas/NAT.
        enforce("ip:all:" + ipHash, ipMax, clientWindow, now,
                "Se alcanzó temporalmente el límite de consultas desde esta red. Intenta nuevamente más tarde.");
        enforce("ip:pension:" + ipHash + ':' + pensionId, ipPensionMax, pensionWindow, now,
                "Se alcanzó temporalmente el límite de consultas a esta pensión desde esta red.");

        if ((checks.incrementAndGet() & 255L) == 0L) {
            cleanup(now);
        }
    }

    public boolean isRecentDuplicate(Long pensionId, String email, String phone) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedPhone = normalizePhone(phone);
        if (normalizedEmail == null && normalizedPhone == null) return false;

        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(duplicateWindow);
        List<PensionInquiry> recent = inquiries.findTop20ByPensionIdAndCreatedAtAfterOrderByCreatedAtDesc(
                pensionId, since);

        for (PensionInquiry inquiry : recent) {
            String existingEmail = normalizeEmail(inquiry.getContactEmail());
            String existingPhone = normalizePhone(inquiry.getContactPhone());
            if (normalizedEmail != null && normalizedEmail.equals(existingEmail)) return true;
            if (normalizedPhone != null && normalizedPhone.equals(existingPhone)) return true;
        }
        return false;
    }

    private void enforce(String key, int max, Duration window, long now, String message) {
        WindowBucket bucket = buckets.computeIfAbsent(key, ignored -> new WindowBucket(now));
        long retryAfter = bucket.tryAcquire(now, max, window.toMillis());
        if (retryAfter > 0) {
            throw new InquiryRateLimitExceededException(message, retryAfter);
        }
    }

    private void cleanup(long now) {
        long staleAfter = Math.max(clientWindow.toMillis(), pensionWindow.toMillis()) * 2L;
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMillis > staleAfter);
    }

    private String normalizeAddress(String value) {
        String normalized = value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private String normalizeEmail(String value) {
        if (!hasText(value)) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePhone(String value) {
        if (!hasText(value)) return null;
        String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;

        // Canonicaliza formatos uruguayos equivalentes para que, por ejemplo,
        // +598 99 123 456, 00598 99 123 456 y 099 123 456 se comparen igual.
        // Para otros paises conservamos los digitos originales: sin contexto de
        // pais no es seguro inferir un codigo internacional a partir de un numero local.
        if (digits.startsWith("00598") && digits.length() == 13) {
            return digits.substring(2);
        }
        if (digits.startsWith("598") && digits.length() == 11) {
            return digits;
        }
        if (digits.length() == 9 && digits.startsWith("09")) {
            return "598" + digits.substring(1);
        }
        if (digits.length() == 8) {
            return "598" + digits;
        }
        return digits;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static final class WindowBucket {
        private long windowStartedMillis;
        private int count;
        private volatile long lastSeenMillis;

        private WindowBucket(long now) {
            this.windowStartedMillis = now;
            this.lastSeenMillis = now;
        }

        private synchronized long tryAcquire(long now, int max, long windowMillis) {
            lastSeenMillis = now;
            if (now - windowStartedMillis >= windowMillis) {
                windowStartedMillis = now;
                count = 0;
            }
            if (count >= max) {
                long remainingMillis = Math.max(1L, windowMillis - (now - windowStartedMillis));
                return Math.max(1L, (remainingMillis + 999L) / 1000L);
            }
            count++;
            return 0L;
        }
    }
}
