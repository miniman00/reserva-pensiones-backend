package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uy.pensiones.enums.PaymentAlertSeverity;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.PaymentSettings;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PaymentOperationalAlertNotificationRepository;
import uy.pensiones.repo.PaymentOperationalAlertQueryRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PaymentOperationalAlertEmailService {
    private static final Logger log = LoggerFactory.getLogger(PaymentOperationalAlertEmailService.class);
    private static final int MAX_ALERTS_PER_DIGEST = 50;
    private static final int SCAN_LEASE_MINUTES = 2;
    private static final int DELIVERY_CLAIM_MINUTES = 2;

    private final PaymentRuntimeConfigurationService runtime;
    private final PaymentOperationalAlertQueryRepository alerts;
    private final PaymentOperationalAlertNotificationRepository notifications;
    private final MailService mail;

    public PaymentOperationalAlertEmailService(PaymentRuntimeConfigurationService runtime,
                                               PaymentOperationalAlertQueryRepository alerts,
                                               PaymentOperationalAlertNotificationRepository notifications,
                                               MailService mail) {
        this.runtime = runtime;
        this.alerts = alerts;
        this.notifications = notifications;
        this.mail = mail;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 45000)
    public void schedulerTick() {
        PaymentSettings settings;
        try {
            settings = runtime.settings();
        } catch (RuntimeException ex) {
            log.warn("No se pudo leer la configuración de alertas operativas: {}", ex.getMessage());
            return;
        }
        if (!settings.isOperationalAlertEmailEnabled()) return;

        String owner = "payment-alert-email-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!notifications.tryAcquireScanLease(owner, now.plusMinutes(SCAN_LEASE_MINUTES))) return;

        boolean emailSent = false;
        List<String> errors = new ArrayList<>();
        try {
            List<String> recipients = recipients(settings);
            if (recipients.isEmpty()) {
                errors.add("No hay destinatarios configurados");
                return;
            }

            PaymentAlertSeverity minimum = settings.getOperationalAlertMinSeverity() == null
                    ? PaymentAlertSeverity.CRITICAL : settings.getOperationalAlertMinSeverity();
            int cooldown = clampCooldown(settings.getOperationalAlertCooldownMinutes());
            List<PaymentOperationalAlertQueryRepository.AlertRow> active =
                    alerts.notificationCandidates(minimum.name(), MAX_ALERTS_PER_DIGEST);
            List<ClaimedAlert> claimed = new ArrayList<>();
            for (var alert : active) {
                String token = UUID.randomUUID().toString();
                Long deliveryId = notifications.claimEmail(alert, String.join(",", recipients), token,
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(DELIVERY_CLAIM_MINUTES));
                if (deliveryId != null) claimed.add(new ClaimedAlert(deliveryId, token, alert));
            }
            if (claimed.isEmpty()) return;

            MailService.DeliveryResult result = sendDigest(recipients, claimed);
            if (result.sent()) {
                emailSent = true;
                for (ClaimedAlert item : claimed) {
                    if (!notifications.markSent(item.deliveryId(), item.claimToken(), cooldown)) {
                        errors.add("Se perdió la reserva de la alerta " + item.alert().alertKey());
                    }
                }
            } else {
                String error = result.message() == null ? "Falló el envío del digest" : result.message();
                errors.add(error);
                for (ClaimedAlert item : claimed) notifications.markFailed(item.deliveryId(), item.claimToken(), error);
            }
        } catch (RuntimeException ex) {
            errors.add(safe(ex));
            log.warn("Falló el ciclo de notificación de alertas operativas: {}", ex.getMessage(), ex);
        } finally {
            notifications.completeScan(owner, errors.isEmpty(), emailSent,
                    errors.isEmpty() ? null : String.join(" | ", errors.subList(0, Math.min(errors.size(), 3))));
        }
    }

    public MailService.DeliveryResult sendTest(PaymentSettings settings) {
        List<String> recipients = recipients(settings);
        if (recipients.isEmpty()) return new MailService.DeliveryResult(false, "No hay destinatarios configurados");
        return mail.sendPaymentOperationalAlert(recipients, "TEST", "PAYMENT",
                "Prueba de alertas operativas de pagos",
                "La configuración de correo del Backoffice está conectada correctamente. "
                        + "Este mensaje no corresponde a una incidencia real.",
                "/payment-alerts", true);
    }

    public List<String> recipients(PaymentSettings settings) {
        if (settings == null || settings.getOperationalAlertEmailRecipients() == null
                || settings.getOperationalAlertEmailRecipients().isBlank()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(settings.getOperationalAlertEmailRecipients().split("[,;\\n\\r]+"))
                .map(String::trim).filter(x -> !x.isBlank()).forEach(result::add);
        return List.copyOf(result);
    }

    private MailService.DeliveryResult sendDigest(List<String> recipients, List<ClaimedAlert> claimed) {
        PaymentAlertSeverity highest = claimed.stream().map(x -> severity(x.alert().severity()))
                .min(java.util.Comparator.comparingInt(PaymentAlertSeverity::rank))
                .orElse(PaymentAlertSeverity.CRITICAL);
        if (claimed.size() == 1) {
            var alert = claimed.get(0).alert();
            return mail.sendPaymentOperationalAlert(recipients, alert.severity(), alert.category(), alert.title(),
                    alert.message(), alert.actionPath(), false);
        }
        StringBuilder message = new StringBuilder("Se detectaron ").append(claimed.size())
                .append(" alertas que requieren atención en el mismo ciclo:\n\n");
        for (ClaimedAlert item : claimed) {
            var alert = item.alert();
            message.append("• [").append(alert.severity()).append("][").append(alert.category()).append("] ")
                    .append(alert.title()).append(" — ").append(trim(alert.message(), 240)).append("\n");
        }
        return mail.sendPaymentOperationalAlert(recipients, highest.name(), "OPERATIONS",
                claimed.size() + " alertas operativas de pagos requieren atención",
                message.toString(), "/payment-alerts", false);
    }

    private PaymentAlertSeverity severity(String raw) {
        try {
            return PaymentAlertSeverity.valueOf(raw == null ? "CRITICAL" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return PaymentAlertSeverity.CRITICAL;
        }
    }

    private int clampCooldown(int value) {
        return Math.max(5, Math.min(10080, value <= 0 ? 180 : value));
    }

    private String safe(RuntimeException ex) {
        String value = ex.getMessage();
        if (value == null || value.isBlank()) value = ex.getClass().getSimpleName();
        return trim(value.replace('\n', ' ').replace('\r', ' '), 300);
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        String x = value.trim();
        return x.length() <= max ? x : x.substring(0, max);
    }

    private record ClaimedAlert(Long deliveryId, String claimToken,
                                PaymentOperationalAlertQueryRepository.AlertRow alert) {}
}
