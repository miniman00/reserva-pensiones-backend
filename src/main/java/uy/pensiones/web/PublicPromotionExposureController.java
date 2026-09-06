package uy.pensiones.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.service.PromotionExposureAnalyticsService;
import uy.pensiones.service.PublicTrafficProtectionService;
import uy.pensiones.web.dto.PromotionExposureEventRequest;

@RestController
@RequestMapping("/api/public/promotions")
public class PublicPromotionExposureController {

    private final PromotionExposureAnalyticsService analytics;
    private final PublicTrafficProtectionService traffic;

    public PublicPromotionExposureController(PromotionExposureAnalyticsService analytics,
                                             PublicTrafficProtectionService traffic) {
        this.analytics = analytics;
        this.traffic = traffic;
    }

    @PostMapping("/{promotionId}/events")
    public ResponseEntity<Void> record(@PathVariable Long promotionId,
                                       HttpServletRequest servletRequest,
                                       @Valid @RequestBody PromotionExposureEventRequest request) {
        if (!traffic.allowTelemetry(servletRequest, request.visitorKey())) {
            return ResponseEntity.noContent().build();
        }
        analytics.record(
                promotionId,
                request.pensionId(),
                request.eventType(),
                request.visitorKey(),
                servletRequest.getHeader("User-Agent")
        );
        return ResponseEntity.noContent().build();
    }
}
