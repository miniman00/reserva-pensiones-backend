package uy.pensiones.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;

import java.security.Principal;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private final UserRepository users;
    private final RealtimeWebSocketSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public RealtimeWebSocketHandler(UserRepository users,
                                    RealtimeWebSocketSessionRegistry registry,
                                    ObjectMapper objectMapper) {
        this.users = users;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = resolveUserId(session.getPrincipal());
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Usuario no identificado"));
            return;
        }
        registry.register(userId, session);
        log.debug("realtime_socket_connected userId={} sessionId={}", userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!isPing(message.getPayload())) return;
        registry.sendPong(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session.getId());
        log.debug("realtime_socket_disconnected sessionId={} code={}", session.getId(), status.getCode());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        registry.unregister(session.getId());
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    private boolean isPing(String payload) {
        if (payload == null || payload.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(payload);
            return "PING".equalsIgnoreCase(node.path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private Long resolveUserId(Principal principal) {
        if (!(principal instanceof Authentication authentication)) return null;
        Object authenticatedPrincipal = authentication.getPrincipal();
        if (!(authenticatedPrincipal instanceof OAuth2User oauth2User)) return null;

        Object appUser = oauth2User.getAttribute("appUser");
        if (appUser instanceof User user && user.getId() != null) return user.getId();

        String email = oauth2User.getAttribute("email");
        if (email == null || email.isBlank()) return null;
        return users.findByEmail(email.trim().toLowerCase()).map(User::getId).orElse(null);
    }
}
