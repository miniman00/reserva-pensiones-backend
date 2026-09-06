package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.enums.PromotionExposureEventType;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.repo.PensionPromotionRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromotionExposureAnalyticsServiceTest {

    private final PensionPromotionRepository promotions = mock(PensionPromotionRepository.class);
    private final PromotionExposureAnalyticsService service = new PromotionExposureAnalyticsService(promotions);

    @Test
    void clickAlsoEnsuresAnImpressionAndDeduplicationIsDelegatedToDatabase() {
        when(promotions.findEffectiveAttribution(eq(9L), eq(4L), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(new PensionPromotion()));

        service.record(9L, 4L, PromotionExposureEventType.CLICK,
                "visitor-12345678901234567890", "Mozilla/5.0");

        verify(promotions).insertExposureEvent(eq(9L), eq(4L), eq("IMPRESSION"),
                anyString(), any(LocalDate.class), any(OffsetDateTime.class));
        verify(promotions).insertExposureEvent(eq(9L), eq(4L), eq("CLICK"),
                anyString(), any(LocalDate.class), any(OffsetDateTime.class));
    }

    @Test
    void ignoresKnownCrawlerUserAgents() {
        service.record(9L, 4L, PromotionExposureEventType.IMPRESSION,
                "visitor-12345678901234567890", "Googlebot/2.1");

        verifyNoInteractions(promotions);
    }

    @Test
    void invalidOrExpiredPromotionDoesNotCreateMetrics() {
        when(promotions.findEffectiveAttribution(eq(9L), eq(4L), any(OffsetDateTime.class)))
                .thenReturn(Optional.empty());

        service.record(9L, 4L, PromotionExposureEventType.IMPRESSION,
                "visitor-12345678901234567890", "Mozilla/5.0");

        verify(promotions, never()).insertExposureEvent(anyLong(), anyLong(), anyString(),
                anyString(), any(LocalDate.class), any(OffsetDateTime.class));
    }
}
