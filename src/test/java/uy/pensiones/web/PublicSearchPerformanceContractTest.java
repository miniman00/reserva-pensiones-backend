package uy.pensiones.web;

import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import uy.pensiones.model.Pension;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicSearchPerformanceContractTest {

    @Test
    void publicCardCollectionsAreBatchFetchedInsteadOfOneQueryPerPension() throws Exception {
        assertBatchSize("amenities", 100);
        assertBatchSize("nearbyTags", 100);
        assertBatchSize("studyCenters", 100);
    }

    @Test
    void promotionAttributionLookupIsBoundToCurrentResultIds() throws Exception {
        Method global = PensionPromotionRepository.class.getMethod(
                "findEffectiveGlobalExposuresForPensions", OffsetDateTime.class, List.class);
        Query globalQuery = global.getAnnotation(Query.class);

        Method studyCenter = PensionPromotionRepository.class.getMethod(
                "findEffectiveExposuresForStudyCenterAndPensions",
                OffsetDateTime.class, Long.class, List.class);
        Query studyCenterQuery = studyCenter.getAnnotation(Query.class);

        assertThat(globalQuery.value()).contains("pp.pension_id IN (:pensionIds)");
        assertThat(studyCenterQuery.value()).contains("pp.pension_id IN (:pensionIds)");
    }

    @Test
    void studyCenterAutocompleteFiltersAndLimitsInsideDatabase() throws Exception {
        Method method = PensionRepository.class.getMethod("findPublicStudyCenters", String.class, OffsetDateTime.class, int.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query.value())
                .contains("LOWER(psc.study_center) LIKE")
                .contains("p.availability_updated_at >= :availabilityCutoff")
                .contains("LIMIT :limit");
    }

    @Test
    void publicSearchMigrationAddsTrigramAndAvailabilityIndexes() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V037__public_search_query_plan_optimization.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("CREATE EXTENSION IF NOT EXISTS pg_trgm")
                    .contains("pension_public_search_text")
                    .contains("idx_pensions_public_search_trgm_v3")
                    .contains("idx_pension_study_centers_lower_trgm_v3")
                    .contains("idx_pensions_public_available_simple_v3")
                    .contains("idx_pensions_public_available_matrimonial_v3");
        }
    }

    private void assertBatchSize(String fieldName, int expected) throws Exception {
        BatchSize annotation = Pension.class.getDeclaredField(fieldName).getAnnotation(BatchSize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.size()).isEqualTo(expected);
    }
}
