package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uy.pensiones.enums.ChargebackBenefitDecision;
import uy.pensiones.enums.PaymentChargebackStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.*;

@RestController
@RequestMapping("/api/admin/commercial/chargebacks")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminPaymentChargebackController {
    private final PaymentChargebackService service;
    private final AdminPaymentChargebackService admin;
    private final BackofficeAccessService access;

    public AdminPaymentChargebackController(PaymentChargebackService service, AdminPaymentChargebackService admin, BackofficeAccessService access) {
        this.service=service; this.admin=admin; this.access=access;
    }

    @GetMapping public Page<PaymentChargebackService.ChargebackDTO> list(@RequestParam(required=false) String q,
            @RequestParam(required=false) PaymentChargebackStatus status,@RequestParam(required=false) Boolean documentationRequired,
            @RequestParam(required=false) Boolean reviewRequired,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return service.list(q,status,documentationRequired,reviewRequired,page,size);
    }
    @GetMapping("/summary") public PaymentChargebackService.SummaryDTO summary(){return service.summary();}
    @GetMapping("/{id}") public PaymentChargebackService.ChargebackDTO detail(@PathVariable Long id){return service.detail(id);}
    @PostMapping("/{id}/refresh") public PaymentChargebackService.ChargebackDTO refresh(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id){return admin.refresh(id,access.requireAdmin(principal));}
    @PostMapping(value="/{id}/documentation", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public PaymentChargebackService.ChargebackDTO documentation(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id,
            @RequestParam("files") java.util.List<MultipartFile> files,@RequestParam("reason") String reason){
        return admin.submitDocumentation(id,files,reason,access.requireAdmin(principal));
    }
    @PostMapping("/{id}/benefit-decision") public PaymentChargebackService.ChargebackDTO decision(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id,@Valid @RequestBody BenefitDecisionRequest request){
        BackofficeUser actor=access.requireAdmin(principal); return service.reconcileBenefit(id,request.decision(),request.reason(),actor);
    }
    public record BenefitDecisionRequest(@NotNull ChargebackBenefitDecision decision,@NotBlank @Size(max=1500) String reason){}
}
