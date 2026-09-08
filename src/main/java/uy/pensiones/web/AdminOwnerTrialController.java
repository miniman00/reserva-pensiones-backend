package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminOwnerTrialService;
import uy.pensiones.service.BackofficeAccessService;

@RestController
@RequestMapping("/api/admin/commercial/trial")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminOwnerTrialController {

    private final AdminOwnerTrialService service;
    private final BackofficeAccessService access;

    public AdminOwnerTrialController(AdminOwnerTrialService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public AdminOwnerTrialService.TrialSettingsDTO detail() {
        return service.detail();
    }

    @PutMapping
    public AdminOwnerTrialService.TrialSettingsDTO update(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody UpdateTrialSettingsRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.update(request.enabled(), request.durationDays(), request.graceDays(),
                request.trialPlanVersionId(), request.reason(), actor);
    }

    public record UpdateTrialSettingsRequest(
            boolean enabled,
            @Min(1) @Max(3650) int durationDays,
            @Min(0) @Max(90) int graceDays,
            Long trialPlanVersionId,
            @NotBlank @Size(max = 1500) String reason
    ) {}
}
