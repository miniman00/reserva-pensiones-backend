package uy.pensiones.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;
import uy.pensiones.enums.PaymentRefundStatus;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.model.PaymentProviderConfig;
import uy.pensiones.model.PaymentSettings;
import uy.pensiones.payment.MercadoPagoPaymentGateway;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.payment.PaymentSecretCrypto;
import uy.pensiones.repo.PaymentChargebackRepository;
import uy.pensiones.repo.PaymentProviderConfigRepository;
import uy.pensiones.repo.PaymentProviderCredentialRepository;
import uy.pensiones.repo.PaymentProviderEventRepository;
import uy.pensiones.repo.PaymentRefundRepository;
import uy.pensiones.repo.PaymentRepository;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class MercadoPagoReadinessService {
    public static final String WEBHOOK_PATH = "/api/public/payment-webhooks/mercado-pago";
    public static final String SUCCESS_PATH = "/payments/success";
    public static final String FAILURE_PATH = "/payments/failure";
    public static final String PENDING_PATH = "/payments/pending";

    private final PaymentRuntimeConfigurationService runtime;
    private final PaymentSecretCrypto crypto;
    private final PaymentProviderConfigRepository providers;
    private final PaymentProviderCredentialRepository credentials;
    private final PaymentRepository payments;
    private final PaymentProviderEventRepository events;
    private final PaymentRefundRepository refunds;
    private final PaymentChargebackRepository chargebacks;
    private final ObjectMapper objectMapper;

    public MercadoPagoReadinessService(PaymentRuntimeConfigurationService runtime,
                                       PaymentSecretCrypto crypto,
                                       PaymentProviderConfigRepository providers,
                                       PaymentProviderCredentialRepository credentials,
                                       PaymentRepository payments,
                                       PaymentProviderEventRepository events,
                                       PaymentRefundRepository refunds,
                                       PaymentChargebackRepository chargebacks,
                                       ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.crypto = crypto;
        this.providers = providers;
        this.credentials = credentials;
        this.payments = payments;
        this.events = events;
        this.refunds = refunds;
        this.chargebacks = chargebacks;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ReadinessDTO get() {
        PaymentSettings global = runtime.settings();
        PaymentProviderConfig provider = providers.findById(PaymentProvider.MERCADO_PAGO)
                .orElseThrow(() -> new IllegalStateException("Falta configuración de MERCADO_PAGO"));

        boolean accessToken = credentials.findByProviderAndCredentialName(PaymentProvider.MERCADO_PAGO,
                MercadoPagoPaymentGateway.ACCESS_TOKEN).isPresent();
        boolean webhookSecret = credentials.findByProviderAndCredentialName(PaymentProvider.MERCADO_PAGO,
                MercadoPagoPaymentGateway.WEBHOOK_SECRET).isPresent();
        String notificationUrl = configurationText(provider, "notificationUrl");
        String successUrl = configurationText(provider, "successUrl");
        String failureUrl = configurationText(provider, "failureUrl");
        String pendingUrl = configurationText(provider, "pendingUrl");

        long totalCheckoutCount = payments.countByProviderAndProviderCheckoutIdIsNotNull(PaymentProvider.MERCADO_PAGO);
        long totalFulfilledApprovedCount = payments.countByProviderAndStatusAndFulfilledAtIsNotNull(
                PaymentProvider.MERCADO_PAGO, PaymentStatus.APPROVED);
        PaymentProviderMode currentMode = provider.getMode();
        long checkoutCount = payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNull(
                PaymentProvider.MERCADO_PAGO, currentMode);
        long fulfilledApprovedCount = payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNull(
                PaymentProvider.MERCADO_PAGO, currentMode, PaymentStatus.APPROVED);
        long processedWebhookCount = events.countByProviderAndProcessedAtIsNotNull(PaymentProvider.MERCADO_PAGO);
        boolean expectedLiveMode = currentMode == PaymentProviderMode.LIVE;
        long currentModeWebhookCount = events.countByProviderAndProcessedAtIsNotNullAndLiveMode(
                PaymentProvider.MERCADO_PAGO, expectedLiveMode);
        long totalAcceptedRefundCount = refunds.countByProviderAndStatus(PaymentProvider.MERCADO_PAGO,
                PaymentRefundStatus.ACCEPTED);
        long acceptedRefundCount = refunds.countByPayment_ProviderAndPayment_ProviderModeAndStatus(
                PaymentProvider.MERCADO_PAGO, currentMode, PaymentRefundStatus.ACCEPTED);
        long totalChargebackCount = chargebacks.countByProvider(PaymentProvider.MERCADO_PAGO);
        long chargebackCount = chargebacks.countByProviderAndLiveMode(PaymentProvider.MERCADO_PAGO, expectedLiveMode);

        List<CheckDTO> checks = new ArrayList<>();
        checks.add(required("INFRASTRUCTURE_ALLOWED", "Infraestructura permite pagos", runtime.infrastructureAllowed(),
                "APP_PAYMENTS_ALLOWED debe estar habilitado para aceptar cobros.", "INFRAESTRUCTURA"));
        checks.add(required("SECRETS_STORAGE_READY", "Cifrado de credenciales disponible", crypto.isReady(),
                "APP_PAYMENT_SECRETS_MASTER_KEY debe estar configurada.", "INFRAESTRUCTURA"));
        checks.add(required("ACCESS_TOKEN", "ACCESS_TOKEN configurado", accessToken,
                "Guardá la credencial ACCESS_TOKEN de Mercado Pago.", "MERCADO_PAGO"));
        checks.add(required("WEBHOOK_SECRET", "WEBHOOK_SECRET configurado", webhookSecret,
                "Guardá la firma secreta de Webhooks de Mercado Pago.", "MERCADO_PAGO"));
        checks.add(required("CONNECTIVITY", "Prueba de conectividad vigente", Boolean.TRUE.equals(provider.getLastConnectivityCheckSuccess()),
                provider.getLastConnectivityCheckMessage() == null ? "Ejecutá Probar sobre Mercado Pago después del último cambio de configuración o credenciales." : provider.getLastConnectivityCheckMessage(),
                "MERCADO_PAGO"));
        checks.add(required("LIVE_MODE", "Proveedor en modo LIVE", provider.getMode() == PaymentProviderMode.LIVE,
                "Para producción, cambiá el proveedor a LIVE solo después de completar las pruebas Sandbox.", "SALIDA_LIVE"));
        checks.add(required("PROVIDER_ENABLED", "Mercado Pago habilitado", provider.isEnabled(),
                "Habilitá Mercado Pago para nuevos pagos.", "SALIDA_LIVE"));
        checks.add(required("DEFAULT_PROVIDER", "Mercado Pago es el proveedor predeterminado", global.getDefaultProvider() == PaymentProvider.MERCADO_PAGO,
                "Definí MERCADO_PAGO como proveedor predeterminado si será el medio principal del lanzamiento.", "SALIDA_LIVE"));
        checks.add(required("DATABASE_PAYMENTS_ENABLED", "Pagos habilitados desde Backoffice", global.isPaymentsEnabled(),
                "Activá pagos en la configuración global cuando llegue el momento del go-live.", "SALIDA_LIVE"));
        checks.add(required("NOTIFICATION_URL", "Webhook público HTTPS configurado", validNotificationUrl(notificationUrl),
                notificationUrl == null || notificationUrl.isBlank()
                        ? "Configurá notificationUrl apuntando al endpoint público de Mercado Pago."
                        : "La URL debe ser HTTPS y terminar en " + WEBHOOK_PATH + ".",
                "MERCADO_PAGO"));
        checks.add(required("SUCCESS_URL", "Retorno aprobado HTTPS configurado", validPortalReturnUrl(successUrl, SUCCESS_PATH),
                returnUrlDetail("successUrl", successUrl, SUCCESS_PATH), "MERCADO_PAGO"));
        checks.add(required("FAILURE_URL", "Retorno rechazado HTTPS configurado", validPortalReturnUrl(failureUrl, FAILURE_PATH),
                returnUrlDetail("failureUrl", failureUrl, FAILURE_PATH), "MERCADO_PAGO"));
        checks.add(required("PENDING_URL", "Retorno pendiente HTTPS configurado", validPortalReturnUrl(pendingUrl, PENDING_PATH),
                returnUrlDetail("pendingUrl", pendingUrl, PENDING_PATH), "MERCADO_PAGO"));
        checks.add(required("UYU_SCOPE", "Moneda UYU admitida", scopeContains(provider.getSupportedCurrencies(), "UYU"),
                "Incluí UYU o dejá el alcance de monedas sin restricciones.", "MERCADO_PAGO"));
        checks.add(required("UY_SCOPE", "País UY admitido", scopeContains(provider.getSupportedCountries(), "UY"),
                "Incluí UY o dejá el alcance de países sin restricciones.", "MERCADO_PAGO"));
        checks.add(required("RECONCILIATION", "Conciliación preventiva habilitada", global.isAutomaticReconciliationEnabled(),
                "Habilitá la conciliación preventiva antes de producción.", "OPERACION"));
        checks.add(required("OPERATIONAL_ALERTS", "Alertas operativas por correo habilitadas",
                global.isOperationalAlertEmailEnabled() && global.getOperationalAlertEmailRecipients() != null
                        && !global.getOperationalAlertEmailRecipients().isBlank(),
                "Configurá destinatarios y habilitá las alertas operativas por correo.", "OPERACION"));

        checks.add(observed("CHECKOUT_OBSERVED", "Checkout Order creado", checkoutCount,
                "Creá una order de prueba y abrí el checkout_url.", "E2E_CORE"));
        checks.add(observed("WEBHOOK_OBSERVED", "Webhook procesado para el modo actual", currentModeWebhookCount,
                expectedLiveMode
                        ? "Todavía no se observó un webhook live_mode=true procesado."
                        : "Todavía no se observó un webhook de prueba/Sandbox procesado.", "E2E_CORE"));
        checks.add(observed("FULFILLMENT_OBSERVED", "Pago aprobado con beneficio aplicado", fulfilledApprovedCount,
                "Completá un pago de prueba y verificá que el fulfillment se aplique.", "E2E_CORE"));
        checks.add(optionalObserved("REFUND_OBSERVED", "Reembolso aceptado observado", acceptedRefundCount,
                "Ejecutá al menos un refund de prueba para validar el recorrido extendido.", "E2E_EXTENDIDO"));
        checks.add(optionalObserved("CHARGEBACK_OBSERVED", "Contracargo observado", chargebackCount,
                "Los contracargos pueden no ser reproducibles inmediatamente en Sandbox; validá al menos la suscripción al tópico y el flujo cuando haya un caso disponible.", "E2E_EXTENDIDO"));
        checks.add(new CheckDTO("WEBHOOK_TOPICS", "Tópicos configurados en Mercado Pago", "MANUAL", false,
                "Verificá manualmente en el panel de Mercado Pago los eventos Order (Mercado Pago) y Contracargos apuntando al endpoint público.", "MERCADO_PAGO"));

        boolean automaticLiveReady = checks.stream().filter(CheckDTO::blockingForLive)
                .allMatch(c -> "PASS".equals(c.status()));
        boolean liveConfigurationReady = checks.stream()
                .filter(CheckDTO::blockingForLive)
                .filter(c -> !"DATABASE_PAYMENTS_ENABLED".equals(c.code()))
                .allMatch(c -> "PASS".equals(c.status()));
        boolean coreE2eObserved = checkoutCount > 0 && currentModeWebhookCount > 0 && fulfilledApprovedCount > 0;
        boolean extendedE2eObserved = coreE2eObserved && acceptedRefundCount > 0 && chargebackCount > 0;
        EvidenceDTO evidence = new EvidenceDTO(totalCheckoutCount, checkoutCount, processedWebhookCount,
                currentModeWebhookCount, totalFulfilledApprovedCount, fulfilledApprovedCount,
                totalAcceptedRefundCount, acceptedRefundCount, totalChargebackCount, chargebackCount,
                provider.getLastWebhookAt());

        String overallStatus;
        if (automaticLiveReady && extendedE2eObserved) overallStatus = "AUTOMATIC_LIVE_READY_AND_FULL_E2E_OBSERVED";
        else if (automaticLiveReady && coreE2eObserved) overallStatus = "AUTOMATIC_LIVE_READY_CORE_E2E_OBSERVED";
        else if (automaticLiveReady) overallStatus = "AUTOMATIC_LIVE_READY_E2E_PENDING";
        else if (coreE2eObserved) overallStatus = "CORE_E2E_OBSERVED_LIVE_CONFIG_PENDING";
        else overallStatus = "PREPARATION_IN_PROGRESS";

        return new ReadinessDTO(overallStatus, automaticLiveReady, liveConfigurationReady, coreE2eObserved,
                extendedE2eObserved, provider.getMode(), OffsetDateTime.now(ZoneOffset.UTC), evidence, List.copyOf(checks));
    }

    private CheckDTO required(String code, String label, boolean pass, String failureDetail, String category) {
        return new CheckDTO(code, label, pass ? "PASS" : "FAIL", true,
                pass ? "Correcto." : failureDetail, category);
    }

    private CheckDTO observed(String code, String label, long count, String pendingDetail, String category) {
        return new CheckDTO(code, label, count > 0 ? "PASS" : "PENDING", false,
                count > 0 ? "Evidencia observada: " + count + "." : pendingDetail, category);
    }

    private CheckDTO optionalObserved(String code, String label, long count, String pendingDetail, String category) {
        return new CheckDTO(code, label, count > 0 ? "PASS" : "WARNING", false,
                count > 0 ? "Evidencia observada: " + count + "." : pendingDetail, category);
    }

    private String configurationText(PaymentProviderConfig provider, String key) {
        if (provider.getConfigurationJson() == null || provider.getConfigurationJson().isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(provider.getConfigurationJson());
            JsonNode value = node.get(key);
            if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) return null;
            return value.asText().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean validNotificationUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getPath() != null && uri.getPath().endsWith(WEBHOOK_PATH);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean validPortalReturnUrl(String value, String expectedPath) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null
                    && uri.getPath() != null && uri.getPath().endsWith(expectedPath)
                    && !hasQueryParameter(uri.getRawQuery(), "paymentId");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String returnUrlDetail(String key, String value, String expectedPath) {
        if (value == null || value.isBlank()) return "Configurá " + key + " con la URL HTTPS pública del Portal terminada en " + expectedPath + ".";
        return "La URL debe ser HTTPS, terminar en " + expectedPath + " y no incluir paymentId; el backend agrega ese parámetro por checkout.";
    }

    private boolean hasQueryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) return false;
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            try {
                if (name.equals(java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8))) return true;
            } catch (IllegalArgumentException ignored) {
                return true;
            }
        }
        return false;
    }

    private boolean scopeContains(String csv, String expected) {
        if (csv == null || csv.isBlank()) return true;
        return Arrays.stream(csv.split(",")).map(String::trim).map(x -> x.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }

    public record ReadinessDTO(String overallStatus, boolean automaticLiveReady, boolean liveConfigurationReady,
                               boolean coreE2eObserved, boolean extendedE2eObserved,
                               PaymentProviderMode mode, OffsetDateTime generatedAt,
                               EvidenceDTO evidence, List<CheckDTO> checks) {}

    public record EvidenceDTO(long checkoutsCreated, long checkoutsCreatedCurrentMode,
                              long processedWebhooks, long processedWebhooksCurrentMode,
                              long approvedFulfilledPayments, long approvedFulfilledPaymentsCurrentMode,
                              long acceptedRefunds, long acceptedRefundsCurrentMode,
                              long chargebacks, long chargebacksCurrentMode,
                              OffsetDateTime lastWebhookAt) {}

    public record CheckDTO(String code, String label, String status, boolean blockingForLive,
                           String detail, String category) {}
}
