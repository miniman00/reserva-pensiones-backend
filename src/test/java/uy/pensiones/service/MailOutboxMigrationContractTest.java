package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MailOutboxMigrationContractTest {
    @Test
    void migrationAndRepositoryUseMultiInstanceSafeClaiming() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V039__mail_outbox.sql"));
        String repository = Files.readString(Path.of("src/main/java/uy/pensiones/repo/MailOutboxRepository.java"));

        assertTrue(migration.contains("mail_outbox"));
        assertTrue(migration.contains("idx_mail_outbox_pending_delivery"));
        assertTrue(repository.toLowerCase().contains("for update skip locked"));
        assertTrue(repository.contains("lease_until"));
    }
}
