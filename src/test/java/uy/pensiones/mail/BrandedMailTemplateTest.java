package uy.pensiones.mail;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandedMailTemplateTest {

    @Test
    void customerTemplateUsesPensionesBrandAndAbsoluteLogo() {
        BrandedMailTemplate template = new BrandedMailTemplate(
                "https://pensiones.codevaru.com/",
                "soporte@codevaru.com",
                ""
        );

        String html = template.customer(
                "Preheader de prueba",
                "PLAN ACTIVO",
                "Tu plan ya está activo",
                "<p>Contenido seguro</p>",
                "Ver mi plan",
                "https://pensiones.codevaru.com/profile/commercial"
        );

        assertTrue(html.contains("https://pensiones.codevaru.com/brand/pensions-portal-mark.png"));
        assertTrue(html.contains("Pensiones"));
        assertTrue(html.contains("soporte@codevaru.com"));
        assertTrue(html.contains("Ver mi plan"));
        assertTrue(html.contains("Preheader de prueba"));
    }

    @Test
    void summaryAndBenefitsEscapeDynamicText() {
        BrandedMailTemplate template = new BrandedMailTemplate(
                "https://pensiones.codevaru.com",
                "soporte@codevaru.com",
                ""
        );

        String summary = template.summaryTable(List.of(
                new BrandedMailTemplate.SummaryRow("Plan", "Pro <script>")
        ));
        String benefits = template.benefits(List.of("Analítica <avanzada>"));

        assertTrue(summary.contains("Pro &lt;script&gt;"));
        assertFalse(summary.contains("Pro <script>"));
        assertTrue(benefits.contains("&lt;avanzada&gt;"));
        assertFalse(benefits.contains("<avanzada>"));
        assertTrue(HtmlUtils.htmlUnescape(benefits).contains("Analítica <avanzada>"));
    }
}
