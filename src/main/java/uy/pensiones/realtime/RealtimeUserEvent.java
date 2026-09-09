package uy.pensiones.realtime;

public record RealtimeUserEvent(Long userId, RealtimeEvent event) {
}
