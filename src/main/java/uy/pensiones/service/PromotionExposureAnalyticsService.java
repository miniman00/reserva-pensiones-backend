package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.PromotionExposureEventType;
import uy.pensiones.repo.PensionPromotionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class PromotionExposureAnalyticsService {

    private final PensionPromotionRepository promotions;

    public PromotionExposureAnalyticsService(PensionPromotionRepository promotions) {
        this.promotions = promotions;
    }

    @Transactional
    public void record(Long promotionId, Long pensionId, PromotionExposureEventType eventType,
                       String visitorKey, String userAgent) {
        if (promotionId == null || promotionId <= 0 || pensionId == null || pensionId <= 0 || eventType == null) return;
        if (likelyCrawler(userAgent)) return;

        String normalizedVisitor = visitorKey == null ? "" : visitorKey.trim();
        if (normalizedVisitor.length() < 16 || normalizedVisitor.length() > 120) return;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (promotions.findEffectiveAttribution(promotionId, pensionId, now).isEmpty()) return;

        String visitorHash = sha256(normalizedVisitor);
        LocalDate day = now.toLocalDate();
        if (eventType == PromotionExposureEventType.CLICK) {
            // Todo click implica al menos una impresión. Esto mantiene el embudo consistente
            // incluso si IntersectionObserver no está disponible o el request de impresión falló.
            insert(promotionId, pensionId, PromotionExposureEventType.IMPRESSION, visitorHash, day, now);
        }
        insert(promotionId, pensionId, eventType, visitorHash, day, now);
    }

    private void insert(Long promotionId, Long pensionId, PromotionExposureEventType eventType,
                        String visitorHash, LocalDate day, OffsetDateTime now) {
        promotions.insertExposureEvent(promotionId, pensionId, eventType.name(), visitorHash, day, now);
    }

    private boolean likelyCrawler(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return false;
        String value = userAgent.toLowerCase(Locale.ROOT);
        return value.contains("bot") || value.contains("crawler") || value.contains("spider")
                || value.contains("slurp") || value.contains("facebookexternalhit")
                || value.contains("whatsapp") || value.contains("bingpreview");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
