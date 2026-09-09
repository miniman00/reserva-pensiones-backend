package uy.pensiones.realtime;

import java.time.OffsetDateTime;
import java.util.Map;

public record RealtimeEvent(
        String eventId,
        String type,
        Long entityId,
        OffsetDateTime occurredAt,
        Map<String, Object> data
) {
}
