package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.*;
import uy.pensiones.web.dto.AdminReasonRequest;

@RestController
@RequestMapping("/api/admin/commercial/payments")
@PreAuthorize("hasAuthority('BACKOFFICE_COMMERCIAL_MANAGE')")
public class AdminPaymentController {
    private final AdminPaymentService service; private final BackofficeAccessService access; private final BackofficeReauthenticationService reauthentication;
    public AdminPaymentController(AdminPaymentService service,BackofficeAccessService access,BackofficeReauthenticationService reauthentication){this.service=service;this.access=access;this.reauthentication=reauthentication;}
    @GetMapping("/config") public AdminPaymentService.PaymentConfigDTO config(){return service.config();}
    @GetMapping("/summary") public AdminPaymentService.PaymentSummaryDTO summary(){return service.summary();}
    @GetMapping public Page<AdminPaymentService.PaymentDTO> list(@RequestParam(required=false) String q,@RequestParam(required=false) PaymentProvider provider,@RequestParam(required=false) PaymentPurpose purpose,@RequestParam(required=false) PaymentStatus status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(q,provider,purpose,status,page,size);}
    @GetMapping("/{id}") public AdminPaymentService.PaymentDetailDTO detail(@PathVariable Long id){return service.detail(id);}
    @PostMapping("/mock") public AdminPaymentService.PaymentDetailDTO createMock(@AuthenticationPrincipal BackofficePrincipal principal,@Valid @RequestBody MockPaymentRequest r){BackofficeUser a=access.requireAdmin(principal);return service.createMock(new PaymentTransactionService.CreateInput(r.purpose(),r.userId(),r.planVersionId(),r.subscriptionPeriodMonths(),r.pensionId(),r.promotionProductVersionId(),r.studyCenterId(),r.idempotencyKey()),a);}
    @PostMapping("/test-checkout") public AdminPaymentService.PaymentDetailDTO createTestCheckout(@AuthenticationPrincipal BackofficePrincipal principal,@Valid @RequestBody ProviderTestPaymentRequest r){BackofficeUser a=access.requireAdmin(principal);return service.createTestCheckout(r.provider(),new PaymentTransactionService.CreateInput(r.purpose(),r.userId(),r.planVersionId(),r.subscriptionPeriodMonths(),r.pensionId(),r.promotionProductVersionId(),r.studyCenterId(),r.idempotencyKey()),a);}
    @PostMapping("/{id}/mock/status") public AdminPaymentService.PaymentDetailDTO simulate(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id,@Valid @RequestBody MockStatusRequest r){return service.simulate(id,r.status(),access.requireAdmin(principal));}
    @PostMapping("/{id}/refresh") public AdminPaymentService.PaymentDetailDTO refresh(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id){return service.refresh(id,access.requireAdmin(principal));}
    @PostMapping("/{id}/cancel") public AdminPaymentService.PaymentDetailDTO cancel(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id,@Valid @RequestBody AdminReasonRequest r){return service.cancel(id,r.reason(),access.requireAdmin(principal));}
    @PostMapping("/{id}/refunds") public AdminPaymentService.PaymentDetailDTO refund(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id,@Valid @RequestBody RefundRequest r){reauthentication.requireRecent(principal);return service.refund(id,r.amount(),r.idempotencyKey(),r.reason(),access.requireAdmin(principal));}
    @PostMapping("/{id}/refunds/{refundId}/retry") public AdminPaymentService.PaymentDetailDTO retryRefund(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id,@PathVariable Long refundId){reauthentication.requireRecent(principal);return service.retryRefund(id,refundId,access.requireAdmin(principal));}
    @PostMapping("/{id}/refund-benefit-decision") public AdminPaymentService.PaymentDetailDTO reconcileRefundBenefit(@AuthenticationPrincipal BackofficePrincipal principal,@PathVariable Long id,@Valid @RequestBody RefundBenefitRequest r){return service.reconcileRefundBenefit(id,r.decision(),r.reason(),access.requireAdmin(principal));}
    public record MockPaymentRequest(@NotNull PaymentPurpose purpose,Long userId,Long planVersionId,@Min(1) @Max(12) Integer subscriptionPeriodMonths,Long pensionId,Long promotionProductVersionId,Long studyCenterId,@NotBlank @Size(max=120) String idempotencyKey){}
    public record ProviderTestPaymentRequest(@NotNull PaymentProvider provider,@NotNull PaymentPurpose purpose,Long userId,Long planVersionId,@Min(1) @Max(12) Integer subscriptionPeriodMonths,Long pensionId,Long promotionProductVersionId,Long studyCenterId,@NotBlank @Size(max=120) String idempotencyKey){}
    public record MockStatusRequest(@NotNull PaymentStatus status){}
    public record RefundRequest(@DecimalMin(value="0.01") java.math.BigDecimal amount,@NotBlank @Size(max=1500) String reason,@NotBlank @Size(max=120) String idempotencyKey){}
    public record RefundBenefitRequest(@NotNull RefundBenefitDecision decision,@NotBlank @Size(max=1500) String reason){}
}
