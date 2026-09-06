package uy.pensiones.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderEventAttemptStatus;
import uy.pensiones.enums.PaymentProviderMode;
import uy.pensiones.model.PaymentProviderEvent;
import uy.pensiones.model.PaymentRecord;
import uy.pensiones.payment.MercadoPagoPaymentGateway;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PaymentProviderEventRepository;
import uy.pensiones.repo.PaymentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

@Service
public class MercadoPagoWebhookService {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final long SIGNATURE_TOLERANCE_MILLIS = 15 * 60 * 1000L;

    private final MercadoPagoPaymentGateway gateway;
    private final PaymentRuntimeConfigurationService runtime;
    private final PaymentProviderEventRepository events;
    private final PaymentRepository payments;
    private final PaymentTransactionService tx;
    private final PaymentChargebackService chargebacks;
    private final PaymentProviderConfigWriter configWriter;
    private final PaymentProviderEventAttemptService attemptService;
    private final ObjectMapper objectMapper;

    public MercadoPagoWebhookService(MercadoPagoPaymentGateway gateway, PaymentRuntimeConfigurationService runtime,
                                     PaymentProviderEventRepository events, PaymentRepository payments,
                                     PaymentTransactionService tx, PaymentChargebackService chargebacks,
                                     PaymentProviderConfigWriter configWriter,
                                     PaymentProviderEventAttemptService attemptService,
                                     ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.runtime = runtime;
        this.events = events;
        this.payments = payments;
        this.tx = tx;
        this.chargebacks = chargebacks;
        this.configWriter = configWriter;
        this.attemptService = attemptService;
        this.objectMapper = objectMapper;
    }

    public WebhookResult process(String queryDataId, String queryType, String signature, String requestId, String rawBody) {
        byte[] bytes = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Webhook demasiado grande");
        JsonNode body = parse(rawBody);
        String bodyDataId = text(body.path("data"), "id");
        String resourceId = firstNonBlank(queryDataId, bodyDataId);
        if (resourceId == null) throw bad("Webhook de Mercado Pago sin data.id");
        if (queryDataId != null && bodyDataId != null && !queryDataId.equals(bodyDataId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "data.id inconsistente");
        }
        if (!gateway.verifyWebhookSignature(resourceId, requestId, signature, SIGNATURE_TOLERANCE_MILLIS)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Firma de Mercado Pago inválida");
        }

        String type = firstNonBlank(queryType, text(body, "type"));
        String action = eventAction(body);
        String eventId = text(body, "id");
        if (eventId == null) eventId = sha256(firstNonBlank(requestId, "") + "|" + resourceId + "|" + firstNonBlank(action, ""));
        Boolean liveMode = body.has("live_mode") && !body.get("live_mode").isNull() ? body.get("live_mode").asBoolean() : null;
        validateEnvironment(liveMode);

        PaymentProviderEvent event = loadOrCreate(eventId, sha256(rawBody == null ? "" : rawBody), type, action, resourceId, requestId, liveMode);
        Long attemptId = attemptService.startWebhookAttempt(event.getId());
        if (event.getProcessedAt() != null) {
            attemptService.finishWebhookAttempt(attemptId, PaymentProviderEventAttemptStatus.DUPLICATE,
                    "Evento ya procesado; entrega duplicada ignorada", null);
            return new WebhookResult(true, true, "Evento ya procesado");
        }

        try {
            WebhookResult result = processStoredEvent(event);
            attemptService.finishWebhookAttempt(attemptId, PaymentProviderEventAttemptStatus.SUCCEEDED, result.message(), null);
            return result;
        } catch (RuntimeException error) {
            attemptService.finishWebhookAttempt(attemptId, PaymentProviderEventAttemptStatus.FAILED, null, safeMessage(error));
            throw error;
        }
    }

    /**
     * Replay administrativo de un evento ya autenticado y persistido. No reinyecta el payload original:
     * vuelve a consultar al proveedor usando resource_id y aplica el estado actual.
     */
    public WebhookResult replayStoredEvent(Long eventId) {
        PaymentProviderEvent event = events.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook no encontrado"));
        if (event.getProvider() != PaymentProvider.MERCADO_PAGO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El evento no pertenece a Mercado Pago");
        }
        if (event.getProcessedAt() != null) return new WebhookResult(true, true, "Evento ya procesado");
        validateEnvironment(event.getLiveMode());
        return processStoredEvent(event);
    }

