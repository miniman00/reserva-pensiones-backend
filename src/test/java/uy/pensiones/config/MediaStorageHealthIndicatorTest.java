package uy.pensiones.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;
import uy.pensiones.storage.LocalMediaStorage;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaStorageHealthIndicatorTest {

    @TempDir
    Path tempDir;

    @Test
    void writableStorageWithHeadroomIsUp() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setMinUsableBytes(32L * 1024 * 1024);
        MediaStorageHealthIndicator indicator = new MediaStorageHealthIndicator(new LocalMediaStorage(tempDir.toString()), properties);
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void insufficientHeadroomMakesReadinessDown() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setMinUsableBytes(Long.MAX_VALUE);
        MediaStorageHealthIndicator indicator = new MediaStorageHealthIndicator(new LocalMediaStorage(tempDir.toString()), properties);
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }
}
