package uy.pensiones.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.service.PensionCatalogQualityService;

import java.time.Duration;
import java.time.format.DateTimeFormatter;

@RestController
public class PublicSeoController {

    private static final DateTimeFormatter LAST_MOD = DateTimeFormatter.ISO_LOCAL_DATE;

    private final PensionRepository pensions;
    private final AppProperties appProperties;
    private final PensionCatalogQualityService catalogQuality;

    public PublicSeoController(PensionRepository pensions, AppProperties appProperties,
                               PensionCatalogQualityService catalogQuality) {
        this.pensions = pensions;
        this.appProperties = appProperties;
        this.catalogQuality = catalogQuality;
    }

    @GetMapping(value = {"/sitemap.xml", "/api/public/seo/sitemap.xml"}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        String frontend = trimTrailingSlash(appProperties.getFrontendUrl());
        StringBuilder xml = new StringBuilder(4096)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
                .append(url(frontend + "/", null, "1.0"))
                .append(url(frontend + "/terms", null, "0.2"))
                .append(url(frontend + "/privacy", null, "0.2"));

        for (var item : pensions.findPublicSitemapEntries(PensionStatus.PUBLISHED, catalogQuality.publicAvailabilityCutoff())) {
            String lastMod = item.getUpdatedAt() == null ? null : LAST_MOD.format(item.getUpdatedAt());
            xml.append(url(frontend + "/pensions/" + item.getId(), lastMod, "0.8"));
        }

        xml.append("</urlset>");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    @GetMapping(value = {"/robots.txt", "/api/public/seo/robots.txt"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        String sitemapUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/sitemap.xml")
                .build()
                .toUriString();
        String body = """
                User-agent: *
                Allow: /
                Disallow: /api/
                Disallow: /profile/
                Disallow: /dashboard
                Disallow: /legal/accept

                Sitemap: %s
                """.formatted(sitemapUrl);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    private static String url(String loc, String lastMod, String priority) {
        StringBuilder value = new StringBuilder("<url><loc>")
                .append(xml(loc))
                .append("</loc>");
        if (lastMod != null) {
            value.append("<lastmod>").append(lastMod).append("</lastmod>");
        }
        value.append("<priority>").append(priority).append("</priority></url>");
        return value.toString();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:5173";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
