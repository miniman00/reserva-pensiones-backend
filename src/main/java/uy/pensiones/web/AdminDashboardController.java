package uy.pensiones.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.service.AdminDashboardService;

@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasAuthority('ROLE_BACKOFFICE')")
public class AdminDashboardController {

    private final AdminDashboardService service;

    public AdminDashboardController(AdminDashboardService service) {
        this.service = service;
    }

    @GetMapping
    public AdminDashboardService.AdminDashboardDTO get() {
        return service.get();
    }
}
