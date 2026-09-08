package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerTrialLifecycleMigrationContractTest {

    @Test
    void migrationPersistsSingleUseTrialAndCommercialPauseMarkers() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V044__owner_trial_lifecycle.sql"));

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS owner_trial_settings"));
        assertTrue(migration.contains("duration_days INTEGER NOT NULL DEFAULT 90"));
        assertTrue(migration.contains("grace_days INTEGER NOT NULL DEFAULT 7"));
        assertTrue(migration.contains("UNIQUE (user_id)"));
        assertTrue(migration.contains("'TRIAL_STARTED', 'FOUNDER_GRANTED', 'PAID_DIRECT'"));
        assertTrue(migration.contains("commercial_pause_reason"));
        assertTrue(migration.contains("source = 'PAYMENT'"));
        assertTrue(migration.contains("'FOUNDER_GRANTED'"));
    }
}
