package uy.pensiones.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.service.PensionCatalogQualityService;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicSeoControllerTest {

    @Test
    void sitemapContainsOnlyRepositoryEntriesAndFrontendUrls() {
        PensionRepository repo = mock(PensionRepository.class);
        PensionRepository.SitemapEntry entry = mock(PensionRepository.SitemapEntry.class);
        when(entry.getId()).thenReturn(42L);
        when(entry.getUpdatedAt()).thenReturn(OffsetDateTime.parse("2026-08-26T10:15:30Z"));
        PensionCatalogQualityService quality = mock(PensionCatalogQualityService.class);
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-07-01T00:00:00Z");
        when(quality.publicAvailabilityCutoff()).thenReturn(cutoff);
        when(repo.findPublicSitemapEntries(PensionStatus.PUBLISHED, cutoff)).thenReturn(List.of(entry));

        AppProperties props = new AppProperties();
        props.setFrontendUrl("https://pensiones.example/");

        PublicSeoController controller = new PublicSeoController(repo, props, quality);
        ResponseEntity<String> response = controller.sitemap();

        assertThat(response.getHeaders().getCacheControl()).contains("max-age=600");
        assertThat(response.getBody())
                .contains("<loc>https://pensiones.example/</loc>")
                .contains("<loc>https://pensiones.example/pensions/42</loc>")
                .contains("<lastmod>2026-08-26</lastmod>")
                .doesNotContain("/profile/");
    }
}
