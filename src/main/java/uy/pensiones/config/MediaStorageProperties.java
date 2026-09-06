package uy.pensiones.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.media-maintenance")
public class MediaStorageProperties {
    private long minUsableBytes = 128L * 1024 * 1024;
    private boolean cleanupEnabled = false;
    private Duration orphanGrace = Duration.ofHours(24);
    private String cleanupCron = "0 35 3 * * *";
    private String cleanupZone = "UTC";

    public long getMinUsableBytes() { return Math.max(32L * 1024 * 1024, minUsableBytes); }
    public void setMinUsableBytes(long minUsableBytes) { this.minUsableBytes = minUsableBytes; }
    public boolean isCleanupEnabled() { return cleanupEnabled; }
    public void setCleanupEnabled(boolean cleanupEnabled) { this.cleanupEnabled = cleanupEnabled; }
    public Duration getOrphanGrace() { return orphanGrace == null || orphanGrace.isNegative() ? Duration.ofHours(24) : orphanGrace; }
    public void setOrphanGrace(Duration orphanGrace) { this.orphanGrace = orphanGrace; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
    public String getCleanupZone() { return cleanupZone; }
    public void setCleanupZone(String cleanupZone) { this.cleanupZone = cleanupZone; }
}
