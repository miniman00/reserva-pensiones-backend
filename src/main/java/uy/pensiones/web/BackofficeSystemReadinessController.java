package uy.pensiones.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.service.BackofficeProductionReadinessService;

@RestController
@RequestMapping("/api/admin/system-readiness")
@PreAuthorize("hasAuthority('BACKOFFICE_STAFF_MANAGE')")
public class BackofficeSystemReadinessController {
    private final BackofficeProductionReadinessService service;

    public BackofficeSystemReadinessController(BackofficeProductionReadinessService service) {
        this.service = service;
    }

    @GetMapping
    public BackofficeProductionReadinessService.Readiness get() {
        return service.evaluate();
    }
}
