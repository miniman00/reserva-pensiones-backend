package uy.pensiones.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MercadoPagoProviderErrorMessageTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MercadoPagoPaymentGateway gateway = new MercadoPagoPaymentGateway(
            mock(PaymentRuntimeConfigurationService.class), objectMapper);

    @Test
    void keepsOrdersApiDetailsWhenTopLevelMessageIsGeneric() throws Exception {
        var body = objectMapper.readTree("""
                {
                  "code": "unsupported_properties",
                  "message": "Properties not supported",
                  "details": [
                    {"field": "config.online.some_field", "message": "Property is not supported"}
                  ]
                }
                """);

        assertThat(gateway.providerErrorMessage(body, 400))
                .isEqualTo("unsupported_properties: Properties not supported | detalle: config.online.some_field: Property is not supported");
    }

    @Test
    void supportsTextualDetailsReturnedByProvider() throws Exception {
        var body = objectMapper.readTree("""
                {
                  "code": "unsupported_properties",
                  "message": "Properties not supported",
                  "details": "config.online.some_field"
                }
                """);

        assertThat(gateway.providerErrorMessage(body, 400))
                .contains("detalle: config.online.some_field");
    }

    @Test
    void findsUnsupportedPropertyInsideNestedDetails() throws Exception {
        var body = objectMapper.readTree("""
                {
                  "code": "unsupported_properties",
                  "message": "Properties not supported",
                  "details": [
                    {
                      "code": "unsupported_properties",
                      "message": "Properties not supported",
                      "details": ["items.0.total_amount"]
                    }
                  ]
                }
                """);

        assertThat(gateway.providerErrorMessage(body, 400))
                .contains("detalle: items.0.total_amount");
    }

}
