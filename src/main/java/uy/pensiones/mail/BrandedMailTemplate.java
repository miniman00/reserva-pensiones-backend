package uy.pensiones.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

@Component
public class BrandedMailTemplate {

    private static final String BRAND_BLUE = "#0B74DE";
    private static final String BRAND_GREEN = "#16A36A";
    private static final String BRAND_DARK = "#172033";
    private static final String BRAND_MUTED = "#667085";
    private static final String BRAND_BG = "#F4F7FB";
    private static final String BRAND_BORDER = "#E4EAF2";

    private final String frontendUrl;
    private final String supportEmail;
    private final String configuredLogoUrl;

    public BrandedMailTemplate(@Value("${app.frontend-url:http://localhost:5173}") String frontendUrl,
                               @Value("${app.mail.support-email:soporte@codevaru.com}") String supportEmail,
                               @Value("${app.mail.brand.logo-url:}") String configuredLogoUrl) {
        this.frontendUrl = trimTrailingSlash(frontendUrl);
        this.supportEmail = supportEmail == null || supportEmail.isBlank()
                ? "soporte@codevaru.com"
                : supportEmail.trim();
        this.configuredLogoUrl = configuredLogoUrl == null ? "" : configuredLogoUrl.trim();
    }

    public String customer(String preheader,
                           String eyebrow,
                           String title,
                           String bodyHtml,
                           String ctaLabel,
                           String ctaUrl) {
        return render(preheader, eyebrow, title, bodyHtml, ctaLabel, ctaUrl, BRAND_BLUE, false);
    }

    public String positive(String preheader,
                           String eyebrow,
                           String title,
                           String bodyHtml,
                           String ctaLabel,
                           String ctaUrl) {
        return render(preheader, eyebrow, title, bodyHtml, ctaLabel, ctaUrl, BRAND_GREEN, false);
    }

    public String security(String preheader,
                           String title,
                           String bodyHtml) {
        return render(preheader, "SEGURIDAD", title, bodyHtml, null, null, "#B54708", false);
    }

    public String operational(String preheader,
                              String eyebrow,
                              String title,
                              String bodyHtml) {
        return render(preheader, eyebrow, title, bodyHtml, null, null, "#B42318", true);
    }

    public String portalUrl(String path) {
        if (path == null || path.isBlank()) return frontendUrl;
        return path.startsWith("/") ? frontendUrl + path : frontendUrl + "/" + path;
    }

