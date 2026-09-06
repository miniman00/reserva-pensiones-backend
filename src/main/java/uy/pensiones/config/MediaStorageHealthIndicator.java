package uy.pensiones.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import uy.pensiones.storage.MediaStorage;


@Component("mediaStorage")
public class MediaStorageHealthIndicator implements HealthIndicator {

    private final MediaStorage storage;
    private final MediaStorageProperties properties;

    public MediaStorageHealthIndicator(MediaStorage storage, MediaStorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public Health health() {
        MediaStorage.StorageStatus status = storage.health(properties.getMinUsableBytes());
        if (!status.available()) {
            Health.Builder down = Health.down()
                    .withDetail("provider", storage.provider())
                    .withDetail("reason", status.reason() == null ? "media-storage-unavailable" : status.reason());
            if (status.usableBytes() != null) {
                down.withDetail("usableBytes", status.usableBytes())
                        .withDetail("requiredBytes", properties.getMinUsableBytes());
            }
            return down.build();
        }
        Health.Builder up = Health.up().withDetail("provider", storage.provider());
        if (status.usableBytes() != null) up.withDetail("usableBytes", status.usableBytes());
        return up.build();
    }
}
