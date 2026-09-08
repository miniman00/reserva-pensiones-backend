package uy.pensiones.mail;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;
import uy.pensiones.service.MailOutboxService;

import java.util.List;
import java.util.Locale;

@Service
public class MailServiceImpl implements MailService {

    private static final Logger log = LoggerFactory.getLogger(MailServiceImpl.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final RestClient restClient;
    private final boolean mailEnabled;
    private final String mailProvider;
    private final String from;
    private final String brevoApiKey;
    private final String brevoEndpoint;
    private final MailOutboxService outbox;

    public MailServiceImpl(ObjectProvider<JavaMailSender> mailSenderProvider,
                           RestClient.Builder restClientBuilder,
                           @Value("${app.mail.enabled:false}") boolean mailEnabled,
                           @Value("${app.mail.provider:smtp}") String mailProvider,
                           @Value("${app.mail.from:no-reply@codevaru.com}") String from,
                           @Value("${app.mail.brevo.api-key:}") String brevoApiKey,
                           @Value("${app.mail.brevo.endpoint:https://api.brevo.com/v3/smtp/email}") String brevoEndpoint,
                           MailOutboxService outbox) {
        this.mailSenderProvider = mailSenderProvider;
        this.restClient = restClientBuilder.build();
        this.mailEnabled = mailEnabled;
        this.mailProvider = mailProvider;
        this.from = from;
        this.brevoApiKey = brevoApiKey;
        this.brevoEndpoint = brevoEndpoint;
        this.outbox = outbox;
    }

    private void sendHtml(@NonNull String to, @NonNull String subject, @NonNull String html, String category) {
        sendHtml(to, subject, html, null, category);
    }

    private void sendHtml(@NonNull String to,
                          @NonNull String subject,
                          @NonNull String html,
                          String replyTo,
                          String category) {
        outbox.enqueue(to, subject, html, replyTo, category);
    }

    private void sendWithSmtp(String to,
                              String subject,
                              String html,
                              String replyTo,
                              String category) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.error("APP_MAIL_PROVIDER=smtp pero JavaMailSender no está configurado. category={}, to={}",
                    category, to);
            return;
        }

