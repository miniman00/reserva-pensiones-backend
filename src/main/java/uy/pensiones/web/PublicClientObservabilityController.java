package uy.pensiones.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.config.RequestCorrelationFilter;
import uy.pensiones.observability.ClientObservabilityService;
import uy.pensiones.service.PublicTrafficProtectionService;
import uy.pensiones.web.dto.ClientObservabilityRequest;

@RestController
@RequestMapping("/api/public/observability")
public class PublicClientObservabilityController {

    private final ClientObservabilityService observability;
    private final PublicTrafficProtectionService traffic;

    public PublicClientObservabilityController(ClientObservabilityService observability,
                                               PublicTrafficProtectionService traffic) {
        this.observability = observability;
        this.traffic = traffic;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> events(HttpServletRequest servletRequest,
                                       @Valid @RequestBody ClientObservabilityRequest request) {
        if (!traffic.allowTelemetry(servletRequest, null)) {
            return ResponseEntity.noContent().build();
        }
        observability.record(request, RequestCorrelationFilter.currentRequestId(servletRequest));
        return ResponseEntity.noContent().build();
    }
}
