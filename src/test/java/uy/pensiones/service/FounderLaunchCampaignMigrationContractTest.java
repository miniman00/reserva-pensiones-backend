package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FounderLaunchCampaignMigrationContractTest {

    @Test
    void migrationAndRepositoryProtectFounderCapacityAndDistinctOwners() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V040__founder_launch_campaign.sql"));
        String repository = Files.readString(Path.of("src/main/java/uy/pensiones/repo/LaunchCampaignRepository.java"));

        assertTrue(migration.contains("granted_count >= 0 AND granted_count <= max_beneficiaries"));
        assertTrue(migration.contains("UNIQUE (campaign_id, user_id)"));
        assertTrue(migration.contains("UNIQUE (campaign_id, granted_order)"));
        assertTrue(repository.contains("PESSIMISTIC_WRITE"));
        assertTrue(migration.contains("'PROPIETARIOS_FUNDADORES'"));
        assertTrue(migration.contains("'PAUSED', 20, 0, 365"));
    }
}
