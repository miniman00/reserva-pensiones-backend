package uy.pensiones.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.service.PensionViewService;
import uy.pensiones.service.PublicTrafficProtectionService;

@RestController
@RequestMapping("/api/public/pensions")
public class PublicPensionViewController {

    private final PensionViewService views;
    private final PublicTrafficProtectionService traffic;

    public PublicPensionViewController(PensionViewService views, PublicTrafficProtectionService traffic) {
        this.views = views;
        this.traffic = traffic;
    }

    @PostMapping("/{id}/views")
    public ResponseEntity<Void> record(@PathVariable Long id,
                                       HttpServletRequest servletRequest,
                                       @RequestBody(required = false) ViewRequest request) {
        String visitorKey = request == null ? null : request.visitorKey();
        if (!traffic.allowTelemetry(servletRequest, visitorKey)) {
            return ResponseEntity.noContent().build();
        }
        views.record(id, visitorKey);
        return ResponseEntity.noContent().build();
    }

    public record ViewRequest(String visitorKey) {}
}
