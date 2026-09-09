package uy.pensiones.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import uy.pensiones.realtime.RealtimeWebSocketHandler;

@Configuration
@EnableWebSocket
public class RealtimeWebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final AppProperties properties;

    public RealtimeWebSocketConfig(RealtimeWebSocketHandler handler, AppProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String portalOrigin = properties.getFrontendUrl() == null
                ? null
                : properties.getFrontendUrl().trim().replaceAll("/+$", "");
        if (portalOrigin == null || portalOrigin.isBlank() || "*".equals(portalOrigin)) {
            throw new IllegalStateException("app.frontend-url debe definir el origen explícito del portal para WebSocket");
        }
        registry.addHandler(handler, "/ws/events")
                .setAllowedOrigins(portalOrigin);
    }
}