        try {
            MailAddress senderAddress = parseMailAddress(from, "CodeVaru");
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");

            if (senderAddress.name() == null || senderAddress.name().isBlank()) {
                helper.setFrom(senderAddress.email());
            } else {
                helper.setFrom(senderAddress.email(), senderAddress.name());
            }

            helper.setTo(to);
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo.trim());
            }
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(mime);

            log.info("Email enviado por SMTP. category={}, to={}, subject={}", category, to, subject);
        } catch (Exception ex) {
            log.warn("No se pudo enviar email por SMTP. category={}, to={}, error={}",
                    category, to, ex.getMessage(), ex);
        }
    }

    private void sendWithBrevo(String to,
                               String subject,
                               String html,
                               String replyTo,
                               String category) {
        if (brevoApiKey == null || brevoApiKey.isBlank()) {
            log.error("APP_MAIL_PROVIDER=brevo pero APP_BREVO_API_KEY está vacío. category={}, to={}",
                    category, to);
            return;
        }

        try {
            MailAddress parsedFrom = parseMailAddress(from, "CodeVaru");
            BrevoMailAddress sender = new BrevoMailAddress(parsedFrom.email(), parsedFrom.name());
            BrevoMailAddress reply = null;
            if (replyTo != null && !replyTo.isBlank()) {
                reply = new BrevoMailAddress(replyTo.trim(), null);
            }

            BrevoEmailRequest request = new BrevoEmailRequest(
                    sender,
                    List.of(new BrevoMailAddress(to, null)),
                    reply,
                    subject,
                    html,
                    List.of("pensiones", normalizeTag(category))
            );

            restClient.post()
                    .uri(brevoEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("api-key", brevoApiKey.trim())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Email enviado por Brevo. category={}, to={}, subject={}", category, to, subject);
        } catch (RestClientResponseException ex) {
            log.warn("No se pudo enviar email por Brevo. category={}, to={}, status={}, body={}",
                    category, to, ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("No se pudo enviar email por Brevo. category={}, to={}, error={}",
                    category, to, ex.getMessage(), ex);
        }
    }

    @Override
    public void sendInvite(String to, String orgName, String link) {
        String subject = "Invitación a " + orgName;
        String html = ("<p>Fuiste invitado/a a administrar la organización <b>%s</b>.</p>"
                + "<p>Hacé clic para aceptar: <a href=\"%s\">%s</a></p>"
                + "<p>Si no esperabas este correo, podés ignorarlo.</p>").formatted(orgName, link, link);
        sendHtml(to, subject, html, "INVITE");
    }

    @Override
    public void sendUserAdded(String to, String orgName) {
        String subject = "Acceso otorgado a " + orgName;
        String html = ("<p>Se te otorgó acceso a la organización <b>%s</b>.</p>").formatted(orgName);
        sendHtml(to, subject, html, "USER_ADDED");
    }

    @Override
    public void sendUserRemoved(String to, String orgName) {
        String subject = "Acceso removido de " + orgName;
        String html = ("<p>Tu acceso a la organización <b>%s</b> fue removido.</p>").formatted(orgName);
        sendHtml(to, subject, html, "USER_REMOVED");
    }

    @Override
    public void sendVerifyEmail(String to, String link) {
        String subject = "Verificá tu correo";
        String html = ("<p>Para verificar tu correo, hacé clic en el siguiente enlace:</p>"
                + "<p><a href=\"%s\">%s</a></p>"
                + "<p>Si no fuiste vos, ignorá este mensaje.</p>").formatted(link, link);
        sendHtml(to, subject, html, "VERIFY_EMAIL");
    }

    @Override
    public void sendPensionInquiry(String to, String pensionName, String contactName,
                                   String contactEmail, String contactPhone, String roomType,
                                   String moveInDate, String message) {
        String safePension = esc(pensionName);
        String safeName = esc(contactName);
        String safeEmail = esc(contactEmail);
        String safePhone = esc(contactPhone);
        String safeRoom = switch (roomType == null ? "ANY" : roomType) {
            case "SIMPLE" -> "Simple";
            case "MATRIMONIAL" -> "Matrimonial";
            default -> "Cualquiera";
        };
        String safeDate = moveInDate == null || moveInDate.isBlank() ? "No indicada" : esc(moveInDate);
        String safeMessage = message == null || message.isBlank()
                ? "Sin mensaje adicional"
                : esc(message).replace("\n", "<br>");

        String subject = "Nueva consulta para " + pensionName;
        String html = ("<p>Recibiste una nueva consulta por <b>%s</b>.</p>"
                + "<p><b>Nombre:</b> %s<br>"
                + "<b>Email:</b> %s<br>"
                + "<b>Teléfono:</b> %s<br>"
                + "<b>Habitación:</b> %s<br>"
                + "<b>Ingreso estimado:</b> %s</p>"
                + "<p><b>Mensaje:</b><br>%s</p>"
                + "<p>También podés gestionar esta consulta desde el portal.</p>")
                .formatted(safePension, safeName, valueOrDash(safeEmail), valueOrDash(safePhone),
                        safeRoom, safeDate, safeMessage);

        sendHtml(to, subject, html, contactEmail, "PENSION_INQUIRY");
    }

    @Override
    public void sendPensionMaintenanceReminder(String to, String pensionName, String title, String message, String actionLink) {
        String safePension = esc(pensionName == null || pensionName.isBlank() ? "tu pensión" : pensionName);
        String safeTitle = esc(title == null || title.isBlank() ? "Revisa tu publicación" : title);
        String safeMessage = esc(message == null ? "" : message).replace("\n", "<br>");
        String safeLink = esc(actionLink == null ? "" : actionLink);
        String subject = title == null || title.isBlank() ? "Revisa tu publicación en Pensiones" : title;
        String html = ("<p><b>%s</b></p>"
                + "<p><b>Publicación:</b> %s</p>"
                + "<p>%s</p>"
                + "<p><a href=\"%s\">Revisar publicación</a></p>"
                + "<p>Mantener disponibilidad y datos actualizados ayuda a quienes buscan alojamiento y evita mostrar información desactualizada.</p>")
                .formatted(safeTitle, safePension, safeMessage, safeLink);
        sendHtml(to, subject, html, "PENSION_MAINTENANCE");
    }

    @Override
    public void sendFounderBenefitNotice(String to, String displayName, String title, String message, String actionLink) {
        String safeName = esc(displayName == null || displayName.isBlank() ? "propietario" : displayName);
        String safeTitle = esc(title == null || title.isBlank() ? "Beneficio Propietario Fundador" : title);
        String safeMessage = esc(message == null ? "" : message).replace("\n", "<br>");
        String safeLink = esc(actionLink == null ? "" : actionLink);
        String subject = title == null || title.isBlank() ? "Beneficio Propietario Fundador" : title;
        String html = ("<p>Hola <b>%s</b>,</p>"
                + "<p><b>%s</b></p>"
                + "<p>%s</p>"
                + "<p><a href=\"%s\">Ver Planes y destacados</a></p>"
                + "<p>Cuando finalice el período de gracia, las publicaciones pueden quedar pausadas hasta que contrates un plan.</p>")
                .formatted(safeName, safeTitle, safeMessage, safeLink);
        sendHtml(to, subject, html, "FOUNDER_BENEFIT");
    }

    @Override
    public void sendOwnerAccessNotice(String to, String displayName, String title, String message, String actionLink) {
        String safeName = esc(displayName == null || displayName.isBlank() ? "propietario" : displayName);
        String safeTitle = esc(title == null || title.isBlank() ? "Acceso de propietario" : title);
        String safeMessage = esc(message == null ? "" : message).replace("\n", "<br>");
        String safeLink = esc(actionLink == null ? "" : actionLink);
        String subject = title == null || title.isBlank() ? "Acceso de propietario en Pensiones" : title;
        String html = ("<p>Hola <b>%s</b>,</p>"
                + "<p><b>%s</b></p>"
                + "<p>%s</p>"
                + "<p><a href=\"%s\">Ver planes</a></p>"
                + "<p>Tu información y tus publicaciones no se eliminan si el acceso vence. Puedes reactivarlas contratando un plan.</p>")
                .formatted(safeName, safeTitle, safeMessage, safeLink);
        sendHtml(to, subject, html, "OWNER_ACCESS");
    }

    @Override
    public void sendBackofficeSecurityNotice(String to, String displayName, String action) {
        String safeName = esc(displayName == null || displayName.isBlank() ? "usuario del Backoffice" : displayName);
        String safeAction = esc(action == null ? "Se modificó la configuración de seguridad de tu cuenta." : action);
        String subject = "Cambio de seguridad en tu cuenta del Backoffice";
        String html = ("<p>Hola <b>%s</b>,</p>"
                + "<p>%s</p>"
                + "<p>Si no reconocés este cambio, contactá inmediatamente al responsable del sistema y solicitá la revocación de tu acceso.</p>"
                + "<p>Por seguridad, este correo nunca incluye contraseñas, secretos TOTP ni códigos de recuperación.</p>")
                .formatted(safeName, safeAction);
        sendHtml(to, subject, html, "BACKOFFICE_SECURITY");
    }

    @Override
    public DeliveryResult sendPaymentOperationalAlert(List<String> recipients, String severity, String category,
                                                      String title, String message, String actionPath, boolean test) {
        List<String> targets = recipients == null ? List.of() : recipients.stream()
                .filter(java.util.Objects::nonNull).map(String::trim).filter(x -> !x.isBlank()).distinct().toList();
        if (targets.isEmpty()) return new DeliveryResult(false, "No hay destinatarios configurados");
        String safeSeverity = esc(severity == null ? "CRITICAL" : severity);
        String safeCategory = esc(category == null ? "PAYMENT" : category);
        String safeTitle = esc(title == null ? "Alerta operativa de pagos" : title);
        String safeMessage = esc(message == null ? "Sin detalle adicional" : message).replace("\n", "<br>");
        String safePath = actionPath == null || actionPath.isBlank() ? "/payment-alerts" : esc(actionPath);
        String prefix = test ? "[PRUEBA] " : "";
        String subject = "[Pensiones][" + (test ? "TEST" : (severity == null ? "CRITICAL" : severity)) + "] "
                + (title == null ? "Alerta operativa de pagos" : title);
        String html = ("<p><b>%s%s</b></p>"
                + "<p><b>Severidad:</b> %s<br><b>Categoría:</b> %s</p>"
                + "<p>%s</p>"
                + "<p><b>Ruta en Backoffice:</b> <code>%s</code></p>"
                + "<p>Este mensaje fue generado por el monitor operativo de pagos. "
                + "Las repeticiones de una misma alerta respetan el cooldown configurado.</p>")
                .formatted(prefix, safeTitle, safeSeverity, safeCategory, safeMessage, safePath);
        return sendOperationalHtml(targets, subject, html);
    }

    private DeliveryResult sendOperationalHtml(List<String> recipients, String subject, String html) {
        if (!mailEnabled) {
            log.info("Email operativo omitido porque app.mail.enabled=false. recipients={}, subject={}", recipients.size(), subject);
            return new DeliveryResult(false, "El transporte de correo está deshabilitado (app.mail.enabled=false)");
        }
        String provider = mailProvider == null || mailProvider.isBlank()
                ? "smtp" : mailProvider.trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "smtp" -> sendOperationalWithSmtp(recipients, subject, html);
            case "brevo" -> sendOperationalWithBrevo(recipients, subject, html);
            default -> {
                log.error("Proveedor de email no soportado para alerta operativa: {}", provider);
                yield new DeliveryResult(false, "Proveedor de correo no soportado: " + provider);
            }
        };
    }

    private DeliveryResult sendOperationalWithSmtp(List<String> recipients, String subject, String html) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) return new DeliveryResult(false, "JavaMailSender no está configurado");
        try {
            MailAddress senderAddress = parseMailAddress(from, "CodeVaru");
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            if (senderAddress.name() == null || senderAddress.name().isBlank()) helper.setFrom(senderAddress.email());
            else helper.setFrom(senderAddress.email(), senderAddress.name());
            helper.setTo(recipients.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(mime);
            log.info("Alerta operativa enviada por SMTP. recipients={}, subject={}", recipients.size(), subject);
            return new DeliveryResult(true, "Correo enviado por SMTP");
        } catch (Exception ex) {
            log.warn("No se pudo enviar alerta operativa por SMTP. recipients={}, error={}", recipients.size(), ex.getMessage(), ex);
            return new DeliveryResult(false, "No se pudo enviar por SMTP: " + safeDeliveryError(ex));
        }
    }

    private DeliveryResult sendOperationalWithBrevo(List<String> recipients, String subject, String html) {
        if (brevoApiKey == null || brevoApiKey.isBlank()) return new DeliveryResult(false, "APP_BREVO_API_KEY está vacío");
        try {
            MailAddress parsedFrom = parseMailAddress(from, "CodeVaru");
            BrevoEmailRequest request = new BrevoEmailRequest(
                    new BrevoMailAddress(parsedFrom.email(), parsedFrom.name()),
                    recipients.stream().map(x -> new BrevoMailAddress(x, null)).toList(),
                    null, subject, html, List.of("pensiones", "payment-operational-alert")
            );
            restClient.post().uri(brevoEndpoint).contentType(MediaType.APPLICATION_JSON)
                    .header("api-key", brevoApiKey.trim()).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .body(request).retrieve().toBodilessEntity();
            log.info("Alerta operativa enviada por Brevo. recipients={}, subject={}", recipients.size(), subject);
            return new DeliveryResult(true, "Correo enviado por Brevo");
        } catch (RestClientResponseException ex) {
            log.warn("No se pudo enviar alerta operativa por Brevo. recipients={}, status={}", recipients.size(), ex.getStatusCode());
            return new DeliveryResult(false, "Brevo respondió " + ex.getStatusCode());
        } catch (Exception ex) {
            log.warn("No se pudo enviar alerta operativa por Brevo. recipients={}, error={}", recipients.size(), ex.getMessage(), ex);
            return new DeliveryResult(false, "No se pudo enviar por Brevo: " + safeDeliveryError(ex));
        }
    }

    private String safeDeliveryError(Exception ex) {
        String value = ex == null ? null : ex.getMessage();
        if (value == null || value.isBlank()) return ex == null ? "error desconocido" : ex.getClass().getSimpleName();
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private MailAddress parseMailAddress(String rawValue, String defaultName) {
        String fallbackEmail = "no-reply@codevaru.com";
        String fallbackName = defaultName == null || defaultName.isBlank() ? "CodeVaru" : defaultName.trim();

        if (rawValue == null || rawValue.isBlank()) {
            return new MailAddress(fallbackEmail, fallbackName);
        }

        String value = rawValue.trim();
        int open = value.indexOf('<');
        int close = value.indexOf('>');

        if (open >= 0 && close > open) {
            String name = value.substring(0, open).trim();
            String email = value.substring(open + 1, close).trim();
            return new MailAddress(
                    email.isBlank() ? fallbackEmail : email,
                    name.isBlank() ? fallbackName : stripQuotes(name)
            );
        }

        return new MailAddress(value, fallbackName);
    }

    private String stripQuotes(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private String normalizeTag(String value) {
        if (value == null || value.isBlank()) {
            return "mail";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private record MailAddress(String email, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BrevoEmailRequest(
            BrevoMailAddress sender,
            List<BrevoMailAddress> to,
            BrevoMailAddress replyTo,
            String subject,
            String htmlContent,
            List<String> tags
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BrevoMailAddress(String email, String name) {
    }
}
