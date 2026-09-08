package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminCommercialPlanService;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.web.dto.AdminReasonRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/commercial")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminCommercialPlanController {

    private final AdminCommercialPlanService service;
    private final BackofficeAccessService access;

    public AdminCommercialPlanController(AdminCommercialPlanService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/config")
    public AdminCommercialPlanService.CommercialConfigDTO config() {
        return service.config();
    }

    @GetMapping("/plans")
    public List<AdminCommercialPlanService.PlanDTO> plans() {
        return service.list();
    }

    @GetMapping("/plans/{planId}")
    public AdminCommercialPlanService.PlanDTO detail(@PathVariable Long planId) {
        return service.detail(planId);
    }

    @PostMapping("/plans")
    public AdminCommercialPlanService.PlanDTO createPlan(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody CreatePlanRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.createPlan(request.code(), request.name(), request.description(), actor);
    }

    @PutMapping("/plans/{planId}")
    public AdminCommercialPlanService.PlanDTO updatePlan(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long planId,
            @Valid @RequestBody UpdatePlanRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.updatePlan(planId, request.name(), request.description(), request.reason(), actor);
    }

    @PostMapping("/plans/{planId}/activate")
    public AdminCommercialPlanService.PlanDTO activatePlan(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long planId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.setPlanActive(planId, true, request.reason(), actor);
    }

    @PostMapping("/plans/{planId}/deactivate")
    public AdminCommercialPlanService.PlanDTO deactivatePlan(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long planId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.setPlanActive(planId, false, request.reason(), actor);
    }

    @PostMapping("/plans/{planId}/versions")
    public AdminCommercialPlanService.PlanVersionDTO createVersion(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long planId,
            @Valid @RequestBody PlanVersionRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.createDraft(planId, request.toInput(), actor);
    }

    @PutMapping("/versions/{versionId}")
    public AdminCommercialPlanService.PlanVersionDTO updateVersion(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long versionId,
            @Valid @RequestBody PlanVersionRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.updateDraft(versionId, request.toInput(), actor);
    }

    @PostMapping("/versions/{versionId}/publish")
    public AdminCommercialPlanService.PlanVersionDTO publishVersion(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long versionId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.publish(versionId, request.reason(), actor);
    }

    @PostMapping("/versions/{versionId}/retire")
    public AdminCommercialPlanService.PlanVersionDTO retireVersion(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long versionId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.retire(versionId, request.reason(), actor);
    }

    public record CreatePlanRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description
    ) {}

    public record UpdatePlanRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank @Size(max = 1500) String reason
    ) {}

    public record PlanPeriodPriceRequest(
            int periodMonths,
            BigDecimal totalPrice,
            boolean enabled
    ) {}

    public record PlanVersionRequest(
            BigDecimal monthlyPrice,
            List<PlanPeriodPriceRequest> periodPrices,
            @NotBlank @Size(max = 3) String currency,
            Integer maxPensions,
            Integer maxCollaborators,
            Integer maxPhotos,
            Integer maxVideos,
            int featuredDays,
            boolean advancedAnalytics,
            boolean inquiryHistory,
            boolean consolidatedAnalytics,
            boolean exportEnabled,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil
    ) {
        AdminCommercialPlanService.VersionInput toInput() {
            List<AdminCommercialPlanService.PeriodPriceInput> prices = periodPrices == null ? null : periodPrices.stream()
                    .map(item -> new AdminCommercialPlanService.PeriodPriceInput(
                            item.periodMonths(), item.totalPrice(), item.enabled()))
                    .toList();
            return new AdminCommercialPlanService.VersionInput(
                    monthlyPrice, prices, currency, maxPensions, maxCollaborators, maxPhotos, maxVideos,
                    featuredDays, advancedAnalytics, inquiryHistory, consolidatedAnalytics,
                    exportEnabled, effectiveFrom, effectiveUntil
            );
        }
    }
}
