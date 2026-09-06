package uy.pensiones.web;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.service.StudyCenterCatalogService;
import uy.pensiones.web.dto.AdminReasonRequest;
import uy.pensiones.web.dto.StudyCenterCatalogUpdateRequest;

@RestController
@RequestMapping("/api/admin/study-centers")
@PreAuthorize("hasAuthority('BACKOFFICE_CATALOG_MANAGE')")
public class AdminStudyCenterController {

    private final StudyCenterCatalogService service;
    private final BackofficeAccessService access;

    public AdminStudyCenterController(StudyCenterCatalogService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public Page<StudyCenterCatalogService.AdminStudyCenterDTO> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) Boolean geolocated,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.adminList(q, active, verified, geolocated, page, size);
    }

    @GetMapping("/summary")
    public StudyCenterCatalogService.AdminStudyCenterSummaryDTO summary() {
        return service.adminSummary();
    }

    @GetMapping("/{id}")
    public StudyCenterCatalogService.AdminStudyCenterDetailDTO detail(@PathVariable Long id) {
        return service.adminDetail(id);
    }

    @PostMapping
    public StudyCenterCatalogService.AdminStudyCenterDTO create(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody StudyCenterCatalogUpdateRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.create(request, actor);
    }

    @PutMapping("/{id}")
    public StudyCenterCatalogService.AdminStudyCenterDTO update(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody StudyCenterCatalogUpdateRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.update(id, request, actor);
    }

    @PostMapping("/{id}/activate")
    public StudyCenterCatalogService.AdminStudyCenterDTO activate(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.activate(id, request.reason(), actor);
    }

    @PostMapping("/{id}/deactivate")
    public StudyCenterCatalogService.AdminStudyCenterDTO deactivate(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.deactivate(id, request.reason(), actor);
    }
}