    public String summaryTable(List<SummaryRow> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder html = new StringBuilder(512);
        html.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"border-collapse:collapse;background:#F8FAFC;border:1px solid ")
                .append(BRAND_BORDER)
                .append(";border-radius:12px;overflow:hidden;margin:20px 0;\">");
        for (SummaryRow row : rows) {
            if (row == null || row.value() == null || row.value().isBlank()) continue;
            html.append("<tr><td style=\"padding:11px 16px;color:")
                    .append(BRAND_MUTED)
                    .append(";font-size:13px;border-bottom:1px solid #EEF2F6;width:42%;\">")
                    .append(esc(row.label()))
                    .append("</td><td style=\"padding:11px 16px;color:")
                    .append(BRAND_DARK)
                    .append(";font-size:14px;font-weight:700;border-bottom:1px solid #EEF2F6;\">")
                    .append(esc(row.value()))
                    .append("</td></tr>");
        }
        return html.append("</table>").toString();
    }

    public String benefits(List<String> benefits) {
        if (benefits == null || benefits.isEmpty()) return "";
        StringBuilder html = new StringBuilder(640);
        html.append("<div style=\"margin:22px 0 8px;\"><div style=\"font-size:15px;font-weight:800;color:")
                .append(BRAND_DARK)
                .append(";margin-bottom:10px;\">Tus beneficios</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"border-collapse:collapse;\">");
        for (String benefit : benefits) {
            if (benefit == null || benefit.isBlank()) continue;
            html.append("<tr><td valign=\"top\" style=\"width:24px;padding:5px 8px 5px 0;color:")
                    .append(BRAND_GREEN)
                    .append(";font-size:18px;font-weight:800;\">✓</td><td style=\"padding:5px 0;color:#344054;font-size:14px;line-height:1.5;\">")
                    .append(esc(benefit))
                    .append("</td></tr>");
        }
        return html.append("</table></div>").toString();
    }

    public String callout(String title, String text, boolean positive) {
        String background = positive ? "#ECFDF3" : "#FFF7ED";
        String border = positive ? "#ABEFC6" : "#FED7AA";
        String heading = positive ? "#067647" : "#9A3412";
        return "<div style=\"margin:18px 0;padding:14px 16px;background:" + background
                + ";border:1px solid " + border + ";border-radius:10px;\"><div style=\"font-size:14px;font-weight:800;color:"
                + heading + ";margin-bottom:4px;\">" + esc(title) + "</div><div style=\"font-size:13px;line-height:1.55;color:#475467;\">"
                + esc(text) + "</div></div>";
    }

    private String render(String preheader,
                          String eyebrow,
                          String title,
                          String bodyHtml,
                          String ctaLabel,
                          String ctaUrl,
                          String accent,
                          boolean operational) {
        String safePreheader = esc(preheader == null ? title : preheader);
        String safeEyebrow = esc(eyebrow == null ? "PENSIONES" : eyebrow);
        String safeTitle = esc(title == null ? "Pensiones" : title);
        String logo = escAttr(logoUrl());
        String support = esc(supportEmail);
        String supportHref = escAttr("mailto:" + supportEmail);
        String homeHref = escAttr(frontendUrl);
        String button = "";
        if (ctaLabel != null && !ctaLabel.isBlank() && ctaUrl != null && !ctaUrl.isBlank()) {
            button = "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin:26px 0 8px;\"><tr><td style=\"border-radius:9px;background:"
                    + accent + ";\"><a href=\"" + escAttr(ctaUrl) + "\" style=\"display:inline-block;padding:13px 22px;color:#FFFFFF;text-decoration:none;font-size:14px;font-weight:800;border-radius:9px;\">"
                    + esc(ctaLabel) + "</a></td></tr></table>";
        }
        String footerDescriptor = operational
                ? "Mensaje operativo generado por Pensiones."
                : "Recibís este correo por una acción o servicio relacionado con tu cuenta en Pensiones.";

        return "<!doctype html><html lang=\"es\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light\"><meta name=\"supported-color-schemes\" content=\"light\"><title>" + safeTitle + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:" + BRAND_BG + ";font-family:Arial,'Helvetica Neue',Helvetica,sans-serif;color:" + BRAND_DARK + ";\">"
                + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;\">" + safePreheader + "</div>"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"width:100%;background:" + BRAND_BG + ";border-collapse:collapse;\"><tr><td align=\"center\" style=\"padding:28px 14px;\">"
                + "<table role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\" style=\"width:100%;max-width:640px;border-collapse:separate;background:#FFFFFF;border:1px solid " + BRAND_BORDER + ";border-radius:18px;overflow:hidden;box-shadow:0 8px 30px rgba(23,32,51,.08);\">"
                + "<tr><td style=\"padding:24px 30px 20px;border-bottom:1px solid #EEF2F6;\"><table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\"><tr>"
                + "<td valign=\"middle\" style=\"padding-right:12px;\"><img src=\"" + logo + "\" width=\"52\" height=\"52\" alt=\"Pensiones\" style=\"display:block;width:52px;height:52px;border:0;border-radius:12px;\"></td>"
                + "<td valign=\"middle\"><div style=\"font-size:21px;line-height:1.1;font-weight:900;letter-spacing:.2px;color:" + BRAND_DARK + ";\">Pensiones</div>"
                + "<div style=\"margin-top:4px;font-size:12px;color:" + BRAND_MUTED + ";\">Encontrá y gestioná alojamiento con confianza</div></td></tr></table></td></tr>"
                + "<tr><td style=\"padding:30px;\"><div style=\"font-size:11px;font-weight:900;letter-spacing:1.1px;color:" + accent + ";margin-bottom:9px;\">" + safeEyebrow + "</div>"
                + "<h1 style=\"margin:0 0 14px;font-size:27px;line-height:1.22;color:" + BRAND_DARK + ";font-weight:900;\">" + safeTitle + "</h1>"
                + "<div style=\"font-size:15px;line-height:1.65;color:#344054;\">" + (bodyHtml == null ? "" : bodyHtml) + "</div>"
                + button + "</td></tr>"
                + "<tr><td style=\"padding:20px 30px 24px;background:#FBFCFE;border-top:1px solid #EEF2F6;\"><div style=\"font-size:12px;line-height:1.55;color:#7A8496;\">"
                + footerDescriptor + "<br>¿Necesitás ayuda? Escribinos a <a href=\"" + supportHref + "\" style=\"color:" + BRAND_BLUE + ";text-decoration:none;font-weight:700;\">" + support + "</a>.</div>"
                + "<div style=\"margin-top:12px;font-size:11px;color:#98A2B3;\"><a href=\"" + homeHref + "\" style=\"color:#667085;text-decoration:none;\">Pensiones</a> · Uruguay</div></td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private String logoUrl() {
        if (!configuredLogoUrl.isBlank()) return configuredLogoUrl;
        return portalUrl("/brand/pensions-portal-mark.png");
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:5173";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private static String escAttr(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    public record SummaryRow(String label, String value) {
    }
}
