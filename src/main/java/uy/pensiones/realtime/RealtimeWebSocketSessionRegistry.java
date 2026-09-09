package uy.pensiones.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeWebSocketSessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketSessionRegistry.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> sessionsByUser = new ConcurrentHashMap<>();

    public RealtimeWebSocketSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WebSocketSession register(Long userId, WebSocketSession session) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        connections.put(session.getId(), new Connection(userId, safeSession));
        sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session.getId());
        return safeSession;
    }

    public void unregister(String sessionId) {
        if (sessionId == null) return;
        Connection connection = connections.remove(sessionId);
        if (connection == null) return;
        Set<String> ids = sessionsByUser.get(connection.userId());
        if (ids == null) return;
        ids.remove(sessionId);
        if (ids.isEmpty()) sessionsByUser.remove(connection.userId(), ids);
    }

    public void sendPong(String sessionId) {
        Connection connection = connections.get(sessionId);
        if (connection == null || !connection.session().isOpen()) return;
        try {
            connection.session().sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
        } catch (IOException | RuntimeException ex) {
            unregister(sessionId);
        }
    }

    public void send(Long userId, RealtimeEvent event) {
        if (userId == null || event == null) return;
        Set<String> ids = sessionsByUser.get(userId);
        if (ids == null || ids.isEmpty()) return;
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            log.warn("realtime_event_serialization_failed userId={} type={} error={}", userId, event.type(), ex.getMessage());
            return;
        }
        for (String sessionId : Set.copyOf(ids)) {
            Connection connection = connections.get(sessionId);
            if (connection == null) continue;
            WebSocketSession session = connection.session();
            if (!session.isOpen()) {
                unregister(sessionId);
                continue;
            }
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (IOException | RuntimeException ex) {
                log.warn("realtime_event_send_failed userId={} sessionId={} type={} error={}",
                        userId, sessionId, event.type(), ex.getMessage());
                unregister(sessionId);
                try {
                    session.close();
                } catch (IOException ignored) {
                    // La sesión ya no es utilizable.
                }
            }
        }
    }

    int connectionCount(Long userId) {
        Set<String> ids = sessionsByUser.get(userId);
        return ids == null ? 0 : ids.size();
    }

    private record Connection(Long userId, WebSocketSession session) {}
}
