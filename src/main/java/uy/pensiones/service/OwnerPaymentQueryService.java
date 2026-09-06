package uy.pensiones.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.model.PaymentRecord;
import uy.pensiones.model.Pension;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.PromotionProduct;
import uy.pensiones.model.PromotionProductVersion;
import uy.pensiones.model.StudyCenterCatalog;
import uy.pensiones.repo.PaymentRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
public class OwnerPaymentQueryService {
    private static final int MAX_PAGE_SIZE = 50;

    private final PaymentRepository payments;

    public OwnerPaymentQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public Page<OwnerPaymentDTO> list(Long userId, int page, int size) {
        Long ownerId = requireId(userId);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return payments.findOwnerPayments(ownerId, PageRequest.of(safePage, safeSize)).map(this::dto);
    }

    @Transactional(readOnly = true)
    public OwnerPaymentDTO detail(Long userId, Long paymentId) {
        PaymentRecord payment = payments.findOwnerDetailedById(requireId(paymentId), requireId(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no encontrado"));
        return dto(payment);
    }

    private OwnerPaymentDTO dto(PaymentRecord payment) {
        PaymentStatus status = payment.getStatus();
        String checkoutUrl = canResumeCheckout(status, payment.getCheckoutUrl()) ? payment.getCheckoutUrl() : null;
        boolean benefitApplied = payment.getFulfilledAt() != null;
        boolean fulfillmentPending = status == PaymentStatus.APPROVED && !benefitApplied;

        return new OwnerPaymentDTO(
                payment.getId(),
                payment.getPurpose(),
                status,
                payment.getAmount(),
                payment.getCurrency(),
                checkoutUrl,
                checkoutUrl != null,
                canRetry(status, checkoutUrl),
                status != null && status.isTerminal(),
                publicStatusMessage(status, benefitApplied),
                purchase(payment),
                benefitApplied,
                fulfillmentPending,
                payment.getFulfilledAt(),
                payment.getApprovedAt(),
                payment.getRejectedAt(),
                payment.getCancelledAt(),
                payment.getRefundedAt(),
                payment.getExpiredAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }

    private PurchaseDTO purchase(PaymentRecord payment) {
        PlanVersion planVersion = payment.getPlanVersion();
        Plan plan = planVersion == null ? null : planVersion.getPlan();
        Pension pension = payment.getPension();
        PromotionProductVersion promotionVersion = payment.getPromotionProductVersion();
        PromotionProduct product = promotionVersion == null ? null : promotionVersion.getProduct();
        StudyCenterCatalog studyCenter = payment.getStudyCenter();

        return new PurchaseDTO(
                planVersion == null ? null : planVersion.getId(),
                plan == null ? null : plan.getCode(),
                plan == null ? null : plan.getName(),
                planVersion == null ? null : planVersion.getVersion(),
                payment.getSubscriptionPeriodMonths(),
                pension == null ? null : pension.getId(),
                pension == null ? null : pension.getName(),
                promotionVersion == null ? null : promotionVersion.getId(),
                product == null ? null : product.getCode(),
                product == null ? null : product.getName(),
                product == null ? null : product.getTargetType(),
                studyCenter == null ? null : studyCenter.getId(),
                studyCenter == null ? null : studyCenter.getName()
        );
    }

    private boolean canResumeCheckout(PaymentStatus status, String checkoutUrl) {
        if (checkoutUrl == null || checkoutUrl.isBlank()) return false;
        return status == PaymentStatus.CREATED || status == PaymentStatus.PENDING;
    }

    private boolean canRetry(PaymentStatus status, String checkoutUrl) {
        if (status == PaymentStatus.REJECTED || status == PaymentStatus.CANCELLED || status == PaymentStatus.EXPIRED) {
            return true;
        }
        return status == PaymentStatus.CREATED && checkoutUrl == null;
    }

    private String publicStatusMessage(PaymentStatus status, boolean benefitApplied) {
        if (status == null) return "El estado del pago todavía no está disponible.";
        return switch (status) {
            case CREATED -> "El intento de pago fue creado.";
            case PENDING -> "El pago está pendiente de confirmación.";
            case APPROVED -> benefitApplied
                    ? "El pago fue aprobado y el beneficio ya fue aplicado."
                    : "El pago fue aprobado. El beneficio todavía está pendiente de activación.";
            case REJECTED -> "El pago fue rechazado.";
            case CANCELLED -> "El pago fue cancelado.";
            case REFUNDED -> "El pago fue reembolsado.";
            case EXPIRED -> "El intento de pago venció.";
        };
    }

    private Long requireId(Long value) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador inválido");
        }
        return value;
    }

    public record OwnerPaymentDTO(
            Long paymentId,
            PaymentPurpose purpose,
            PaymentStatus status,
            BigDecimal amount,
            String currency,
            String checkoutUrl,
            boolean canResumeCheckout,
            boolean canRetry,
            boolean terminal,
            String statusMessage,
            PurchaseDTO purchase,
            boolean benefitApplied,
            boolean fulfillmentPending,
            OffsetDateTime fulfilledAt,
            OffsetDateTime approvedAt,
            OffsetDateTime rejectedAt,
            OffsetDateTime cancelledAt,
            OffsetDateTime refundedAt,
            OffsetDateTime expiredAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record PurchaseDTO(
            Long planVersionId,
            String planCode,
            String planName,
            Integer planVersion,
            Integer subscriptionPeriodMonths,
            Long pensionId,
            String pensionName,
            Long promotionProductVersionId,
            String promotionProductCode,
            String promotionProductName,
            PromotionTargetType promotionTargetType,
            Long studyCenterId,
            String studyCenterName
    ) {}
}
