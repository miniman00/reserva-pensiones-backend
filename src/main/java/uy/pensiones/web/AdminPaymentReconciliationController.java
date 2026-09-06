package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.*;

@RestController
@RequestMapping("/api/admin/commercial/payment-reconciliation")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminPaymentReconciliationController {
    private final PaymentReconciliationStateService state;
    private final PaymentAutomaticReconciliationService reconciler;
    private final BackofficeAccessService access;
    public AdminPaymentReconciliationController(PaymentReconciliationStateService state,
            PaymentAutomaticReconciliationService reconciler, BackofficeAccessService access) {
        this.state=state; this.reconciler=reconciler; this.access=access;
    }
    @GetMapping public PaymentReconciliationStateService.StatusDTO get(){ return state.status(); }
    @PostMapping("/run") public PaymentReconciliationStateService.RunDTO run(@AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody RunRequest request) { return reconciler.triggerManual(actor(principal), request.reason()); }
    private BackofficeUser actor(BackofficePrincipal p){ return access.requireAdmin(p); }
    public record RunRequest(@NotBlank @Size(max=1500) String reason){}
}
