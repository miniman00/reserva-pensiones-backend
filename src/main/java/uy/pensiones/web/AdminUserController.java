package uy.pensiones.web;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminUserService;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.web.dto.AdminReasonRequest;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAuthority('BACKOFFICE_USER_MANAGE')")
public class AdminUserController {

    private final AdminUserService service;
    private final BackofficeAccessService access;

    public AdminUserController(AdminUserService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public Page<AdminUserService.AdminUserDTO> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Boolean suspended,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(q, role, suspended, page, size);
    }

    @GetMapping("/summary")
    public AdminUserService.AdminUserSummaryDTO summary() {
        return service.summary();
    }

    @GetMapping("/{userId}")
    public AdminUserService.AdminUserDetailDTO detail(@PathVariable Long userId) {
        return service.detail(userId);
    }

    @PutMapping("/{userId}/role")
    public AdminUserService.AdminUserDTO changeRole(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long userId,
            @RequestBody ChangeRoleRequest request
    ) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.changeRole(userId, request.role(), request.reason(), actor);
    }

    @PostMapping("/{userId}/suspend")
    public AdminUserService.AdminUserDTO suspend(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long userId,
            @RequestBody SuspendUserRequest request
    ) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.suspend(userId, request.reason(), actor);
    }

    @PostMapping("/{userId}/reactivate")
    public AdminUserService.AdminUserDTO reactivate(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long userId,
            @RequestBody AdminReasonRequest request
    ) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.reactivate(userId, request.reason(), actor);
    }

    public record ChangeRoleRequest(UserRole role, String reason) {}
    public record SuspendUserRequest(String reason) {}
}