    private WebhookResult processStoredEvent(PaymentProviderEvent event) {
        String type = event.getEventType();
        String action = event.getEventAction();
        String resourceId = event.getResourceId();
        if (resourceId == null || resourceId.isBlank()) {
            markError(event, "Evento persistido sin resource_id");
            throw bad("Webhook persistido sin resource_id");
        }

        if (isChargeback(type)) {
            try {
                PaymentGateway.PaymentDisputeResult dispute = gateway.getDispute(
                        new PaymentGateway.PaymentDisputeLookupRequest(resourceId, null));
                chargebacks.sync(PaymentProvider.MERCADO_PAGO, dispute);
                markProcessed(event, null);
                configWriter.recordWebhook(PaymentProvider.MERCADO_PAGO);
                return new WebhookResult(true, false, "Contracargo procesado");
            } catch (RuntimeException e) {
                markError(event, safeMessage(e));
                throw e;
            }
        }

        if (type != null && !"order".equalsIgnoreCase(type) && !"orders_v2".equalsIgnoreCase(type)) {
            markProcessed(event, "Tipo de evento ignorado: " + type);
            return new WebhookResult(true, false, "Evento válido no aplicable");
        }

        PaymentRecord payment = payments.findByProviderAndProviderCheckoutId(PaymentProvider.MERCADO_PAGO, resourceId).orElse(null);
        if (payment == null) {
            markProcessed(event, "PAYMENT_NOT_FOUND");
            configWriter.recordWebhook(PaymentProvider.MERCADO_PAGO);
            return new WebhookResult(true, false, "Order no asociada a un pago interno");
        }

        try {
            JsonNode order = gateway.fetchOrder(resourceId);
            PaymentGateway.PaymentStatusResult result = gateway.statusFromOrder(order, payment.getMerchantReference(), payment.getAmount(), payment.getCurrency());
            tx.applyProviderStatusWebhook(payment.getId(), result, "Webhook Mercado Pago " + firstNonBlank(action, type, "order"));
            markProcessed(event, null);
            configWriter.recordWebhook(PaymentProvider.MERCADO_PAGO);
            return new WebhookResult(true, false, "Procesado");
        } catch (RuntimeException e) {
            markError(event, safeMessage(e));
            throw e;
        }
    }

    private PaymentProviderEvent loadOrCreate(String eventId, String payloadHash, String type, String action,
                                              String resourceId, String requestId, Boolean liveMode) {
        PaymentProviderEvent existing = events.findByProviderAndProviderEventId(PaymentProvider.MERCADO_PAGO, eventId).orElse(null);
        if (existing != null) return existing;
        try {
            return events.saveAndFlush(PaymentProviderEvent.builder().provider(PaymentProvider.MERCADO_PAGO)
                    .providerEventId(eventId).payloadHash(payloadHash).eventType(trim(type, 80)).eventAction(trim(action, 120))
                    .resourceId(trim(resourceId, 190)).requestId(trim(requestId, 190)).liveMode(liveMode).build());
        } catch (DataIntegrityViolationException e) {
            return events.findByProviderAndProviderEventId(PaymentProvider.MERCADO_PAGO, eventId)
                    .orElseThrow(() -> e);
        }
    }

    private void markProcessed(PaymentProviderEvent event, String note) {
        event.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        event.setProcessingError(trim(note, 500));
        events.save(event);
    }

    private void markError(PaymentProviderEvent event, String error) {
        event.setProcessingError(trim(error, 500));
        events.save(event);
    }

    private void validateEnvironment(Boolean liveMode) {
        PaymentProviderMode mode = runtime.provider(PaymentProvider.MERCADO_PAGO).getMode();
        if (liveMode == null) return;
        if (mode == PaymentProviderMode.LIVE && !liveMode) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook de prueba recibido por configuración LIVE");
        if (mode != PaymentProviderMode.LIVE && liveMode) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook LIVE recibido por configuración no productiva");
    }

    private boolean isChargeback(String type) {
        return type != null && "topic_chargebacks_wh".equalsIgnoreCase(type.trim());
    }

    private String eventAction(JsonNode body) {
        String direct = text(body, "action");
        if (direct != null) return direct;
        JsonNode actions = body == null ? null : body.get("actions");
        if (actions != null && actions.isArray() && !actions.isEmpty()) return actions.get(0).asText(null);
        return null;
    }

    private JsonNode parse(String raw) {
        try {
            return raw == null || raw.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
        } catch (Exception e) {
            throw bad("Webhook de Mercado Pago con JSON inválido");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private String safeMessage(Throwable error) {
        String message = error instanceof ResponseStatusException r ? r.getReason() : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : trim(message, 500);
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record WebhookResult(boolean accepted, boolean duplicate, String message) {}
}
