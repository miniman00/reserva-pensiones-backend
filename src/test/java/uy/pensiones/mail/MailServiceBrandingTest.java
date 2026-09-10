package uy.pensiones.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import uy.pensiones.service.MailOutboxService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MailServiceBrandingTest {

    private MailOutboxService outbox;
    private MailServiceImpl mail;

    @BeforeEach
    void setUp() {
        outbox = mock(MailOutboxService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> senders = mock(ObjectProvider.class);
        BrandedMailTemplate template = new BrandedMailTemplate(
                "https://pensiones.codevaru.com",
                "soporte@codevaru.com",
                ""
        );
        mail = new MailServiceImpl(
                senders,
                RestClient.builder(),
                true,
                "smtp",
                "no-reply@codevaru.com",
                "",
                "https://api.brevo.com/v3/smtp/email",
                outbox,
                template
        );
    }

    @Test
    void customerEmailsUseTheSamePensionesBrandShell() {
        OffsetDateTime start = OffsetDateTime.parse("2026-09-10T12:00:00Z");

        mail.sendVerifyEmail("owner@example.com", "https://pensiones.codevaru.com/verify?t=abc");
        mail.sendPensionInquiry("owner@example.com", "Residencia Centro", "Ana", "ana@example.com",
                "099123123", "SIMPLE", "15/09/2026", "Hola, ¿hay lugar?");
        mail.sendFounderBenefitNotice("owner@example.com", "María", "Tu beneficio fundador sigue activo",
                "Tenés visibilidad incluida.", "https://pensiones.codevaru.com/profile/commercial");
        mail.sendSubscriptionActivated(new MailService.SubscriptionActivatedMail(
                "owner@example.com", "María", "Business", "Esencial", "Plan para residencias.",
                start, start.plusMonths(1), 1, new BigDecimal("1290.00"), "UYU", "pay_123",
                List.of("10 pensiones publicadas", "Analítica avanzada"),
                "https://pensiones.codevaru.com/profile/commercial"
        ));
        mail.sendPromotionActivated(new MailService.PromotionActivatedMail(
                "owner@example.com", "María", "Residencia Centro", "Destacado 7 días", "Mayor visibilidad.",
                start, start.plusDays(7), new BigDecimal("390.00"), "UYU", "pay_456",
                "https://pensiones.codevaru.com/profile/commercial"
        ));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(5)).enqueue(anyString(), anyString(), html.capture(), any(), anyString());

        assertEquals(5, html.getAllValues().size());
        for (String value : html.getAllValues()) {
            assertTrue(value.startsWith("<!doctype html>"));
            assertTrue(value.contains("pensions-portal-mark.png"));
            assertTrue(value.contains("soporte@codevaru.com"));
            assertTrue(value.contains(">Pensiones<"));
        }
    }

    @Test
    void planChangeEmailContainsCommercialSummaryAndBenefits() {
        OffsetDateTime start = OffsetDateTime.parse("2026-09-10T12:00:00Z");
        mail.sendSubscriptionActivated(new MailService.SubscriptionActivatedMail(
                "owner@example.com", "María", "Business", "Esencial", "Plan para residencias.",
                start, start.plusMonths(1), 1, new BigDecimal("1290.00"), "UYU", "pay_123",
                List.of("10 pensiones publicadas", "20 días de destacado incluidos", "Analítica avanzada"),
                null
        ));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(anyString(), subject.capture(), html.capture(), isNull(), anyString());

        assertTrue(subject.getValue().contains("Business"));
        assertTrue(html.getValue().contains("Plan anterior"));
        assertTrue(html.getValue().contains("Esencial"));
        assertTrue(html.getValue().contains("Tus beneficios"));
        assertTrue(HtmlUtils.htmlUnescape(html.getValue()).contains("Analítica avanzada"));
        assertTrue(html.getValue().contains("$ 1.290,00 UYU"));
    }
}
