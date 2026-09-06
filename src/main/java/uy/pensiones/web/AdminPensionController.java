package uy.pensiones.web;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminPensionService;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.web.dto.AdminReasonRequest;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/pensions")
@PreAuthorize("hasAuthority('ROLE_BACKOFFICE')")
public class AdminPensionController {

    private final AdminPensionService service;
    private final BackofficeAccessService access;

    public AdminPensionController(AdminPensionService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public Page<AdminPensionService.AdminPensionDTO> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) PensionStatus status,
            @RequestParam(required = false) Boolean moderationBlocked,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) Boolean available,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(q, owner, email, city, state, status, moderationBlocked, featured, available,
                createdFrom, createdTo, page, size);
    }
    @GetMapping("/{pensionId}")
    public AdminPensionService.AdminPensionDetailDTO detail(@PathVariable Long pensionId) {
        return service.detail(pensionId);
    }

    @PostMapping("/{pensionId}/block")
    @PreAuthorize("hasAuthority('BACKOFFICE_MODERATION_MANAGE')")
    public AdminPensionService.AdminPensionActionDTO block(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long pensionId,
            @Valid @RequestBody AdminReasonRequest request
    ) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.block(pensionId, request.reason(), actor);
    }

    @PostMapping("/{pensionId}/unblock")
    @PreAuthorize("hasAuthority('BACKOFFICE_MODERATION_MANAGE')")
    public AdminPensionService.AdminPensionActionDTO unblock(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long pensionId,
            @Valid @RequestBody AdminReasonRequest request
    ) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.unblock(pensionId, request.reason(), actor);
    }

}
