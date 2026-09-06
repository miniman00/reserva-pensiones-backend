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
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.LaunchCampaignStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminFounderCampaignService;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.web.dto.AdminReasonRequest;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/admin/commercial/founder-campaign")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminFounderCampaignController {

    private final AdminFounderCampaignService service;
    private final BackofficeAccessService access;

    public AdminFounderCampaignController(AdminFounderCampaignService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public AdminFounderCampaignService.OverviewDTO overview() {
        return service.overview();
    }

    @PutMapping
    public AdminFounderCampaignService.OverviewDTO update(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody ConfigRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.updateConfig(request.toInput(), request.reason(), actor);
    }

    @PostMapping("/activate")
    public AdminFounderCampaignService.OverviewDTO activate(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody AdminReasonRequest request) {
        return service.setStatus(LaunchCampaignStatus.ACTIVE, request.reason(), access.requireAdmin(principal));
    }

    @PostMapping("/pause")
    public AdminFounderCampaignService.OverviewDTO pause(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody AdminReasonRequest request) {
        return service.setStatus(LaunchCampaignStatus.PAUSED, request.reason(), access.requireAdmin(principal));
    }

    @PostMapping("/end")
    public AdminFounderCampaignService.OverviewDTO end(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody AdminReasonRequest request) {
        return service.setStatus(LaunchCampaignStatus.ENDED, request.reason(), access.requireAdmin(principal));
    }

    @GetMapping("/beneficiaries")
    public Page<AdminFounderCampaignService.BeneficiaryDTO> beneficiaries(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) LaunchCampaignBeneficiaryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.beneficiaries(q, status, page, size);
    }

    @PostMapping("/beneficiaries")
    public AdminFounderCampaignService.BeneficiaryDTO grant(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody ManualGrantRequest request) {
        return service.grantManually(request.pensionId(), request.reason(), access.requireAdmin(principal));
    }

    @PostMapping("/beneficiaries/{beneficiaryId}/revoke")
    public AdminFounderCampaignService.BeneficiaryDTO revoke(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long beneficiaryId,
            @Valid @RequestBody AdminReasonRequest request) {
        return service.revoke(beneficiaryId, request.reason(), access.requireAdmin(principal));
    }

    @PostMapping("/beneficiaries/{beneficiaryId}/extend")
    public AdminFounderCampaignService.BeneficiaryDTO extend(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long beneficiaryId,
            @Valid @RequestBody ExtendRequest request) {
        return service.extend(beneficiaryId, request.days(), request.reason(), access.requireAdmin(principal));
    }

    @GetMapping("/events")
    public Page<AdminFounderCampaignService.EventDTO> events(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return service.events(page, size);
    }

    public record ConfigRequest(
            @Min(1) @Max(10_000) int maxBeneficiaries,
            @Min(1) @Max(3_650) int benefitDurationDays,
            @Min(0) @Max(1_000) int maxFeaturedPensions,
            Long benefitPlanVersionId,
            @NotNull OffsetDateTime enrollmentStartsAt,
            OffsetDateTime enrollmentEndsAt,
            boolean showRemainingSlots,
            @NotBlank @Size(max = 1500) String reason
    ) {
        AdminFounderCampaignService.ConfigInput toInput() {
            return new AdminFounderCampaignService.ConfigInput(maxBeneficiaries, benefitDurationDays,
                    maxFeaturedPensions, benefitPlanVersionId, enrollmentStartsAt, enrollmentEndsAt,
                    showRemainingSlots);
        }
    }

    public record ManualGrantRequest(
            @NotNull @Min(1) Long pensionId,
            @NotBlank @Size(max = 1500) String reason
    ) {}

    public record ExtendRequest(
            @Min(1) @Max(3_650) int days,
            @NotBlank @Size(max = 1500) String reason
    ) {}
}
