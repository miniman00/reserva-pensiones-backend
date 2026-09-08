package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.model.PaymentRecord;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentGatewayRegistry;
import uy.pensiones.repo.PaymentRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class OwnerPaymentRefreshService {
    private static final Logger log = LoggerFactory.getLogger(OwnerPaymentRefreshService.class);
    private static final Duration MIN_PROVIDER_REFRESH_INTERVAL = Duration.ofSeconds(5);

    private final PaymentRepository payments;
    private final PaymentGatewayRegistry gateways;
    private final PaymentTransactionService transactions;
    private final OwnerPaymentQueryService queries;

    public OwnerPaymentRefreshService(PaymentRepository payments,
                                      PaymentGatewayRegistry gateways,
                                      PaymentTransactionService transactions,
                                      OwnerPaymentQueryService queries) {
        this.payments = payments;
        this.gateways = gateways;
        this.transactions = transactions;
        this.queries = queries;
    }

    public OwnerPaymentQueryService.OwnerPaymentDTO refresh(Long userId, Long paymentId) {
        RefreshTarget target = target(userId, paymentId);
        if (!target.refreshProvider()) {
            return queries.detail(userId, paymentId);
        }

        try {
            PaymentGateway gateway = gateways.requireImplemented(target.payment().getProvider());
            PaymentGateway.PaymentStatusResult result = gateway.getStatus(target.lookup());
            transactions.applyProviderStatusOwnerRefresh(target.payment().getId(), result);
        } catch (RuntimeException error) {
            transactions.recordOwnerRefreshFailure(target.payment().getId(), error);
            log.warn("owner_payment_provider_refresh_failed paymentId={} provider={} error={}",
                    target.payment().getId(), target.payment().getProvider(), safeMessage(error));
        }
        return queries.detail(userId, paymentId);
    }

    @Transactional(readOnly = true)
    protected RefreshTarget target(Long userId, Long paymentId) {
        Long ownerId = requireId(userId);
        Long id = requireId(paymentId);
        PaymentRecord payment = payments.findOwnerDetailedById(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no encontrado"));

        PaymentStatus status = payment.getStatus();
        if (status == null || status.isTerminal()) {
            return new RefreshTarget(payment, null, false);
        }
        if (payment.getProviderCheckoutId() == null && payment.getProviderPaymentId() == null) {
            return new RefreshTarget(payment, null, false);
        }

        OffsetDateTime lastAttempt = latest(payment.getLastReconciliationAttemptAt(), payment.getLastProviderSyncAt());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (lastAttempt != null && lastAttempt.plus(MIN_PROVIDER_REFRESH_INTERVAL).isAfter(now)) {
            return new RefreshTarget(payment, null, false);
        }

        PaymentGateway.PaymentLookupRequest lookup = new PaymentGateway.PaymentLookupRequest(
                payment.getProviderPaymentId(), payment.getProviderSubscriptionId(), payment.getProviderCheckoutId(),
                payment.getMerchantReference(), payment.getIdempotencyKey() + ":owner-refresh");
        return new RefreshTarget(payment, lookup, true);
    }

    private OffsetDateTime latest(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private Long requireId(Long value) {
        if (value == null || value <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador inválido");
        return value;
    }

    private String safeMessage(RuntimeException error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return error == null ? "unknown" : error.getClass().getSimpleName();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    protected record RefreshTarget(PaymentRecord payment, PaymentGateway.PaymentLookupRequest lookup, boolean refreshProvider) {}
}
