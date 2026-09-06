package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FounderCommercialIntegrationContractTest {

    @Test
    void founderBenefitsSnapshotPlanAndUseExplicitPromotionSourceWithoutPayments() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V041__founder_entitlements_and_featured_slots.sql"));
        String promotionSource = Files.readString(Path.of(
                "src/main/java/uy/pensiones/enums/PensionPromotionSource.java"));
        String entitlementSource = Files.readString(Path.of(
                "src/main/java/uy/pensiones/enums/EntitlementSource.java"));
        String founderService = Files.readString(Path.of(
                "src/main/java/uy/pensiones/service/FounderFeaturedBenefitService.java"));

        assertTrue(migration.contains("plan_version_id"));
        assertTrue(migration.contains("REFERENCES plan_versions(id)"));
        assertTrue(promotionSource.contains("LAUNCH_CAMPAIGN"));
        assertTrue(entitlementSource.contains("LAUNCH_CAMPAIGN"));
        assertTrue(founderService.contains("PensionPromotionSource.LAUNCH_CAMPAIGN"));
        assertTrue(founderService.contains(".payment(null)"));
        assertTrue(founderService.contains(".price(BigDecimal.ZERO)"));
    }
}
