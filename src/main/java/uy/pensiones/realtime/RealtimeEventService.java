package uy.pensiones.realtime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class RealtimeEventService {

    private final ApplicationEventPublisher events;

    public RealtimeEventService(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void publishToUser(Long userId, String type, Long entityId, Map<String, ?> data) {
        if (userId == null || userId <= 0 || type == null || type.isBlank()) return;
        Map<String, Object> safeData = new LinkedHashMap<>();
        if (data != null) {
            data.forEach((key, value) -> {
                if (key != null && !key.isBlank()) safeData.put(key, value);
            });
        }
        events.publishEvent(new RealtimeUserEvent(
                userId,
                new RealtimeEvent(UUID.randomUUID().toString(), type.trim(), entityId,
                        OffsetDateTime.now(ZoneOffset.UTC), Collections.unmodifiableMap(new LinkedHashMap<>(safeData)))
        ));
    }
}
