package uy.pensiones.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.service.BackofficeReauthenticationService;
import uy.pensiones.service.BackofficeStaffService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/staff")
@PreAuthorize("hasAuthority('BACKOFFICE_STAFF_MANAGE')")
public class BackofficeStaffController {

    private final BackofficeStaffService service;
    private final BackofficeAccessService access;
    private final BackofficeReauthenticationService reauthentication;

    public BackofficeStaffController(BackofficeStaffService service, BackofficeAccessService access,
                                     BackofficeReauthenticationService reauthentication) {
        this.service = service;
        this.access = access;
        this.reauthentication = reauthentication;
    }

    @GetMapping
    public List<BackofficeStaffService.StaffUserDTO> list() {
        return service.list();
    }

    @PostMapping
    public BackofficeStaffService.StaffUserDTO create(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @RequestBody BackofficeStaffService.CreateStaffRequest request
    ) {
        reauthentication.requireRecent(principal);
        BackofficeUser actor = access.requireAdmin(principal);
        return service.create(request, actor);
    }

    @PutMapping("/{id}")
    public BackofficeStaffService.StaffUserDTO update(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long id,
            @RequestBody BackofficeStaffService.UpdateStaffRequest request
    ) {
        reauthentication.requireRecent(principal);
        BackofficeUser actor = access.requireAdmin(principal);
        return service.update(id, request, actor);
    }

    @PostMapping("/{id}/unlock")
    public BackofficeStaffService.StaffUserDTO unlock(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long id,
            @RequestBody BackofficeStaffService.UnlockStaffRequest request
    ) {
        reauthentication.requireRecent(principal);
        BackofficeUser actor = access.requireAdmin(principal);
        return service.unlock(id, request, actor);
    }

    @PutMapping("/{id}/mfa/reset")
    public BackofficeStaffService.StaffUserDTO resetMfa(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long id,
            @RequestBody BackofficeStaffService.ResetMfaRequest request
    ) {
        reauthentication.requireRecent(principal);
        BackofficeUser actor = access.requireAdmin(principal);
        return service.resetMfa(id, request, actor);
    }

    @PutMapping("/{id}/password")
    public BackofficeStaffService.StaffUserDTO resetPassword(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long id,
            @RequestBody BackofficeStaffService.ResetPasswordRequest request
    ) {
        reauthentication.requireRecent(principal);
        BackofficeUser actor = access.requireAdmin(principal);
        return service.resetPassword(id, request, actor);
    }
}
