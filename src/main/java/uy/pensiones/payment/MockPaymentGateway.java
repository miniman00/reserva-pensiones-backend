package uy.pensiones.payment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.model.MockPaymentState;
import uy.pensiones.repo.MockPaymentStateRepository;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

@Component
public class MockPaymentGateway implements PaymentGateway {

    private final MockPaymentStateRepository states;

    public MockPaymentGateway(MockPaymentStateRepository states) {
        this.states = states;
    }

    @Override
    public PaymentProvider provider() { return PaymentProvider.MOCK; }

    @Override
    @Transactional
    public PaymentCreationResult createPayment(PaymentCreationRequest request) {
        MockPaymentState existing = states.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
        if (existing != null) {
            return new PaymentCreationResult(existing.getProviderPaymentId(), existing.getProviderSubscriptionId(),
                    existing.getProviderPaymentId(), null, existing.getStatus(), existing.getProviderStatus());
        }
        String paymentId = "mock_pay_" + UUID.randomUUID();
        String subscriptionId = request.purpose() == uy.pensiones.enums.PaymentPurpose.SUBSCRIPTION
                ? "mock_sub_" + UUID.randomUUID() : null;
        MockPaymentState state = states.save(MockPaymentState.builder()
                .providerPaymentId(paymentId)
                .idempotencyKey(request.idempotencyKey())
                .providerSubscriptionId(subscriptionId)
                .status(PaymentStatus.PENDING)
                .providerStatus("mock_pending")
                .build());
        return new PaymentCreationResult(state.getProviderPaymentId(), state.getProviderSubscriptionId(),
                state.getProviderPaymentId(), null, state.getStatus(), state.getProviderStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentStatusResult getStatus(PaymentLookupRequest request) {
        MockPaymentState state = find(reference(request));
        return new PaymentStatusResult(state.getStatus(), state.getProviderStatus(), state.getProviderPaymentId(), state.getRefundedAmount());
    }


    @Override
    @Transactional
    public PaymentStatusResult refund(PaymentRefundRequest request) {
        if (request == null || request.payment() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud de devolución inválida");
        MockPaymentState state = find(reference(request.payment()));
        if (state.getStatus() != PaymentStatus.APPROVED && state.getStatus() != PaymentStatus.REFUNDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo un pago aprobado puede reembolsarse en MOCK");
        }
        BigDecimal total = request.originalAmount();
        if (total == null || total.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Importe original inválido");
        BigDecimal current = state.getRefundedAmount() == null ? BigDecimal.ZERO : state.getRefundedAmount();
        BigDecimal remaining = total.subtract(current);
        if (remaining.signum() <= 0) {
            state.setStatus(PaymentStatus.REFUNDED);
            state.setProviderStatus("mock_refunded");
            states.save(state);
            return new PaymentStatusResult(state.getStatus(), state.getProviderStatus(), state.getProviderPaymentId(), current);
        }
        BigDecimal amount = request.amount() == null ? remaining : request.amount();
        if (amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El importe a devolver supera el saldo reembolsable");
        }
        BigDecimal refunded = current.add(amount);
        state.setRefundedAmount(refunded);
        if (refunded.compareTo(total) >= 0) {
            state.setStatus(PaymentStatus.REFUNDED);
            state.setProviderStatus("mock_refunded");
        } else {
            state.setStatus(PaymentStatus.APPROVED);
            state.setProviderStatus("mock_processed:partially_refunded");
        }
        states.save(state);
        return new PaymentStatusResult(state.getStatus(), state.getProviderStatus(), state.getProviderPaymentId(), state.getRefundedAmount());
    }

    @Override
    public ConnectionTestResult testConnection() { return new ConnectionTestResult(true, "Proveedor MOCK operativo"); }

    @Override
    @Transactional
    public PaymentStatusResult cancel(PaymentLookupRequest request) {
        MockPaymentState state = find(reference(request));
        if (state.getStatus() == PaymentStatus.APPROVED || state.getStatus() == PaymentStatus.REFUNDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El mock no permite cancelar un pago ya aprobado; una devolución será un flujo separado");
        }
        if (state.getStatus() == PaymentStatus.CANCELLED) {
            return new PaymentStatusResult(state.getStatus(), state.getProviderStatus(), state.getProviderPaymentId(), state.getRefundedAmount());
        }
        state.setStatus(PaymentStatus.CANCELLED);
        state.setProviderStatus("mock_cancelled");
        states.save(state);
        return new PaymentStatusResult(state.getStatus(), state.getProviderStatus(), state.getProviderPaymentId(), state.getRefundedAmount());
    }

    @Transactional
    public PaymentStatusResult simulate(String providerPaymentId, PaymentStatus status) {
        if (status == null || status == PaymentStatus.CREATED) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado mock no permitido");
        if (status == PaymentStatus.REFUNDED) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La devolución tendrá un flujo específico; no se simula como cambio arbitrario de estado");
        MockPaymentState state = find(providerPaymentId);
        if (state.getStatus().isTerminal() && state.getStatus() != status) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Un estado terminal del proveedor mock no puede reescribirse con otro resultado");
        state.setStatus(status);
        state.setProviderStatus("mock_" + status.name().toLowerCase(Locale.ROOT));
        states.save(state);
        return new PaymentStatusResult(state.getStatus(), state.getProviderStatus(), state.getProviderPaymentId(), state.getRefundedAmount());
    }

    private String reference(PaymentLookupRequest request) {
        if (request == null) return null;
        return request.providerPaymentId() != null ? request.providerPaymentId() : request.providerCheckoutId();
    }

    private MockPaymentState find(String providerPaymentId) {
        if (providerPaymentId == null || providerPaymentId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Referencia del proveedor inválida");
        return states.findById(providerPaymentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "El pago no existe en el proveedor mock"));
    }
}
