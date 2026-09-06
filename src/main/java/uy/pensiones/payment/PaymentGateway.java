package uy.pensiones.payment;

import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public interface PaymentGateway {

    PaymentProvider provider();

    PaymentCreationResult createPayment(PaymentCreationRequest request);

    PaymentStatusResult getStatus(PaymentLookupRequest request);

    PaymentStatusResult cancel(PaymentLookupRequest request);

    PaymentStatusResult refund(PaymentRefundRequest request);

    default PaymentDisputeResult getDispute(PaymentDisputeLookupRequest request) {
        throw new UnsupportedOperationException("Consulta de disputas no implementada por el proveedor");
    }

    default boolean supportsDisputeEvidence() { return false; }

    default void submitDisputeEvidence(PaymentDisputeEvidenceRequest request) {
        throw new UnsupportedOperationException("Carga de evidencia de disputas no implementada por el proveedor");
    }

    default ConnectionTestResult testConnection() {
        return new ConnectionTestResult(false, "Prueba de conectividad no implementada por el proveedor");
    }

    record PaymentCreationRequest(
            String merchantReference,
            String idempotencyKey,
            PaymentPurpose purpose,
            Long userId,
            String payerEmail,
            BigDecimal amount,
            String currency,
            String description,
            Map<String, String> metadata
    ) {}

    record PaymentLookupRequest(
            String providerPaymentId,
            String providerSubscriptionId,
            String providerCheckoutId,
            String merchantReference,
            String idempotencyKey
    ) {}

    record PaymentCreationResult(
            String providerPaymentId,
            String providerSubscriptionId,
            String providerCheckoutId,
            String checkoutUrl,
            PaymentStatus status,
            String providerStatus
    ) {}

    record PaymentStatusResult(
            PaymentStatus status,
            String providerStatus,
            String providerPaymentId,
            BigDecimal refundedAmount
    ) {}

    record PaymentRefundRequest(
            PaymentLookupRequest payment,
            BigDecimal amount,
            BigDecimal originalAmount,
            String currency,
            String idempotencyKey
    ) {}

    record PaymentDisputeLookupRequest(
            String providerDisputeId,
            String providerPaymentId
    ) {}

    record PaymentDisputeResult(
            String providerDisputeId,
            List<String> providerPaymentIds,
            BigDecimal amount,
            String currency,
            String reason,
            Boolean coverageEligible,
            Boolean coverageApplied,
            Boolean documentationRequired,
            String documentationStatus,
            OffsetDateTime documentationDeadline,
            OffsetDateTime providerCreatedAt,
            OffsetDateTime providerUpdatedAt,
            Boolean liveMode
    ) {}

    record PaymentDisputeEvidenceFile(
            String filename,
            String contentType,
            long size,
            String sha256,
            byte[] content
    ) {}

    record PaymentDisputeEvidenceRequest(
            String providerDisputeId,
            List<PaymentDisputeEvidenceFile> files
    ) {}

    record ConnectionTestResult(boolean success, String message) {}
}
