package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.model.PaymentRecord;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentGatewayRegistry;
import uy.pensiones.repo.PaymentRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OwnerPaymentRefreshServiceTest {

    private PaymentRepository payments;
    private PaymentGatewayRegistry gateways;
    private PaymentTransactionService transactions;
    private OwnerPaymentQueryService queries;
    private OwnerPaymentRefreshService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        gateways = mock(PaymentGatewayRegistry.class);
        transactions = mock(PaymentTransactionService.class);
        queries = mock(OwnerPaymentQueryService.class);
        service = new OwnerPaymentRefreshService(payments, gateways, transactions, queries);
    }

    @Test
    void pendingPaymentRefreshesProviderImmediatelyAfterCheckoutReturn() {
        PaymentRecord payment = pendingPayment();
        when(payments.findOwnerDetailedById(8L, 7L)).thenReturn(Optional.of(payment));
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateways.requireImplemented(PaymentProvider.MERCADO_PAGO)).thenReturn(gateway);
        var result = new PaymentGateway.PaymentStatusResult(
                PaymentStatus.APPROVED, "processed:accredited", "PAY-8", BigDecimal.ZERO);
        when(gateway.getStatus(any())).thenReturn(result);
        var dto = mock(OwnerPaymentQueryService.OwnerPaymentDTO.class);
        when(queries.detail(7L, 8L)).thenReturn(dto);

        assertEquals(dto, service.refresh(7L, 8L));

        verify(gateway).getStatus(any());
        verify(transactions).applyProviderStatusOwnerRefresh(8L, result);
        verify(transactions, never()).recordOwnerRefreshFailure(anyLong(), any());
    }

    @Test
    void recentProviderSyncIsThrottledAndReturnsLocalState() {
        PaymentRecord payment = pendingPayment();
        payment.setLastProviderSyncAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(payments.findOwnerDetailedById(8L, 7L)).thenReturn(Optional.of(payment));
        var dto = mock(OwnerPaymentQueryService.OwnerPaymentDTO.class);
        when(queries.detail(7L, 8L)).thenReturn(dto);

        assertEquals(dto, service.refresh(7L, 8L));

        verifyNoInteractions(gateways);
        verify(transactions, never()).applyProviderStatusOwnerRefresh(anyLong(), any());
    }

    @Test
    void terminalPaymentNeverCallsProvider() {
        PaymentRecord payment = pendingPayment();
        payment.setStatus(PaymentStatus.APPROVED);
        when(payments.findOwnerDetailedById(8L, 7L)).thenReturn(Optional.of(payment));
        var dto = mock(OwnerPaymentQueryService.OwnerPaymentDTO.class);
        when(queries.detail(7L, 8L)).thenReturn(dto);

        assertEquals(dto, service.refresh(7L, 8L));

        verifyNoInteractions(gateways);
    }

    @Test
    void paymentOwnedByAnotherUserIsNotRefreshable() {
        when(payments.findOwnerDetailedById(8L, 7L)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.refresh(7L, 8L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(gateways);
    }

    @Test
    void providerFailureIsRecordedButPortalStillReceivesLocalState() {
        PaymentRecord payment = pendingPayment();
        when(payments.findOwnerDetailedById(8L, 7L)).thenReturn(Optional.of(payment));
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateways.requireImplemented(PaymentProvider.MERCADO_PAGO)).thenReturn(gateway);
        when(gateway.getStatus(any())).thenThrow(new IllegalStateException("provider temporarily unavailable"));
        var dto = mock(OwnerPaymentQueryService.OwnerPaymentDTO.class);
        when(queries.detail(7L, 8L)).thenReturn(dto);

        assertEquals(dto, service.refresh(7L, 8L));

        verify(transactions).recordOwnerRefreshFailure(eq(8L), any(IllegalStateException.class));
        verify(transactions, never()).applyProviderStatusOwnerRefresh(anyLong(), any());
    }

    private PaymentRecord pendingPayment() {
        return PaymentRecord.builder()
                .id(8L)
                .provider(PaymentProvider.MERCADO_PAGO)
                .purpose(PaymentPurpose.SUBSCRIPTION)
                .status(PaymentStatus.PENDING)
                .providerCheckoutId("ORD-8")
                .merchantReference("pay_8")
                .idempotencyKey("portal:7:8")
                .amount(new BigDecimal("390.00"))
                .currency("UYU")
                .build();
    }
}
