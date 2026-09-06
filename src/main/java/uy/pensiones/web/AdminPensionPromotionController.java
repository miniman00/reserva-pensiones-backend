package uy.pensiones.web;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.PensionPromotionEffectiveStatus;
import uy.pensiones.enums.PensionPromotionSource;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminPensionPromotionService;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.web.dto.AdminReasonRequest;

@RestController
@RequestMapping("/api/admin/commercial/promotions")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminPensionPromotionController {

    private final AdminPensionPromotionService service;
    private final BackofficeAccessService access;

    public AdminPensionPromotionController(AdminPensionPromotionService service,
                                           BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public Page<AdminPensionPromotionService.PromotionSummaryDTO> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long pensionId,
            @RequestParam(required = false) PromotionTargetType targetType,
            @RequestParam(required = false) PensionPromotionSource source,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) Long studyCenterId,
            @RequestParam(required = false) PensionPromotionEffectiveStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(q, pensionId, targetType, source, productCode, studyCenterId, status, page, size);
    }

    @GetMapping("/summary")
    public AdminPensionPromotionService.PromotionDashboardDTO summary() {
        return service.summary();
    }

    @GetMapping("/{promotionId}")
    public AdminPensionPromotionService.PromotionDetailDTO detail(@PathVariable Long promotionId) {
        return service.detail(promotionId);
    }

    @PostMapping("/grant")
    public AdminPensionPromotionService.PromotionDetailDTO grant(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @RequestBody AdminPensionPromotionService.GrantInput input) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.grant(input, actor);
    }

    @PostMapping("/{promotionId}/cancel")
    public AdminPensionPromotionService.PromotionDetailDTO cancel(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long promotionId,
            @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.cancel(promotionId, request.reason(), actor);
    }
}
