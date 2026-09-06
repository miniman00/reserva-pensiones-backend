package uy.pensiones.web;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.AdminPaymentWebhookService;
import uy.pensiones.service.BackofficeAccessService;
import uy.pensiones.service.BackofficeReauthenticationService;
import uy.pensiones.web.dto.AdminReasonRequest;

@RestController
@RequestMapping("/api/admin/commercial/payment-webhooks")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminPaymentWebhookController {
    private final AdminPaymentWebhookService service;
    private final BackofficeAccessService access;
    private final BackofficeReauthenticationService reauthentication;

    public AdminPaymentWebhookController(AdminPaymentWebhookService service, BackofficeAccessService access,
                                         BackofficeReauthenticationService reauthentication) {
        this.service = service;
        this.access = access;
        this.reauthentication = reauthentication;
    }

    @GetMapping
    public Page<AdminPaymentWebhookService.EventDTO> list(@RequestParam(required = false) String q,
                                                          @RequestParam(required = false) PaymentProvider provider,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String eventType,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "30") int size) {
        return service.list(q, provider, status, eventType, page, size);
    }

    @GetMapping("/summary")
    public AdminPaymentWebhookService.SummaryDTO summary() {
        return service.summary();
    }

    @GetMapping("/{id}")
    public AdminPaymentWebhookService.EventDetailDTO detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/replay")
    public AdminPaymentWebhookService.EventDetailDTO replay(@AuthenticationPrincipal BackofficePrincipal principal,
                                                             @PathVariable Long id,
                                                             @Valid @RequestBody AdminReasonRequest request) {
        reauthentication.requireRecent(principal);
        BackofficeUser actor = access.requireAdmin(principal);
        return service.replay(id, request.reason(), actor);
    }
}
