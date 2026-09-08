package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPeriodPricingMigrationContractTest {

    @Test
    void migrationBackfillsLegacyMonthlyPricingAndRestrictsSupportedPeriods() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V043__plan_period_prices.sql"));

        assertTrue(migration.contains("period_months IN (1, 3, 6, 12)"));
        assertTrue(migration.contains("UNIQUE (plan_version_id, period_months)"));
        assertTrue(migration.contains("SELECT id, 3, monthly_price * 3"));
        assertTrue(migration.contains("SELECT id, 12, monthly_price * 12"));
        assertTrue(migration.contains("ON DELETE CASCADE"));
    }
}
