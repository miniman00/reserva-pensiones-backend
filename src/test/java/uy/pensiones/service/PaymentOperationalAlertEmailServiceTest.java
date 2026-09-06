package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uy.pensiones.enums.PaymentAlertSeverity;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.PaymentSettings;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PaymentOperationalAlertNotificationRepository;
import uy.pensiones.repo.PaymentOperationalAlertQueryRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentOperationalAlertEmailServiceTest {
    private final PaymentRuntimeConfigurationService runtime = mock(PaymentRuntimeConfigurationService.class);
    private final PaymentOperationalAlertQueryRepository alerts = mock(PaymentOperationalAlertQueryRepository.class);
    private final PaymentOperationalAlertNotificationRepository notifications = mock(PaymentOperationalAlertNotificationRepository.class);
    private final MailService mail = mock(MailService.class);
    private PaymentOperationalAlertEmailService service;
    private PaymentSettings settings;

    @BeforeEach
    void setUp() {
        service = new PaymentOperationalAlertEmailService(runtime, alerts, notifications, mail);
        settings = PaymentSettings.builder()
                .id((short) 1)
                .operationalAlertEmailEnabled(true)
                .operationalAlertEmailRecipients("ops@codevaru.com,soporte@codevaru.com")
                .operationalAlertMinSeverity(PaymentAlertSeverity.HIGH)
                .operationalAlertCooldownMinutes(180)
                .build();
        when(runtime.settings()).thenReturn(settings);
        when(notifications.tryAcquireScanLease(anyString(), any())).thenReturn(true);
    }

    @Test
    void disabledConfigurationDoesNotAcquireLeaseOrQueryAlerts() {
        settings.setOperationalAlertEmailEnabled(false);

        service.schedulerTick();

        verify(notifications, never()).tryAcquireScanLease(anyString(), any());
        verifyNoInteractions(alerts, mail);
    }

    @Test
    void oneDueAlertIsSentAndReceivesConfiguredCooldown() {
        var alert = alert("PAYMENT_FULFILLMENT:10", "CRITICAL", "PAYMENT", "Pago aprobado sin beneficio");
        when(alerts.notificationCandidates("HIGH", 50)).thenReturn(List.of(alert));
        when(notifications.claimEmail(eq(alert), anyString(), anyString(), any())).thenReturn(71L);
        when(mail.sendPaymentOperationalAlert(anyList(), eq("CRITICAL"), eq("PAYMENT"), eq(alert.title()),
                eq(alert.message()), eq(alert.actionPath()), eq(false)))
                .thenReturn(new MailService.DeliveryResult(true, "ok"));
        when(notifications.markSent(eq(71L), anyString(), eq(180))).thenReturn(true);

        service.schedulerTick();

        verify(mail).sendPaymentOperationalAlert(eq(List.of("ops@codevaru.com", "soporte@codevaru.com")),
                eq("CRITICAL"), eq("PAYMENT"), eq(alert.title()), eq(alert.message()), eq(alert.actionPath()), eq(false));
        verify(notifications).markSent(eq(71L), anyString(), eq(180));
        verify(notifications).completeScan(anyString(), eq(true), eq(true), isNull());
    }

    @Test
    void severalDueAlertsAreGroupedIntoOneDigest() {
        var critical = alert("A", "CRITICAL", "RECONCILIATION", "Lease vencido");
        var high = alert("B", "HIGH", "REFUND", "Refund incierto");
        when(alerts.notificationCandidates("HIGH", 50)).thenReturn(List.of(critical, high));
        when(notifications.claimEmail(eq(critical), anyString(), anyString(), any())).thenReturn(1L);
        when(notifications.claimEmail(eq(high), anyString(), anyString(), any())).thenReturn(2L);
        when(mail.sendPaymentOperationalAlert(anyList(), eq("CRITICAL"), eq("OPERATIONS"), anyString(), anyString(),
                eq("/payment-alerts"), eq(false))).thenReturn(new MailService.DeliveryResult(true, "ok"));
        when(notifications.markSent(anyLong(), anyString(), anyInt())).thenReturn(true);

        service.schedulerTick();

        var title = org.mockito.ArgumentCaptor.forClass(String.class);
        var message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail).sendPaymentOperationalAlert(anyList(), eq("CRITICAL"), eq("OPERATIONS"), title.capture(),
                message.capture(), eq("/payment-alerts"), eq(false));
        assertThat(title.getValue()).contains("2 alertas");
        assertThat(message.getValue()).contains("Lease vencido").contains("Refund incierto");
        verify(notifications).markSent(eq(1L), anyString(), eq(180));
        verify(notifications).markSent(eq(2L), anyString(), eq(180));
    }

    @Test
    void cooldownClaimPreventsRepeatedEmail() {
        var alert = alert("PAYMENT_FULFILLMENT:10", "CRITICAL", "PAYMENT", "Pago aprobado sin beneficio");
        when(alerts.notificationCandidates("HIGH", 50)).thenReturn(List.of(alert));
        when(notifications.claimEmail(eq(alert), anyString(), anyString(), any())).thenReturn(null);

        service.schedulerTick();

        verifyNoInteractions(mail);
        verify(notifications).completeScan(anyString(), eq(true), eq(false), isNull());
    }

    @Test
    void failedTransportSchedulesRetryAndExposesScanError() {
        var alert = alert("AUTO_RECONCILIATION_STALE", "CRITICAL", "RECONCILIATION", "Conciliación atrasada");
        when(alerts.notificationCandidates("HIGH", 50)).thenReturn(List.of(alert));
        when(notifications.claimEmail(eq(alert), anyString(), anyString(), any())).thenReturn(9L);
        when(mail.sendPaymentOperationalAlert(anyList(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(new MailService.DeliveryResult(false, "Brevo respondió 503"));
        when(notifications.markFailed(eq(9L), anyString(), anyString())).thenReturn(true);

        service.schedulerTick();

        verify(notifications).markFailed(eq(9L), anyString(), eq("Brevo respondió 503"));
        verify(notifications).completeScan(anyString(), eq(false), eq(false), contains("Brevo respondió 503"));
    }

    @Test
    void testEmailUsesSavedRecipientsAndDoesNotDependOnSchedulerLease() {
        when(mail.sendPaymentOperationalAlert(anyList(), eq("TEST"), eq("PAYMENT"), anyString(), anyString(),
                eq("/payment-alerts"), eq(true))).thenReturn(new MailService.DeliveryResult(true, "Correo enviado"));

        var result = service.sendTest(settings);

        assertThat(result.sent()).isTrue();
        verify(mail).sendPaymentOperationalAlert(eq(List.of("ops@codevaru.com", "soporte@codevaru.com")),
                eq("TEST"), eq("PAYMENT"), anyString(), anyString(), eq("/payment-alerts"), eq(true));
        verify(notifications, never()).tryAcquireScanLease(anyString(), any());
    }

    private PaymentOperationalAlertQueryRepository.AlertRow alert(String key, String severity, String category, String title) {
        return new PaymentOperationalAlertQueryRepository.AlertRow(key, key, severity, category, title,
                "Detalle de " + title, "PAYMENT", "10", "MERCADO_PAGO",
                OffsetDateTime.now(ZoneOffset.UTC), null, "/payment-alerts", 0);
    }
}
