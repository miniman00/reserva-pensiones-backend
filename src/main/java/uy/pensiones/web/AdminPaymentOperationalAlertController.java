package uy.pensiones.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.service.PaymentOperationalAlertService;

@RestController
@RequestMapping("/api/admin/commercial/payment-alerts")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminPaymentOperationalAlertController {
    private final PaymentOperationalAlertService service;

    public AdminPaymentOperationalAlertController(PaymentOperationalAlertService service) {
        this.service = service;
    }

    @GetMapping
    public PaymentOperationalAlertService.OperationalAlertsDTO get(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return service.get(severity, category, page, size);
    }
}
