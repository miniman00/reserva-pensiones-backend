package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PensionReportProtectionService {

    private final int maxPerClient;
    private final Duration window;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong checks = new AtomicLong();

    public PensionReportProtectionService(
            @Value("${app.reports.rate-limit.max:5}") int maxPerClient,
            @Value("${app.reports.rate-limit.window:PT1H}") Duration window) {
        this.maxPerClient = Math.max(1, maxPerClient);
        this.window = window == null || window.isZero() || window.isNegative()
                ? Duration.ofHours(1)
                : window;
    }

    public void check(String clientKey) {
        long now = System.currentTimeMillis();
        Bucket bucket = buckets.computeIfAbsent(clientKey, ignored -> new Bucket(now));
        long retryAfter = bucket.tryAcquire(now, maxPerClient, window.toMillis());
        if (retryAfter > 0) {
            throw new InquiryRateLimitExceededException(
                    "Has enviado varios reportes en poco tiempo. Intenta nuevamente más tarde.",
                    retryAfter
            );
        }

        if ((checks.incrementAndGet() & 255L) == 0L) {
            long staleAfter = window.toMillis() * 2L;
            buckets.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > staleAfter);
        }
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
