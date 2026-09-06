package uy.pensiones.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;
import uy.pensiones.model.PaymentProviderConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MercadoPagoWebhookSignatureTest {
    private static final String SECRET = "webhook-secret-for-tests";
    private PaymentRuntimeConfigurationService runtime;
    private MercadoPagoPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        runtime = mock(PaymentRuntimeConfigurationService.class);
        when(runtime.decryptedCredentials(PaymentProvider.MERCADO_PAGO))
                .thenReturn(Map.of(MercadoPagoPaymentGateway.ACCESS_TOKEN, "access-token-for-tests",
                        MercadoPagoPaymentGateway.WEBHOOK_SECRET, SECRET));
        gateway = new MercadoPagoPaymentGateway(runtime, new ObjectMapper());
    }

    @Test
    void acceptsOrdersSignaturePreservingUppercaseDataIdAsCurrentMercadoPagoSdkDoes() throws Exception {
        String dataId = "ORD01JY8ABCDEF123";
        String requestId = "req-123";
        String ts = String.valueOf(System.currentTimeMillis());
        String signature = signature(dataId, requestId, ts);

        assertThat(gateway.verifyWebhookSignature(dataId, requestId, signature, 60_000)).isTrue();
    }

    @Test
    void acceptsEpochSecondsTimestampUsedByOfficialMercadoPagoSdk() throws Exception {
        String dataId = "ORD01SECONDS";
        String requestId = "req-seconds";
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);

        assertThat(gateway.verifyWebhookSignature(dataId, requestId,
                signature(dataId, requestId, ts), 60_000)).isTrue();
    }

    @Test
    void acceptsCaseInsensitiveSignatureHeaderKeys() throws Exception {
        String dataId = "ORD01HEADER";
        String requestId = "req-header";
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String signed = signature(dataId, requestId, ts).replace("ts=", "TS=").replace("v1=", "V1=");

        assertThat(gateway.verifyWebhookSignature(dataId, requestId, signed, 60_000)).isTrue();
    }

    @Test
    void appendsInternalPaymentIdToConfiguredReturnUrl() {
        assertThat(gateway.appendInternalPaymentId("https://portal.example.com/payments/success?source=mp",
                Map.of("internalPaymentId", "42")))
                .isEqualTo("https://portal.example.com/payments/success?source=mp&paymentId=42");
    }

    @Test
    void rejectsLiveConfigurationWithoutCompletePortalReturnUrls() {
        when(runtime.provider(PaymentProvider.MERCADO_PAGO)).thenReturn(PaymentProviderConfig.builder()
                .provider(PaymentProvider.MERCADO_PAGO)
                .mode(PaymentProviderMode.LIVE)
                .configurationJson("{\"notificationUrl\":\"https://api.example.com/api/public/payment-webhooks/mercado-pago\",\"successUrl\":\"https://portal.example.com/payments/success\"}")
                .build());

        assertThatThrownBy(gateway::validateLocalConfiguration)
                .hasMessageContaining("requiere successUrl, failureUrl y pendingUrl");
    }

    @Test
    void keepsNumericDataIdUnchanged() throws Exception {
        String dataId = "123456789";
        String requestId = "req-456";
        String ts = String.valueOf(System.currentTimeMillis());

        assertThat(gateway.verifyWebhookSignature(dataId, requestId, signature(dataId, requestId, ts), 60_000)).isTrue();
    }

    @Test
    void rejectsSignatureBuiltWithDifferentCaseForAlphanumericDataId() throws Exception {
        String dataId = "ORD01ABC";
        String requestId = "req-789";
        String ts = String.valueOf(System.currentTimeMillis());

        assertThat(gateway.verifyWebhookSignature(dataId, requestId,
                signature(dataId.toLowerCase(java.util.Locale.ROOT), requestId, ts), 60_000)).isFalse();
    }

    @Test
    void rejectsStaleSignedWebhook() throws Exception {
        String dataId = "ORD01STALE";
        String requestId = "req-stale";
        String ts = String.valueOf(System.currentTimeMillis() - 120_000);

        assertThat(gateway.verifyWebhookSignature(dataId, requestId,
                signature(dataId, requestId, ts), 60_000)).isFalse();
    }

    private String signature(String dataIdForManifest, String requestId, String ts) throws Exception {
        String manifest = "id:" + dataIdForManifest + ";request-id:" + requestId + ";ts:" + ts + ";";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hash = HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        return "ts=" + ts + ",v1=" + hash;
    }
}
