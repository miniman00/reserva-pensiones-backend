package uy.pensiones.web;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.enums.PensionReportResolution;
import uy.pensiones.enums.PensionReportStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.service.PensionReportService;
import uy.pensiones.web.dto.AdminReasonRequest;
import uy.pensiones.web.dto.PensionReportDTO;
import uy.pensiones.web.dto.PensionReportReviewRequest;

@RestController
@RequestMapping("/api/admin/reports")
@PreAuthorize("hasAuthority('BACKOFFICE_MODERATION_MANAGE')")
public class AdminPensionReportController {

    private final PensionReportService service;
    private final BackofficeAccessService access;

    public AdminPensionReportController(PensionReportService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public Page<PensionReportDTO> list(@RequestParam(required = false) PensionReportStatus status,
                                       @RequestParam(required = false) Long pensionId,
                                       @RequestParam(required = false) PensionReportReason reason,
                                       @RequestParam(required = false) PensionReportResolution resolution,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.list(status, pensionId, reason, resolution, page, size);
    }

    @GetMapping("/{reportId}")
    public PensionReportService.AdminReportDetailDTO detail(@PathVariable Long reportId) {
        return service.detail(reportId);
    }

    @PutMapping("/{reportId}")
    public PensionReportDTO review(@AuthenticationPrincipal BackofficePrincipal principal,
                                   @PathVariable Long reportId,
                                   @Valid @RequestBody PensionReportReviewRequest request) {
        BackofficeUser admin = access.requireAdmin(principal);
        return service.review(reportId, request, admin);
    }

    @PostMapping("/{reportId}/warn-owner")
    public PensionReportDTO warnOwner(@AuthenticationPrincipal BackofficePrincipal principal,
                                      @PathVariable Long reportId,
                                      @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser admin = access.requireAdmin(principal);
        return service.warnOwner(reportId, request.reason(), admin);
    }

    @PostMapping("/{reportId}/pause-pension")
    public PensionReportDTO pause(@AuthenticationPrincipal BackofficePrincipal principal,
                                  @PathVariable Long reportId,
                                  @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser admin = access.requireAdmin(principal);
        return service.pausePension(reportId, request.reason(), admin);
    }

    @PostMapping("/{reportId}/confirm-violation")
    public PensionReportDTO confirmViolation(@AuthenticationPrincipal BackofficePrincipal principal,
                                             @PathVariable Long reportId,
                                             @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser admin = access.requireAdmin(principal);
        return service.confirmViolation(reportId, request.reason(), admin);
    }

    @PostMapping("/{reportId}/reactivate-owner")
    public PensionReportDTO reactivateOwner(@AuthenticationPrincipal BackofficePrincipal principal,
                                            @PathVariable Long reportId,
                                            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser admin = access.requireAdmin(principal);
        return service.reactivateOwner(reportId, request.reason(), admin);
    }

    @PostMapping("/{reportId}/release-pension")
    public PensionReportDTO release(@AuthenticationPrincipal BackofficePrincipal principal,
                                    @PathVariable Long reportId,
                                    @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser admin = access.requireAdmin(principal);
        return service.releasePension(reportId, request.reason(), admin);
    }
}
