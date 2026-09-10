package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/commercial/payment-settings")
@PreAuthorize("hasAuthority('BACKOFFICE_PAYMENT_CONFIG_MANAGE')")
public class AdminPaymentProviderController {
    private final AdminPaymentProviderService service; private final BackofficeAccessService access; private final MercadoPagoReadinessService mercadoPagoReadiness; private final MercadoPagoCertificationService mercadoPagoCertification; private final BackofficeReauthenticationService reauthentication;
    public AdminPaymentProviderController(AdminPaymentProviderService service, BackofficeAccessService access, MercadoPagoReadinessService mercadoPagoReadiness, MercadoPagoCertificationService mercadoPagoCertification, BackofficeReauthenticationService reauthentication){this.service=service;this.access=access;this.mercadoPagoReadiness=mercadoPagoReadiness;this.mercadoPagoCertification=mercadoPagoCertification;this.reauthentication=reauthentication;}

    @GetMapping public AdminPaymentProviderService.SettingsDTO get(){return service.get();}
    @GetMapping("/providers/mercado-pago/readiness") public MercadoPagoReadinessService.ReadinessDTO mercadoPagoReadiness(){return mercadoPagoReadiness.get();}
    @GetMapping("/providers/mercado-pago/certification") public MercadoPagoCertificationService.CertificationDTO mercadoPagoCertification(){return mercadoPagoCertification.get();}
    @PostMapping("/providers/mercado-pago/certification/start") public MercadoPagoCertificationService.CertificationDTO startMercadoPagoCertification(@AuthenticationPrincipal BackofficePrincipal principal,@Valid @RequestBody ReasonRequest r){return mercadoPagoCertification.start(r.reason(),actor(principal));}
    @PutMapping("/providers/mercado-pago/certification/{runId}/checks/{code}") public MercadoPagoCertificationService.CertificationDTO setMercadoPagoCertificationCheck(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long runId,@PathVariable PaymentProviderCertificationCheckCode code,@Valid @RequestBody CertificationCheckRequest r){return mercadoPagoCertification.setManualCheck(runId,code,r.confirmed(),r.reason(),actor(principal));}
    @PostMapping("/providers/mercado-pago/certification/{runId}/validate-pre-live") public MercadoPagoCertificationService.CertificationDTO validateMercadoPagoPreLive(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long runId,@Valid @RequestBody ReasonRequest r){reauthentication.requireRecent(principal);return mercadoPagoCertification.validatePreLive(runId,r.reason(),actor(principal));}
    @PostMapping("/providers/mercado-pago/certification/{runId}/live-smoke-checkout") public AdminPaymentService.PaymentDetailDTO createMercadoPagoLiveSmokeCheckout(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long runId,@Valid @RequestBody CertificationPaymentRequest r){reauthentication.requireRecent(principal);return mercadoPagoCertification.createLiveSmokeCheckout(runId,new PaymentTransactionService.CreateInput(r.purpose(),r.userId(),r.planVersionId(),r.subscriptionPeriodMonths(),r.pensionId(),r.promotionProductVersionId(),r.studyCenterId(),r.idempotencyKey()),r.reason(),actor(principal));}
    @PostMapping("/providers/mercado-pago/certification/{runId}/complete") public MercadoPagoCertificationService.CertificationDTO completeMercadoPagoCertification(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long runId,@Valid @RequestBody ReasonRequest r){reauthentication.requireRecent(principal);return mercadoPagoCertification.complete(runId,r.reason(),actor(principal));}
    @PostMapping("/providers/mercado-pago/certification/{runId}/abort") public MercadoPagoCertificationService.CertificationDTO abortMercadoPagoCertification(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long runId,@Valid @RequestBody ReasonRequest r){return mercadoPagoCertification.abort(runId,r.reason(),actor(principal));}
    @PutMapping public AdminPaymentProviderService.SettingsDTO updateGlobal(@AuthenticationPrincipal BackofficePrincipal principal,@Valid @RequestBody GlobalRequest r){if(r.paymentsEnabled()) reauthentication.requireRecent(principal);return service.updateGlobal(r.paymentsEnabled(),r.defaultProvider(),r.reason(),actor(principal));}
    @PutMapping("/reconciliation") public AdminPaymentProviderService.SettingsDTO updateReconciliation(@AuthenticationPrincipal BackofficePrincipal principal,@Valid @RequestBody ReconciliationRequest r){return service.updateReconciliation(r.enabled(),r.intervalMinutes(),r.batchSize(),r.reason(),actor(principal));}
    @PutMapping("/operational-alerts") public AdminPaymentProviderService.SettingsDTO updateOperationalAlerts(@AuthenticationPrincipal BackofficePrincipal principal,@Valid @RequestBody OperationalAlertsRequest r){return service.updateOperationalAlerts(r.enabled(),r.recipients(),r.minimumSeverity(),r.cooldownMinutes(),r.reason(),actor(principal));}
    @PostMapping("/operational-alerts/test") public AdminPaymentProviderService.AlertEmailTestDTO testOperationalAlerts(@AuthenticationPrincipal BackofficePrincipal principal,@Valid @RequestBody ReasonRequest r){return service.testOperationalAlertEmail(r.reason(),actor(principal));}
    @PutMapping("/providers/{provider}") public AdminPaymentProviderService.ProviderDTO updateProvider(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable PaymentProvider provider,@Valid @RequestBody ProviderRequest r){reauthentication.requireRecent(principal);return service.updateProvider(provider,new AdminPaymentProviderService.ProviderUpdate(r.displayName(),r.enabled(),r.mode(),r.priority(),r.subscriptionsEnabled(),r.promotionsEnabled(),r.supportedCurrencies(),r.supportedCountries(),r.configurationJson(),r.reason()),actor(principal));}
    @PutMapping("/providers/{provider}/credentials/{name}") public AdminPaymentProviderService.ProviderDTO putCredential(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable PaymentProvider provider,@PathVariable String name,@Valid @RequestBody CredentialRequest r){reauthentication.requireRecent(principal);return service.putCredential(provider,name,r.value(),r.reason(),actor(principal));}
    @PostMapping("/providers/{provider}/credentials/{name}/remove") public AdminPaymentProviderService.ProviderDTO removeCredential(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable PaymentProvider provider,@PathVariable String name,@Valid @RequestBody ReasonRequest r){reauthentication.requireRecent(principal);return service.removeCredential(provider,name,r.reason(),actor(principal));}
    @PostMapping("/providers/{provider}/test") public AdminPaymentProviderService.ProviderDTO test(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable PaymentProvider provider){return service.testConnection(provider,actor(principal));}
    @PostMapping("/providers/{provider}/test/{mode}") public AdminPaymentProviderService.ProviderDTO testEnvironment(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable PaymentProvider provider,@PathVariable PaymentProviderMode mode){if(mode==PaymentProviderMode.LIVE) reauthentication.requireRecent(principal);return service.testConnection(provider,mode,actor(principal));}
    private BackofficeUser actor(BackofficePrincipal p){return access.requireAdmin(p);}

