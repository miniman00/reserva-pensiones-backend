package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.SubscriptionSource;
import uy.pensiones.enums.SubscriptionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminSubscriptionService;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.web.dto.AdminReasonRequest;

@RestController
@RequestMapping("/api/admin/commercial/subscriptions")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminSubscriptionController {

    private final AdminSubscriptionService service;
    private final BackofficeAccessService access;

    public AdminSubscriptionController(AdminSubscriptionService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public Page<AdminSubscriptionService.SubscriptionDTO> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String planCode,
            @RequestParam(required = false) SubscriptionSource source,
            @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(q, planCode, source, status, page, size);
    }

    @GetMapping("/summary")
    public AdminSubscriptionService.SubscriptionSummaryDTO summary() {
        return service.summary();
    }

    @GetMapping("/{subscriptionId}")
    public AdminSubscriptionService.SubscriptionDTO detail(@PathVariable Long subscriptionId) {
        return service.detail(subscriptionId);
    }

    @PostMapping("/grant")
    public AdminSubscriptionService.SubscriptionDTO grant(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody GrantRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.grant(request.userId(), request.planVersionId(), request.durationDays(), request.reason(), actor);
    }

    @PostMapping("/{subscriptionId}/extend")
    public AdminSubscriptionService.SubscriptionDTO extend(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long subscriptionId,
            @Valid @RequestBody ExtendRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.extend(subscriptionId, request.days(), request.reason(), actor);
    }

    @PostMapping("/{subscriptionId}/cancel")
    public AdminSubscriptionService.SubscriptionDTO cancel(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long subscriptionId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.cancel(subscriptionId, request.reason(), actor);
    }

    public record GrantRequest(
            @NotNull Long userId,
            @NotNull Long planVersionId,
            @Min(1) @Max(3650) int durationDays,
            @NotBlank @Size(max = 1500) String reason
    ) {}

    public record ExtendRequest(
            @Min(1) @Max(3650) int days,
            @NotBlank @Size(max = 1500) String reason
    ) {}
}
