package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminPromotionProductService;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.web.dto.AdminReasonRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/commercial/promotion-products")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminPromotionProductController {

    private final AdminPromotionProductService service;
    private final BackofficeAccessService access;

    public AdminPromotionProductController(AdminPromotionProductService service, BackofficeAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public List<AdminPromotionProductService.PromotionProductDTO> list() {
        return service.list();
    }

    @GetMapping("/{productId}")
    public AdminPromotionProductService.PromotionProductDTO detail(@PathVariable Long productId) {
        return service.detail(productId);
    }

    @PostMapping
    public AdminPromotionProductService.PromotionProductDTO create(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody CreatePromotionProductRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.createProduct(request.code(), request.name(), request.description(), request.targetType(),
                request.durationDays(), actor);
    }

    @PutMapping("/{productId}")
    public AdminPromotionProductService.PromotionProductDTO update(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long productId,
            @Valid @RequestBody UpdatePromotionProductRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.updateProduct(productId, request.name(), request.description(), request.reason(), actor);
    }

    @PostMapping("/{productId}/activate")
    public AdminPromotionProductService.PromotionProductDTO activate(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long productId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.setProductActive(productId, true, request.reason(), actor);
    }

    @PostMapping("/{productId}/deactivate")
    public AdminPromotionProductService.PromotionProductDTO deactivate(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long productId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.setProductActive(productId, false, request.reason(), actor);
    }

    @PostMapping("/{productId}/versions")
    public AdminPromotionProductService.PromotionProductVersionDTO createVersion(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long productId,
            @Valid @RequestBody PromotionProductVersionRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.createDraft(productId, request.toInput(), actor);
    }

    @PutMapping("/versions/{versionId}")
    public AdminPromotionProductService.PromotionProductVersionDTO updateVersion(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long versionId,
            @Valid @RequestBody PromotionProductVersionRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.updateDraft(versionId, request.toInput(), actor);
    }

    @PostMapping("/versions/{versionId}/publish")
    public AdminPromotionProductService.PromotionProductVersionDTO publishVersion(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long versionId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.publish(versionId, request.reason(), actor);
    }

    @PostMapping("/versions/{versionId}/retire")
    public AdminPromotionProductService.PromotionProductVersionDTO retireVersion(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @PathVariable Long versionId,
            @Valid @RequestBody AdminReasonRequest request) {
        BackofficeUser actor = access.requireAdmin(principal);
        return service.retire(versionId, request.reason(), actor);
    }

    public record CreatePromotionProductRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 700) String description,
            @NotNull PromotionTargetType targetType,
            @NotNull @Min(1) @Max(3650) Integer durationDays
    ) {}

    public record UpdatePromotionProductRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 700) String description,
            @NotBlank @Size(max = 1500) String reason
    ) {}

    public record PromotionProductVersionRequest(
            BigDecimal price,
            @NotBlank @Size(max = 3) String currency,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil
    ) {
        AdminPromotionProductService.VersionInput toInput() {
            return new AdminPromotionProductService.VersionInput(price, currency, effectiveFrom, effectiveUntil);
        }
    }
}
