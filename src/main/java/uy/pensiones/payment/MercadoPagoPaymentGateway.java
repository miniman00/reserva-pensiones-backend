package uy.pensiones.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.model.PaymentProviderConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MercadoPagoPaymentGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(MercadoPagoPaymentGateway.class);
    /** Credenciales separadas por ambiente. Nunca almacenamos usuario/contraseña de cuentas de prueba. */
    public static final String SANDBOX_ACCESS_TOKEN = "SANDBOX_ACCESS_TOKEN";
    public static final String SANDBOX_WEBHOOK_SECRET = "SANDBOX_WEBHOOK_SECRET";
    public static final String LIVE_ACCESS_TOKEN = "LIVE_ACCESS_TOKEN";
    public static final String LIVE_WEBHOOK_SECRET = "LIVE_WEBHOOK_SECRET";
    private static final URI API_BASE = URI.create("https://api.mercadopago.com");

    private final PaymentRuntimeConfigurationService runtime;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public MercadoPagoPaymentGateway(PaymentRuntimeConfigurationService runtime, ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public PaymentProvider provider() { return PaymentProvider.MERCADO_PAGO; }

    @Override
    public PaymentCreationResult createPayment(PaymentCreationRequest request) {
        requireRequest(request);
        ProviderSettings settings = settings();
        String payerEmail = settings.mode() == PaymentProviderMode.LIVE ? request.payerEmail() : settings.testPayerEmail();
        if (payerEmail == null || payerEmail.isBlank()) throw bad("Mercado Pago requiere email del pagador");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "online");
        body.put("processing_mode", "manual");
        body.put("capture_mode", "automatic_async");
        body.put("total_amount", money(request.amount()));
        body.put("external_reference", request.merchantReference());
        body.put("description", trim(request.description(), 160));
        body.put("expiration_time", settings.expirationTime());
        body.putObject("payer").put("email", payerEmail.trim());

        ArrayNode items = body.putArray("items");
        ObjectNode item = items.addObject();
        item.put("title", trim(request.description(), 120));
        item.put("quantity", 1);
        item.put("unit_price", money(request.amount()));

        String successUrl = appendInternalPaymentId(settings.successUrl(), request.metadata());
        String failureUrl = appendInternalPaymentId(settings.failureUrl(), request.metadata());
        String pendingUrl = appendInternalPaymentId(settings.pendingUrl(), request.metadata());
        if (successUrl != null || failureUrl != null || pendingUrl != null) {
            ObjectNode config = body.putObject("config");
            // En Checkout Pro via Orders los Webhooks se configuran en Tus integraciones.
            // notificationUrl se conserva para readiness/certificacion, pero no forma parte
            // del payload de /v1/orders.
            ObjectNode online = config.putObject("online");
            if (successUrl != null) online.put("success_url", successUrl);
            if (failureUrl != null) online.put("failure_url", failureUrl);
            if (pendingUrl != null) online.put("pending_url", pendingUrl);
            if (successUrl != null) online.put("auto_return", settings.autoReturn());
        }

        JsonNode response = exchange("POST", "/v1/orders", settings.accessToken(), request.idempotencyKey(), body);
        verifyOrderIdentity(response, request.merchantReference(), request.amount(), request.currency());
        String orderId = text(response, "id");
        String checkoutUrl = text(response, "checkout_url");
        if (orderId == null || checkoutUrl == null) throw upstream("Mercado Pago no devolvió id/checkout_url de la order");
        MappedStatus mapped = mapStatus(response);
        return new PaymentCreationResult(extractPaymentId(response), null, orderId, checkoutUrl, mapped.status(), mapped.providerStatus());
    }

    @Override
    public PaymentStatusResult getStatus(PaymentLookupRequest request) {
        String orderId = requireCheckoutId(request);
        ProviderSettings settings = settings();
        JsonNode response = exchange("GET", "/v1/orders/" + encodePath(orderId), settings.accessToken(), null, null);
        verifyOrderReference(response, request == null ? null : request.merchantReference());
        MappedStatus mapped = mapStatus(response);
        return new PaymentStatusResult(mapped.status(), mapped.providerStatus(), extractPaymentId(response), extractRefundedAmount(response));
    }

    @Override
    public PaymentStatusResult cancel(PaymentLookupRequest request) {
        String orderId = requireCheckoutId(request);
        ProviderSettings settings = settings();
        String idempotency = request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? java.util.UUID.randomUUID().toString() : request.idempotencyKey();
        JsonNode response = exchange("POST", "/v1/orders/" + encodePath(orderId) + "/cancel", settings.accessToken(), idempotency, objectMapper.createObjectNode());
        verifyOrderReference(response, request == null ? null : request.merchantReference());
        MappedStatus mapped = mapStatus(response);
        return new PaymentStatusResult(mapped.status(), mapped.providerStatus(), extractPaymentId(response), extractRefundedAmount(response));
    }


    @Override
    public PaymentStatusResult refund(PaymentRefundRequest request) {
        if (request == null || request.payment() == null) throw bad("Solicitud de devolución inválida");
        String orderId = requireCheckoutId(request.payment());
        ProviderSettings settings = settings();
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) throw bad("Idempotency key obligatoria para reembolsar");

        JsonNode body = request.amount() == null ? null : refundTransactionBody(request, request.amount(), false);

        JsonNode response;
        try {
            response = exchange("POST", "/v1/orders/" + encodePath(orderId) + "/refund",
                    settings.accessToken(), request.idempotencyKey(), body);
        } catch (ResponseStatusException e) {
            // La documentacion de Orders indica body vacio para un reembolso total. En SANDBOX
            // algunas orders de prueba responden property_value sobre refund_amount incluso
            // con un POST realmente sin body. Solo para ese error reintentamos con la forma
            // documentada de transaccion y el importe total original.
            if (request.amount() != null || !isRefundAmountPatternError(e)) throw e;
            ObjectNode explicitFull = refundTransactionBody(request, request.originalAmount(), true);
            String fallbackKey = fullRefundFallbackIdempotencyKey(request.idempotencyKey());
            log.warn("mercado_pago_full_refund_empty_body_rejected orderId={} providerPaymentId={} retryingWithExplicitAmount=true",
                    orderId, request.payment().providerPaymentId());
            response = exchange("POST", "/v1/orders/" + encodePath(orderId) + "/refund",
                    settings.accessToken(), fallbackKey, explicitFull);
        }
        verifyOrderReference(response, request.payment().merchantReference());

        // La respuesta del endpoint de refund puede ser resumida. Consultamos la order
        // nuevamente para normalizar estado e importe acumulado reembolsado.
        JsonNode order = exchange("GET", "/v1/orders/" + encodePath(orderId), settings.accessToken(), null, null);
        verifyOrderReference(order, request.payment().merchantReference());
        MappedStatus mapped = mapStatus(order);
        return new PaymentStatusResult(mapped.status(), mapped.providerStatus(), extractPaymentId(order), extractRefundedAmount(order));
    }

    ObjectNode refundTransactionBody(PaymentRefundRequest request, BigDecimal amount, boolean fullFallback) {
        if (amount == null || amount.signum() <= 0) {
            throw bad(fullFallback
                    ? "Mercado Pago requiere el importe original para reintentar el reembolso total"
                    : "El importe del reembolso parcial debe ser mayor que cero");
        }
        String paymentId = request == null || request.payment() == null ? null : request.payment().providerPaymentId();
        if (paymentId == null || paymentId.isBlank()) {
            throw bad("Mercado Pago requiere el payment ID para reembolsar la transaccion");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode tx = payload.putArray("transactions").addObject();
        tx.put("id", paymentId.trim());
        tx.put("amount", money(amount));
        return payload;
    }

    String fullRefundFallbackIdempotencyKey(String originalKey) {
        String seed = (originalKey == null ? "" : originalKey) + ":mp-full-refund-explicit";
        return java.util.UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isRefundAmountPatternError(ResponseStatusException error) {
        if (error == null || error.getReason() == null) return false;
        String reason = error.getReason().toLowerCase(Locale.ROOT);
        return reason.contains("property_value")
                && reason.contains("refund_amount")
                && reason.contains("pattern");
    }

    @Override
    public PaymentDisputeResult getDispute(PaymentDisputeLookupRequest request) {
        if (request == null) throw bad("Solicitud de contracargo inválida");
        String disputeId = firstNonBlank(request.providerDisputeId(), request.providerPaymentId());
        if (disputeId == null) throw bad("Falta el ID de contracargo o payment ID de Mercado Pago");
        ProviderSettings settings = settings();
        JsonNode response = exchange("GET", "/v1/chargebacks/" + encodePath(disputeId), settings.accessToken(), null, null);
        String id = firstNonBlank(text(response, "id"), request.providerDisputeId());
        if (id == null) throw upstream("Mercado Pago no devolvió el ID del contracargo");
        List<String> paymentIds = new ArrayList<>();
        JsonNode payments = response.get("payments");
        if (payments != null) {
            if (payments.isArray()) {
                for (JsonNode payment : payments) if (!payment.isNull()) paymentIds.add(payment.asText());
            } else if (!payments.isNull()) paymentIds.add(payments.asText());
        }
        if (paymentIds.isEmpty() && request.providerPaymentId() != null && !request.providerPaymentId().isBlank()) {
            paymentIds.add(request.providerPaymentId().trim());
        }
        return new PaymentDisputeResult(
                id, List.copyOf(paymentIds), decimal(response, "amount"), upper(text(response, "currency")),
                trimNullable(text(response, "reason"), 160), bool(response, "coverage_eligible", "coverage_elegible"),
                bool(response, "coverage_applied"), bool(response, "documentation_required"),
                trimNullable(text(response, "documentation_status"), 80), date(response, "date_documentation_deadline"),
                date(response, "date_created"), date(response, "date_last_updated"), bool(response, "live_mode")
        );
    }

    @Override public boolean supportsDisputeEvidence() { return true; }

    @Override
    public void submitDisputeEvidence(PaymentDisputeEvidenceRequest request) {
        if (request == null || request.providerDisputeId() == null || request.providerDisputeId().isBlank()) {
            throw bad("Falta el ID del contracargo para enviar evidencia");
        }
        if (request.files() == null || request.files().isEmpty()) {
            throw bad("Debes adjuntar al menos un archivo de evidencia");
        }
        ProviderSettings settings = settings();
        exchangeMultipart("/v1/chargebacks/" + encodePath(request.providerDisputeId()) + "/documentation",
                settings.accessToken(), request.files());
    }

    @Override
    public ConnectionTestResult testConnection() {
        try {
            ProviderSettings settings = settings();
            JsonNode response = exchange("GET", "/v1/payment_methods", settings.accessToken(), null, null);
            if (!response.isArray()) return new ConnectionTestResult(false, "Mercado Pago respondió, pero el formato de payment_methods fue inesperado");
            return new ConnectionTestResult(true, "Mercado Pago respondió correctamente con las credenciales configuradas");
        } catch (Exception e) {
            return new ConnectionTestResult(false, safeMessage(e));
        }
    }

    public void validateLocalConfiguration() {
        settings();
        webhookSecret();
    }

    public String webhookSecret() {
        PaymentProviderMode mode = runtime.provider(PaymentProvider.MERCADO_PAGO).getMode();
        String credentialName = webhookSecretCredentialName(mode);
        String value = runtime.decryptedCredentials(PaymentProvider.MERCADO_PAGO).get(credentialName);
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Falta credencial " + credentialName + " de Mercado Pago");
        }
        return value;
    }

    public static String accessTokenCredentialName(PaymentProviderMode mode) {
        return mode == PaymentProviderMode.LIVE ? LIVE_ACCESS_TOKEN : SANDBOX_ACCESS_TOKEN;
    }

    public static String webhookSecretCredentialName(PaymentProviderMode mode) {
        return mode == PaymentProviderMode.LIVE ? LIVE_WEBHOOK_SECRET : SANDBOX_WEBHOOK_SECRET;
    }

    public static boolean credentialAffectsMode(String credentialName, PaymentProviderMode mode) {
        if (credentialName == null) return false;
        return credentialName.equals(accessTokenCredentialName(mode))
                || credentialName.equals(webhookSecretCredentialName(mode));
    }

    public boolean verifyWebhookSignature(String dataId, String requestId, String signature, long toleranceMillis) {
        if (signature == null || signature.isBlank()) return false;
        String ts = null, v1 = null;
        for (String part : signature.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            String value = kv[1].trim();
            if ("ts".equals(key)) ts = value;
            if ("v1".equals(key)) v1 = value;
        }
        if (ts == null || ts.isBlank() || v1 == null || v1.isBlank()) return false;
        long timestampMillis;
        try { timestampMillis = webhookTimestampMillis(ts); }
        catch (NumberFormatException | ArithmeticException e) { return false; }

        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) manifest.append("id:").append(dataId).append(';');
        if (requestId != null && !requestId.isBlank()) manifest.append("request-id:").append(requestId).append(';');
        manifest.append("ts:").append(ts).append(';');
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(manifest.toString().getBytes(StandardCharsets.UTF_8)));
            boolean signatureMatches = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    v1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
            if (!signatureMatches) return false;
            return toleranceMillis <= 0 || Math.abs(System.currentTimeMillis() - timestampMillis) <= toleranceMillis;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo validar la firma de Mercado Pago", e);
        }
    }

    private long webhookTimestampMillis(String ts) {
        long raw = Long.parseLong(ts);
        if (raw < 0) throw new NumberFormatException("timestamp negativo");
        // El SDK oficial interpreta ts como epoch-seconds, mientras que ejemplos actuales
        // de Orders/Webhooks también muestran epoch-milliseconds. El valor original se
        // conserva en el manifest HMAC; solo normalizamos para la ventana anti-replay.
        return raw < 100_000_000_000L ? Math.multiplyExact(raw, 1000L) : raw;
    }

    public JsonNode fetchOrder(String orderId) {
        ProviderSettings settings = settings();
        return exchange("GET", "/v1/orders/" + encodePath(orderId), settings.accessToken(), null, null);
    }

    public PaymentStatusResult statusFromOrder(JsonNode order, String expectedReference, BigDecimal expectedAmount, String expectedCurrency) {
        verifyOrderIdentity(order, expectedReference, expectedAmount, expectedCurrency);
        MappedStatus mapped = mapStatus(order);
        return new PaymentStatusResult(mapped.status(), mapped.providerStatus(), extractPaymentId(order), extractRefundedAmount(order));
    }

    private ProviderSettings settings() {
        PaymentProviderConfig config = runtime.provider(PaymentProvider.MERCADO_PAGO);
        if (config.getMode() == PaymentProviderMode.TEST) {
            throw bad("Mercado Pago debe operar en modo SANDBOX o LIVE");
        }
        Map<String, String> credentials = runtime.decryptedCredentials(PaymentProvider.MERCADO_PAGO);
        String accessTokenCredential = accessTokenCredentialName(config.getMode());
        String accessToken = credentials.get(accessTokenCredential);
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Falta credencial " + accessTokenCredential + " de Mercado Pago");
        }
        JsonNode json = objectMapper.createObjectNode();
        try {
            if (config.getConfigurationJson() != null && !config.getConfigurationJson().isBlank()) json = objectMapper.readTree(config.getConfigurationJson());
        } catch (Exception e) { throw new IllegalStateException("configurationJson de Mercado Pago no es válido", e); }
        String notification = validatedReturnUrl(text(json, "notificationUrl"), config.getMode());
        if (config.getMode() == PaymentProviderMode.LIVE && notification == null) {
            throw bad("Mercado Pago en LIVE requiere notificationUrl HTTPS para recibir webhooks");
        }
        String success = validatedPortalReturnUrl(text(json, "successUrl"), config.getMode(), "/payments/success");
        String failure = validatedPortalReturnUrl(text(json, "failureUrl"), config.getMode(), "/payments/failure");
        String pending = validatedPortalReturnUrl(text(json, "pendingUrl"), config.getMode(), "/payments/pending");
        if (config.getMode() == PaymentProviderMode.LIVE && (success == null || failure == null || pending == null)) {
            throw bad("Mercado Pago en LIVE requiere successUrl, failureUrl y pendingUrl HTTPS del Portal");
        }
        String testPayer = text(json, "testPayerEmail");
        if (config.getMode() != PaymentProviderMode.LIVE) {
            if (testPayer == null || !testPayer.trim().toLowerCase(Locale.ROOT).endsWith("@testuser.com")) {
                throw bad("Mercado Pago SANDBOX requiere testPayerEmail de un comprador de prueba terminado en @testuser.com");
            }
            testPayer = testPayer.trim();
        }
        String expiration = firstNonBlank(text(json, "expirationTime"), "P1D");
        if (!expiration.matches("P(?:\\d+D)?(?:T(?:\\d+H)?(?:\\d+M)?)?")) throw bad("expirationTime de Mercado Pago debe ser una duración ISO-8601 simple, por ejemplo P1D");
        String autoReturn = firstNonBlank(text(json, "autoReturn"), "approved");
        if (!"approved".equals(autoReturn) && !"all".equals(autoReturn)) throw bad("autoReturn de Mercado Pago debe ser approved o all");
        return new ProviderSettings(config.getMode(), accessToken, notification, success, failure, pending, testPayer, expiration, autoReturn);
    }

    private JsonNode exchangeMultipart(String path, String accessToken, List<PaymentDisputeEvidenceFile> files) {
        String boundary = "----PensionesEvidence" + java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
            for (PaymentDisputeEvidenceFile file : files) {
                String filename = file.filename().replace("\"", "");
                String header = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"files[]\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: " + file.contentType() + "\r\n\r\n";
                parts.add(HttpRequest.BodyPublishers.ofByteArray(header.getBytes(StandardCharsets.UTF_8)));
                parts.add(HttpRequest.BodyPublishers.ofByteArray(file.content()));
                parts.add(HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.US_ASCII)));
            }
            parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII)));
            HttpRequest request = HttpRequest.newBuilder(API_BASE.resolve(path)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode parsed = parseBody(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = providerErrorMessage(parsed, response.statusCode());
                throw new ResponseStatusException(response.statusCode() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.CONFLICT,
                        "Mercado Pago: " + trim(message, 300));
            }
            return parsed;
        } catch (ResponseStatusException e) { throw e; }
        catch (java.net.http.HttpTimeoutException e) { throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Mercado Pago no confirmó la carga de evidencia dentro del tiempo esperado"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La carga de evidencia fue interrumpida"); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo enviar la evidencia a Mercado Pago"); }
    }

    private JsonNode exchange(String method, String path, String accessToken, String idempotencyKey, JsonNode body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(API_BASE.resolve(path)).timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + accessToken);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) b.header("X-Idempotency-Key", idempotencyKey);
            if ("POST".equals(method)) {
                if (body == null) {
                    // Orders API exige que el reembolso total se envíe realmente sin body.
                    // No declaramos application/json en ese caso para evitar que Mercado Pago
                    // intente validar un payload JSON vacío como si fuera un reembolso parcial.
                    b.POST(HttpRequest.BodyPublishers.noBody());
                } else {
                    b.header("Content-Type", "application/json");
                    b.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
                }
            } else b.GET();
            HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode parsed = parseBody(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = providerErrorMessage(parsed, response.statusCode());
                String providerRequestId = response.headers().firstValue("x-request-id").orElse("-");
                log.warn("mercado_pago_http_error method={} path={} status={} providerRequestId={} response={}",
                        method, path, response.statusCode(), providerRequestId, safeProviderErrorBody(parsed));
                throw new ResponseStatusException(response.statusCode() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.CONFLICT,
                        "Mercado Pago: " + trim(message, 300));
            }
            return parsed;
        } catch (ResponseStatusException e) { throw e; }
        catch (java.net.http.HttpTimeoutException e) { throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Mercado Pago no respondió dentro del tiempo esperado"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La comunicación con Mercado Pago fue interrumpida"); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo comunicar con Mercado Pago"); }
    }


    String providerErrorMessage(JsonNode parsed, int statusCode) {
        String code = firstNonBlank(text(parsed, "code"), text(parsed, "error_code"));
        String direct = firstNonBlank(text(parsed, "message"), text(parsed, "error"));

        // Orders API devuelve el nombre de la propiedad inválida en `details`.
        // No debemos perder ese dato por priorizar el mensaje genérico superior.
        String nested = firstNestedProviderError(parsed == null ? null : parsed.get("details"));
        if (nested == null) nested = firstNestedProviderError(parsed == null ? null : parsed.get("errors"));
        if (nested == null) nested = firstNestedProviderError(parsed == null ? null : parsed.get("cause"));

        String header = direct == null ? code : (code == null ? direct : code + ": " + direct);
        if (nested != null && !nested.equals(header) && !nested.equals(direct) && !nested.equals(code)) {
            return header == null ? nested : header + " | detalle: " + nested;
        }
        if (header != null) return header;
        if (nested != null) return nested;
        return "Mercado Pago respondió HTTP " + statusCode;
    }

    private String firstNestedProviderError(JsonNode node) {
        return firstNestedProviderError(node, 0);
    }

    private String firstNestedProviderError(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > 6) return null;
        if (node.isTextual() || node.isNumber() || node.isBoolean()) return node.asText();

        if (node.isArray()) {
            for (JsonNode candidate : node) {
                String found = firstNestedProviderError(candidate, depth + 1);
                if (found != null && !found.isBlank()) return found;
            }
            return null;
        }

        String field = firstNonBlank(text(node, "field"), text(node, "property"), text(node, "path"));
        String message = firstNonBlank(text(node, "message"), text(node, "detail"), text(node, "description"));
        String code = firstNonBlank(text(node, "code"), text(node, "error"));

        // Algunas variantes de Orders anidan el nombre de la propiedad rechazada
        // dentro de otro details/errors/cause. Priorizamos ese dato antes del
        // mensaje genérico "Properties not supported".
        String nested = firstNestedProviderError(node.get("details"), depth + 1);
        if (nested == null) nested = firstNestedProviderError(node.get("errors"), depth + 1);
        if (nested == null) nested = firstNestedProviderError(node.get("cause"), depth + 1);
        if (nested != null && !nested.equals(message) && !nested.equals(code)) {
            if (field != null) return field + ": " + nested;
            return nested;
        }

        if (field != null && message != null) return field + ": " + message;
        if (code != null && message != null) return code + ": " + message;
        if (field != null) return field;
        if (message != null) return message;
        if (code != null) return code;
        return null;
    }

    private String safeProviderErrorBody(JsonNode parsed) {
        if (parsed == null || parsed.isNull()) return "{}";
        String raw = parsed.toString().replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "<email>");
        return trim(raw, 1500);
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(body); } catch (Exception e) { throw upstream("Mercado Pago devolvió una respuesta JSON inválida"); }
    }

    private MappedStatus mapStatus(JsonNode order) {
        String status = lower(text(order, "status"));
        String detail = lower(text(order, "status_detail"));
        String providerStatus = status + (detail == null || detail.equals(status) ? "" : ":" + detail);
        if ("refunded".equals(status) || "refunded".equals(detail)) return new MappedStatus(PaymentStatus.REFUNDED, providerStatus);
        if ("processed".equals(status) && "accredited".equals(detail)) return new MappedStatus(PaymentStatus.APPROVED, providerStatus);
        if ("processed".equals(status) && "partially_refunded".equals(detail)) return new MappedStatus(PaymentStatus.APPROVED, providerStatus + ":REVIEW_PARTIAL_REFUND");
        if ("failed".equals(status)) return new MappedStatus(PaymentStatus.REJECTED, providerStatus);
        if ("expired".equals(status) || "expired".equals(detail)) return new MappedStatus(PaymentStatus.EXPIRED, providerStatus);
        if ("canceled".equals(status) || "cancelled".equals(status)) return new MappedStatus(PaymentStatus.CANCELLED, providerStatus);
        if ("created".equals(status) || "processing".equals(status) || "action_required".equals(status)) return new MappedStatus(PaymentStatus.PENDING, providerStatus);
        return new MappedStatus(PaymentStatus.PENDING, providerStatus + ":UNKNOWN");
    }

    private void verifyOrderIdentity(JsonNode order, String expectedReference, BigDecimal expectedAmount, String expectedCurrency) {
        verifyOrderReference(order, expectedReference);
        if (expectedAmount != null) {
            String total = text(order, "total_amount");
            if (total == null || new BigDecimal(total).compareTo(expectedAmount) != 0) throw upstream("La order de Mercado Pago no coincide con el importe interno");
        }
        if (expectedCurrency != null) {
            String currency = text(order, "currency");
            if (currency != null && !expectedCurrency.equalsIgnoreCase(currency)) throw upstream("La order de Mercado Pago no coincide con la moneda interna");
        }
    }

    private void verifyOrderReference(JsonNode order, String expectedReference) {
        if (expectedReference == null || expectedReference.isBlank()) return;
        if (!expectedReference.equals(text(order, "external_reference"))) throw upstream("La referencia externa de Mercado Pago no coincide con el pago interno");
    }

    private String extractPaymentId(JsonNode order) {
        JsonNode payments = order.path("transactions").path("payments");
        if (payments.isArray() && !payments.isEmpty()) return text(payments.get(0), "id");
        return null;
    }

    private BigDecimal extractRefundedAmount(JsonNode order) {
        JsonNode refunds = order.path("transactions").path("refunds");
        if (!refunds.isArray()) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode refund : refunds) {
            String raw = text(refund, "amount");
            if (raw == null || raw.isBlank()) continue;
            try { total = total.add(new BigDecimal(raw)); } catch (NumberFormatException ignored) { }
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String requireCheckoutId(PaymentLookupRequest request) {
        String id = request == null ? null : request.providerCheckoutId();
        if (id == null || id.isBlank()) throw bad("Falta el ID de order de Mercado Pago");
        return id;
    }

    private void requireRequest(PaymentCreationRequest r) {
        if (r == null || r.amount() == null || r.amount().signum() <= 0 || r.currency() == null || r.currency().isBlank()) throw bad("Solicitud de pago inválida");
        if (r.merchantReference() == null || r.merchantReference().isBlank() || r.merchantReference().length() > 64) throw bad("Referencia comercial inválida para Mercado Pago");
        if (r.idempotencyKey() == null || r.idempotencyKey().isBlank()) throw bad("Idempotency key obligatoria para Mercado Pago");
    }

    private String validatedReturnUrl(String raw, PaymentProviderMode mode) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.trim());
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || (mode != PaymentProviderMode.LIVE && "http".equalsIgnoreCase(uri.getScheme())))) throw new IllegalArgumentException();
            if (uri.getUserInfo() != null || uri.getFragment() != null || hasQueryParameter(uri.getRawQuery(), "paymentId")) throw new IllegalArgumentException();
            if (mode == PaymentProviderMode.LIVE && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))) throw new IllegalArgumentException();
            return uri.toString();
        } catch (Exception e) { throw bad("URL de retorno de Mercado Pago inválida; no incluyas el parámetro reservado paymentId"); }
    }

    private String validatedPortalReturnUrl(String raw, PaymentProviderMode mode, String expectedPath) {
        String value = validatedReturnUrl(raw, mode);
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            if (uri.getPath() == null || !uri.getPath().endsWith(expectedPath)) throw new IllegalArgumentException();
            return value;
        } catch (Exception e) {
            throw bad("URL de retorno de Mercado Pago inválida; debe terminar en " + expectedPath);
        }
    }

    String appendInternalPaymentId(String url, Map<String, String> metadata) {
        if (url == null || url.isBlank() || metadata == null) return url;
        String internalPaymentId = metadata.get("internalPaymentId");
        if (internalPaymentId == null || !internalPaymentId.matches("\\d+")) return url;
        String separator = url.contains("?") ? (url.endsWith("?") || url.endsWith("&") ? "" : "&") : "?";
        return url + separator + "paymentId=" + java.net.URLEncoder.encode(internalPaymentId, StandardCharsets.UTF_8);
    }

    private boolean hasQueryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) return false;
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            try {
                if (name.equals(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8))) return true;
            } catch (IllegalArgumentException ignored) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        try { return new BigDecimal(value.asText()); } catch (Exception e) { return null; }
    }
    private Boolean bool(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) continue;
            if (value.isBoolean()) return value.asBoolean();
            String raw = value.asText();
            if ("true".equalsIgnoreCase(raw)) return true;
            if ("false".equalsIgnoreCase(raw)) return false;
        }
        return null;
    }
    private OffsetDateTime date(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null || raw.isBlank()) return null;
        try { return OffsetDateTime.parse(raw); } catch (Exception e) { return null; }
    }
    private String upper(String value) { return value == null ? null : value.trim().toUpperCase(Locale.ROOT); }
    private String trimNullable(String value, int max) { if (value == null) return null; String x=value.trim(); return x.length()<=max?x:x.substring(0,max); }

    private String encodePath(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private String text(JsonNode n, String field) { if (n == null || n.isMissingNode() || n.isNull()) return null; JsonNode v=n.get(field); return v==null||v.isNull()?null:v.asText(null); }
    private String money(BigDecimal v) { return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(); }
    private String trim(String v, int max) { String x = v == null ? "Pensiones" : v.trim(); return x.length() <= max ? x : x.substring(0, max); }
    private String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v.trim(); return null; }
    private String lower(String v) { return v == null ? "" : v.trim().toLowerCase(Locale.ROOT); }
    private String safeMessage(Exception e) { String m=e instanceof ResponseStatusException r ? r.getReason() : e.getMessage(); return m==null||m.isBlank()?"No se pudo validar la conexión con Mercado Pago":trim(m,300); }
    private ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,m); }
    private ResponseStatusException upstream(String m) { return new ResponseStatusException(HttpStatus.BAD_GATEWAY,m); }

    private record ProviderSettings(PaymentProviderMode mode, String accessToken, String notificationUrl, String successUrl, String failureUrl,
                                    String pendingUrl, String testPayerEmail, String expirationTime, String autoReturn) {}
    private record MappedStatus(PaymentStatus status, String providerStatus) {}
}