    public record GlobalRequest(boolean paymentsEnabled, PaymentProvider defaultProvider,@NotBlank @Size(max=1500) String reason){}
    public record ReconciliationRequest(boolean enabled,@Min(1) @Max(1440) int intervalMinutes,@Min(1) @Max(200) int batchSize,@NotBlank @Size(max=1500) String reason){}
    public record OperationalAlertsRequest(boolean enabled,List<@NotBlank @Email @Size(max=254) String> recipients,@NotNull PaymentAlertSeverity minimumSeverity,@Min(5) @Max(10080) int cooldownMinutes,@NotBlank @Size(max=1500) String reason){}
    public record ProviderRequest(@NotBlank @Size(max=100) String displayName,boolean enabled,@NotNull PaymentProviderMode mode,@Min(1) @Max(10000) int priority,boolean subscriptionsEnabled,boolean promotionsEnabled,List<@Size(min=3,max=3) String> supportedCurrencies,List<@Size(min=2,max=2) String> supportedCountries,@Size(max=10000) String configurationJson,@NotBlank @Size(max=1500) String reason){}
    public record CredentialRequest(@NotBlank @Size(max=10000) String value,@NotBlank @Size(max=1500) String reason){}
    public record CertificationCheckRequest(boolean confirmed,@NotBlank @Size(max=1500) String reason){}
    public record CertificationPaymentRequest(@NotNull PaymentPurpose purpose,Long userId,Long planVersionId,@Min(1) @Max(12) Integer subscriptionPeriodMonths,Long pensionId,Long promotionProductVersionId,Long studyCenterId,@NotBlank @Size(max=120) String idempotencyKey,@NotBlank @Size(max=1500) String reason){}
    public record ReasonRequest(@NotBlank @Size(max=1500) String reason){}
}
