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

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class MailServiceImpl implements MailService {

    private static final Logger log = LoggerFactory.getLogger(MailServiceImpl.class);
    private static final Locale ES_UY = Locale.forLanguageTag("es-UY");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", ES_UY);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final RestClient restClient;
    private final boolean mailEnabled;
    private final String mailProvider;
    private final String from;
    private final String brevoApiKey;
    private final String brevoEndpoint;
    private final MailOutboxService outbox;
    private final BrandedMailTemplate template;

    public MailServiceImpl(ObjectProvider<JavaMailSender> mailSenderProvider,
                           RestClient.Builder restClientBuilder,
                           @Value("${app.mail.enabled:false}") boolean mailEnabled,
                           @Value("${app.mail.provider:smtp}") String mailProvider,
                           @Value("${app.mail.from:no-reply@codevaru.com}") String from,
                           @Value("${app.mail.brevo.api-key:}") String brevoApiKey,
                           @Value("${app.mail.brevo.endpoint:https://api.brevo.com/v3/smtp/email}") String brevoEndpoint,
                           MailOutboxService outbox,
                           BrandedMailTemplate template) {
        this.mailSenderProvider = mailSenderProvider;
        this.restClient = restClientBuilder.build();
        this.mailEnabled = mailEnabled;
        this.mailProvider = mailProvider;
        this.from = from;
        this.brevoApiKey = brevoApiKey;
        this.brevoEndpoint = brevoEndpoint;
        this.outbox = outbox;
        this.template = template;
    }

    private void sendHtml(@NonNull String to, @NonNull String subject, @NonNull String html, String category) {
        sendHtml(to, subject, html, null, category);
    }

    private void sendHtml(@NonNull String to,
                          @NonNull String subject,
                          @NonNull String html,
                          String replyTo,
                          String category) {
        outbox.enqueue(to, subjectValue(subject), html, replyTo, category);
    }

    @Override
    public void sendInvite(String to, String orgName, String link) {
        String safeOrg = esc(orgName == null || orgName.isBlank() ? "una pensión" : orgName);
        String subject = "Te invitaron a gestionar " + subjectValue(orgName == null ? "una pensión" : orgName) + " en Pensiones";
        String body = "<p style=\"margin:0 0 14px;\">Recibiste una invitación para colaborar en la gestión de <strong>" + safeOrg + "</strong>.</p>"
                + "<p style=\"margin:0;\">Al aceptar podrás acceder a las funciones que el propietario haya habilitado para tu cuenta.</p>"
                + template.callout("Invitación segura", "Si no esperabas esta invitación, no necesitás hacer nada. Tu cuenta no cambia hasta que la aceptes.", true);
        String html = template.customer(
                "Tenés una nueva invitación para gestionar una publicación en Pensiones.",
                "INVITACIÓN",
                "Te invitaron a colaborar",
                body,
                "Aceptar invitación",
                link
        );
        sendHtml(to, subject, html, "INVITE");
    }

    @Override
    public void sendUserAdded(String to, String orgName) {
        String safeOrg = esc(orgName == null || orgName.isBlank() ? "una pensión" : orgName);
        String subject = "Ya tenés acceso a " + subjectValue(orgName == null ? "una pensión" : orgName);
        String body = "<p style=\"margin:0 0 14px;\">Tu acceso a <strong>" + safeOrg + "</strong> ya está habilitado.</p>"
                + "<p style=\"margin:0;\">Ingresá al portal para revisar la publicación y comenzar a trabajar con los permisos asignados.</p>";
        String html = template.positive(
                "Tu acceso como colaborador ya está disponible.",
                "ACCESO HABILITADO",
                "Ya podés colaborar",
                body,
                "Ir a Pensiones",
                template.portalUrl("/profile")
        );
        sendHtml(to, subject, html, "USER_ADDED");
    }

    @Override
    public void sendUserRemoved(String to, String orgName) {
        String safeOrg = esc(orgName == null || orgName.isBlank() ? "una pensión" : orgName);
        String subject = "Tu acceso a " + subjectValue(orgName == null ? "una pensión" : orgName) + " fue actualizado";
        String body = "<p style=\"margin:0 0 14px;\">Tu acceso como colaborador a <strong>" + safeOrg + "</strong> fue removido.</p>"
                + "<p style=\"margin:0;\">Esto no afecta tu cuenta personal ni el resto de tus actividades dentro de Pensiones.</p>";
        String html = template.customer(
                "Se actualizó uno de tus accesos de colaboración en Pensiones.",
                "ACCESO ACTUALIZADO",
                "Se modificó tu acceso",
                body,
                "Ir a mi cuenta",
                template.portalUrl("/profile")
        );
        sendHtml(to, subject, html, "USER_REMOVED");
    }

    @Override
    public void sendVerifyEmail(String to, String link) {
        String subject = "Confirmá tu correo en Pensiones";
        String body = "<p style=\"margin:0 0 14px;\">Confirmá que esta dirección de correo te pertenece para mantener tu cuenta protegida y recibir correctamente las comunicaciones importantes de Pensiones.</p>"
                + template.callout("Protegemos tu cuenta", "Si no solicitaste esta verificación, podés ignorar este mensaje con tranquilidad.", true);
        String html = template.customer(
                "Confirmá tu dirección de correo para completar la verificación de tu cuenta.",
                "VERIFICACIÓN",
                "Confirmá tu correo",
                body,
                "Verificar mi correo",
                link
        );
        sendHtml(to, subject, html, "VERIFY_EMAIL");
    }

    @Override
    public void sendPensionInquiry(String to, String pensionName, String contactName,
                                   String contactEmail, String contactPhone, String roomType,
                                   String moveInDate, String message) {
        String safePension = esc(pensionName == null || pensionName.isBlank() ? "tu pensión" : pensionName);
        String safeName = esc(contactName == null || contactName.isBlank() ? "Persona interesada" : contactName);
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

        String subject = "Nueva consulta para " + subjectValue(pensionName == null ? "tu pensión" : pensionName);
        String details = template.summaryTable(List.of(
                new BrandedMailTemplate.SummaryRow("Persona interesada", contactName == null || contactName.isBlank() ? "Persona interesada" : contactName),
                new BrandedMailTemplate.SummaryRow("Email", valueOrDash(contactEmail)),
                new BrandedMailTemplate.SummaryRow("Teléfono", valueOrDash(contactPhone)),
                new BrandedMailTemplate.SummaryRow("Habitación", safeRoom),
                new BrandedMailTemplate.SummaryRow("Ingreso estimado", moveInDate == null || moveInDate.isBlank() ? "No indicado" : moveInDate)
        ));
        String body = "<p style=\"margin:0 0 10px;\">Recibiste una nueva consulta por <strong>" + safePension + "</strong>.</p>"
                + details
                + "<div style=\"margin:18px 0;padding:16px;background:#F8FAFC;border-left:4px solid #0B74DE;border-radius:8px;\">"
                + "<div style=\"font-size:12px;font-weight:800;color:#667085;margin-bottom:7px;\">MENSAJE</div>"
                + "<div style=\"font-size:14px;line-height:1.6;color:#344054;\">" + safeMessage + "</div></div>"
                + "<p style=\"margin:0;color:#667085;font-size:13px;\">Respondé desde el portal para mantener la conversación organizada y accesible para tu equipo.</p>";
        String html = template.customer(
                "Una persona está interesada en " + (pensionName == null ? "tu publicación" : pensionName) + ".",
                "NUEVA CONSULTA",
                "Tenés una nueva oportunidad",
                body,
                "Gestionar consulta",
                template.portalUrl("/profile/inquiries")
        );
        sendHtml(to, subject, html, contactEmail, "PENSION_INQUIRY");
    }

    @Override
    public void sendPensionMaintenanceReminder(String to, String pensionName, String title, String message, String actionLink) {
        String safePension = esc(pensionName == null || pensionName.isBlank() ? "tu pensión" : pensionName);
        String safeMessage = esc(message == null ? "" : message).replace("\n", "<br>");
        String subject = subjectValue(title == null || title.isBlank() ? "Revisá tu publicación en Pensiones" : title);
        String body = "<p style=\"margin:0 0 10px;\">Queremos ayudarte a mantener <strong>" + safePension + "</strong> visible y confiable para quienes buscan alojamiento.</p>"
                + (safeMessage.isBlank() ? "" : "<div style=\"margin:16px 0;padding:15px;background:#F8FAFC;border-radius:10px;color:#344054;\">" + safeMessage + "</div>")
                + template.callout("Una publicación actualizada genera más confianza", "Revisar periódicamente la disponibilidad, los datos y las fotos ayuda a mejorar la experiencia de quienes están buscando alojamiento.", true);
        String html = template.customer(
                "Revisá la información de tu publicación para mantenerla actualizada.",
                "TU PUBLICACIÓN",
                title == null || title.isBlank() ? "Dale una revisión a tu publicación" : title,
                body,
                "Revisar publicación",
                actionLink
        );
        sendHtml(to, subject, html, "PENSION_MAINTENANCE");
    }

    @Override
    public void sendFounderBenefitNotice(String to, String displayName, String title, String message, String actionLink) {
        String safeName = esc(displayName == null || displayName.isBlank() ? "propietario" : displayName);
        String safeMessage = esc(message == null ? "" : message).replace("\n", "<br>");
        String subject = subjectValue(title == null || title.isBlank() ? "Tu beneficio de Propietario Fundador en Pensiones" : title);
        String body = "<p style=\"margin:0 0 12px;\">Hola <strong>" + safeName + "</strong>,</p>"
                + (safeMessage.isBlank() ? "" : "<p style=\"margin:0 0 16px;\">" + safeMessage + "</p>")
                + template.callout("Gracias por ser parte desde el comienzo", "Tu beneficio fundador reconoce a quienes apostaron por Pensiones en su etapa inicial. Podés consultar en el portal su vigencia y los beneficios disponibles.", true);
        String html = template.positive(
                "Información importante sobre tu beneficio de Propietario Fundador.",
                "PROPIETARIO FUNDADOR",
                title == null || title.isBlank() ? "Tu beneficio fundador" : title,
                body,
                "Ver planes y beneficios",
                actionLink
        );
        sendHtml(to, subject, html, "FOUNDER_BENEFIT");
    }

    @Override
    public void sendOwnerAccessNotice(String to, String displayName, String title, String message, String actionLink) {
        String safeName = esc(displayName == null || displayName.isBlank() ? "propietario" : displayName);
        String safeMessage = esc(message == null ? "" : message).replace("\n", "<br>");
        String subject = subjectValue(title == null || title.isBlank() ? "Información sobre tu acceso de propietario en Pensiones" : title);
        String body = "<p style=\"margin:0 0 12px;\">Hola <strong>" + safeName + "</strong>,</p>"
                + (safeMessage.isBlank() ? "" : "<p style=\"margin:0 0 16px;\">" + safeMessage + "</p>")
                + template.callout("Tu información permanece protegida", "Si tu acceso comercial vence, no eliminamos tu información ni tus publicaciones. Podés recuperar los beneficios correspondientes al activar un plan disponible.", true);
        String html = template.customer(
                "Hay una actualización relacionada con tu acceso como propietario.",
                "TU CUENTA",
                title == null || title.isBlank() ? "Actualización de tu acceso" : title,
                body,
                "Ver planes",
                actionLink
        );
        sendHtml(to, subject, html, "OWNER_ACCESS");
    }

    @Override
    public void sendSubscriptionActivated(SubscriptionActivatedMail details) {
        if (details == null || details.to() == null || details.to().isBlank()) return;
        String planName = details.planName() == null || details.planName().isBlank() ? "tu nuevo plan" : details.planName();
        boolean changed = details.previousPlanName() != null && !details.previousPlanName().isBlank()
                && !details.previousPlanName().equalsIgnoreCase(planName);
        String safeName = esc(details.displayName() == null || details.displayName().isBlank() ? "propietario" : details.displayName());
        String safePlan = esc(planName);
        String subject = changed
                ? "Tu plan cambió a " + subjectValue(planName) + " en Pensiones"
                : "Tu plan " + subjectValue(planName) + " ya está activo";
        String title = changed ? "Tu cambio de plan fue exitoso" : "Tu plan ya está activo";

        List<BrandedMailTemplate.SummaryRow> rows = new ArrayList<>();
        rows.add(new BrandedMailTemplate.SummaryRow("Plan activo", planName));
        if (changed) rows.add(new BrandedMailTemplate.SummaryRow("Plan anterior", details.previousPlanName()));
        rows.add(new BrandedMailTemplate.SummaryRow("Estado", "Activo"));
        rows.add(new BrandedMailTemplate.SummaryRow("Inicio", formatDate(details.startsAt())));
        rows.add(new BrandedMailTemplate.SummaryRow("Vigencia hasta", formatDate(details.expiresAt())));
        if (details.periodMonths() != null) {
            rows.add(new BrandedMailTemplate.SummaryRow("Período", details.periodMonths() == 1 ? "1 mes" : details.periodMonths() + " meses"));
        }
        rows.add(new BrandedMailTemplate.SummaryRow("Importe", formatMoney(details.amount(), details.currency())));
        if (details.paymentReference() != null && !details.paymentReference().isBlank()) {
            rows.add(new BrandedMailTemplate.SummaryRow("Referencia", details.paymentReference()));
        }

        String body = "<p style=\"margin:0 0 14px;\">Hola <strong>" + safeName + "</strong>,</p>"
                + (changed
                ? "<p style=\"margin:0 0 14px;\">Confirmamos el pago y <strong>" + safePlan + "</strong> ya reemplazó a tu plan anterior. Tus nuevos beneficios están disponibles desde ahora.</p>"
                : "<p style=\"margin:0 0 14px;\">Confirmamos el pago y <strong>" + safePlan + "</strong> ya está activo. Tus beneficios están disponibles desde ahora.</p>")
                + template.summaryTable(rows)
                + ((details.planDescription() == null || details.planDescription().isBlank()) ? ""
                : "<p style=\"margin:18px 0 0;color:#475467;\">" + esc(details.planDescription()) + "</p>")
                + template.benefits(details.benefits())
                + template.callout("Todo listo", "Podés seguir administrando tus publicaciones con normalidad. El portal ya está aplicando los límites y beneficios de tu plan activo.", true);

        String html = template.positive(
                changed ? "Tu cambio de plan quedó confirmado y los nuevos beneficios ya están activos." : "Tu compra quedó confirmada y tu plan ya está activo.",
                changed ? "CAMBIO DE PLAN CONFIRMADO" : "PLAN ACTIVO",
                title,
                body,
                "Ver mi plan",
                details.actionLink() == null || details.actionLink().isBlank() ? template.portalUrl("/profile/commercial") : details.actionLink()
        );
        sendHtml(details.to(), subject, html, changed ? "SUBSCRIPTION_CHANGED" : "SUBSCRIPTION_ACTIVATED");
    }

    @Override
    public void sendPromotionActivated(PromotionActivatedMail details) {
        if (details == null || details.to() == null || details.to().isBlank()) return;
        String safeName = esc(details.displayName() == null || details.displayName().isBlank() ? "propietario" : details.displayName());
        String pensionName = details.pensionName() == null || details.pensionName().isBlank() ? "tu pensión" : details.pensionName();
        String promotionName = details.promotionName() == null || details.promotionName().isBlank() ? "Destacado" : details.promotionName();
        String subject = "Tu destacado ya está activo para " + subjectValue(pensionName);

        List<BrandedMailTemplate.SummaryRow> rows = new ArrayList<>();
        rows.add(new BrandedMailTemplate.SummaryRow("Publicación", pensionName));
        rows.add(new BrandedMailTemplate.SummaryRow("Beneficio", promotionName));
        rows.add(new BrandedMailTemplate.SummaryRow("Estado", "Activo"));
        rows.add(new BrandedMailTemplate.SummaryRow("Inicio", formatDate(details.startsAt())));
        rows.add(new BrandedMailTemplate.SummaryRow("Vigencia hasta", formatDate(details.endsAt())));
        rows.add(new BrandedMailTemplate.SummaryRow("Importe", formatMoney(details.amount(), details.currency())));
        if (details.paymentReference() != null && !details.paymentReference().isBlank()) {
            rows.add(new BrandedMailTemplate.SummaryRow("Referencia", details.paymentReference()));
        }

        String body = "<p style=\"margin:0 0 14px;\">Hola <strong>" + safeName + "</strong>,</p>"
                + "<p style=\"margin:0 0 14px;\">Confirmamos tu pago y <strong>" + esc(promotionName) + "</strong> ya está aplicado a <strong>" + esc(pensionName) + "</strong>.</p>"
                + template.summaryTable(rows)
                + ((details.promotionDescription() == null || details.promotionDescription().isBlank()) ? ""
                : "<p style=\"margin:18px 0 0;color:#475467;\">" + esc(details.promotionDescription()) + "</p>")
                + template.callout("Más visibilidad para tu publicación", "El destacado ya está activo durante el período indicado. Podés seguir la publicación y tus beneficios desde el perfil comercial.", true);

        String html = template.positive(
                "Tu compra de visibilidad quedó confirmada y ya está activa.",
                "DESTACADO ACTIVO",
                "Tu publicación gana visibilidad",
                body,
                "Ver mis beneficios",
                details.actionLink() == null || details.actionLink().isBlank() ? template.portalUrl("/profile/commercial") : details.actionLink()
        );
        sendHtml(details.to(), subject, html, "PROMOTION_ACTIVATED");
    }

    @Override
    public void sendBackofficeSecurityNotice(String to, String displayName, String action) {
        String safeName = esc(displayName == null || displayName.isBlank() ? "usuario del Backoffice" : displayName);
        String safeAction = esc(action == null ? "Se modificó la configuración de seguridad de tu cuenta." : action);
        String subject = "Seguridad de tu cuenta del Backoffice de Pensiones";
        String body = "<p style=\"margin:0 0 12px;\">Hola <strong>" + safeName + "</strong>,</p>"
                + "<p style=\"margin:0 0 16px;\">" + safeAction + "</p>"
                + template.callout("¿No reconocés este cambio?", "Contactá inmediatamente al responsable del sistema y solicitá la revisión o revocación de tu acceso. Este correo nunca incluye contraseñas, secretos TOTP ni códigos de recuperación.", false);
        String html = template.security(
                "Se registró un cambio de seguridad en tu cuenta administrativa.",
                "Cambio de seguridad en tu cuenta",
                body
        );
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
        String safeMessage = esc(message == null ? "Sin detalle adicional" : message).replace("\n", "<br>");
        String safePath = actionPath == null || actionPath.isBlank() ? "/payment-alerts" : esc(actionPath);
        String rawTitle = title == null ? "Alerta operativa de pagos" : title;
        String prefix = test ? "[PRUEBA] " : "";
        String subject = "[Pensiones][" + (test ? "TEST" : subjectValue(severity == null ? "CRITICAL" : severity)) + "] " + subjectValue(rawTitle);
        String body = "<p style=\"margin:0 0 14px;\">" + (test ? "Esta es una prueba del canal de alertas operativas." : "El monitor operativo detectó una condición que requiere revisión.") + "</p>"
                + template.summaryTable(List.of(
                new BrandedMailTemplate.SummaryRow("Severidad", severity == null ? "CRITICAL" : severity),
                new BrandedMailTemplate.SummaryRow("Categoría", category == null ? "PAYMENT" : category),
                new BrandedMailTemplate.SummaryRow("Ruta en Backoffice", actionPath == null || actionPath.isBlank() ? "/payment-alerts" : actionPath)
        ))
                + "<div style=\"margin:18px 0;padding:16px;background:#FEF3F2;border:1px solid #FECDCA;border-radius:10px;\"><div style=\"font-size:14px;line-height:1.6;color:#7A271A;\">" + safeMessage + "</div></div>"
                + "<p style=\"margin:0;font-size:12px;color:#667085;\">Ruta: <code>" + safePath + "</code>. Las repeticiones de una misma alerta respetan el cooldown configurado.</p>";
        String html = template.operational(
                prefix + rawTitle,
                test ? "PRUEBA OPERATIVA" : "ALERTA · " + safeSeverity + " · " + safeCategory,
                prefix + rawTitle,
                body
        );
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
            MailAddress senderAddress = parseMailAddress(from, "Pensiones");
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
            MailAddress parsedFrom = parseMailAddress(from, "Pensiones");
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
        String fallbackName = defaultName == null || defaultName.isBlank() ? "Pensiones" : defaultName.trim();

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

    private String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String subjectValue(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String formatDate(OffsetDateTime value) {
        return value == null ? "—" : DATE.format(value);
    }

    private String formatMoney(BigDecimal amount, String currency) {
        if (amount == null) return "—";
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(ES_UY);
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        String code = currency == null || currency.isBlank() ? "UYU" : currency.trim().toUpperCase(Locale.ROOT);
        String formatted = format.format(amount);
        return "UYU".equals(code) ? "$ " + formatted + " UYU" : code + " " + formatted;
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
