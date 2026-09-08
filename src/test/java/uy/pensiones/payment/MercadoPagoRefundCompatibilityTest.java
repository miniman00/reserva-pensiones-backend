package uy.pensiones.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MercadoPagoRefundCompatibilityTest {
    private final MercadoPagoPaymentGateway gateway = new MercadoPagoPaymentGateway(
            mock(PaymentRuntimeConfigurationService.class), new ObjectMapper());

    @Test
    void buildsExplicitTransactionPayloadForFullRefundFallback() {
        var lookup = new PaymentGateway.PaymentLookupRequest(
                "PAY01TEST", null, "ORDTST01TEST", "pay_test", "lookup-key");
        var request = new PaymentGateway.PaymentRefundRequest(
                lookup, null, new BigDecimal("390.00"), "UYU", "refund-key");

        var body = gateway.refundTransactionBody(request, request.originalAmount(), true);

        assertThat(body.path("transactions").isArray()).isTrue();
        assertThat(body.path("transactions").get(0).path("id").asText()).isEqualTo("PAY01TEST");
        assertThat(body.path("transactions").get(0).path("amount").asText()).isEqualTo("390.00");
    }

    @Test
    void fallbackIdempotencyKeyIsStableAndFitsMercadoPagoLimit() {
        String first = gateway.fullRefundFallbackIdempotencyKey("refund-key");
        String second = gateway.fullRefundFallbackIdempotencyKey("refund-key");

        assertThat(first).isEqualTo(second);
        assertThat(first.length()).isLessThanOrEqualTo(64);
    }
}
