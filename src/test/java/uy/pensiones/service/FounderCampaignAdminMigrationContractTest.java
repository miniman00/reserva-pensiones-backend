package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FounderCampaignAdminMigrationContractTest {

    @Test
    void founderFeaturedLimitIsSnapshottedPerBeneficiary() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V042__founder_campaign_admin_snapshot.sql"));
        String beneficiary = Files.readString(Path.of(
                "src/main/java/uy/pensiones/model/LaunchCampaignBeneficiary.java"));
        String featured = Files.readString(Path.of(
                "src/main/java/uy/pensiones/service/FounderFeaturedBenefitService.java"));

        assertTrue(migration.contains("max_featured_pensions_snapshot"));
        assertTrue(migration.contains("SET NOT NULL"));
        assertTrue(beneficiary.contains("maxFeaturedPensionsSnapshot"));
        assertTrue(featured.contains("getMaxFeaturedPensionsSnapshot"));
    }
}
