package uy.pensiones.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.service.OwnerEntitlementService;

@RestController
@RequestMapping("/api/admin/commercial/entitlements")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminEntitlementController {

    private final OwnerEntitlementService entitlements;

    public AdminEntitlementController(OwnerEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    @GetMapping("/users/{userId}")
    public OwnerEntitlementService.EntitlementSnapshot user(@PathVariable Long userId) {
        return entitlements.resolve(userId);
    }
}
