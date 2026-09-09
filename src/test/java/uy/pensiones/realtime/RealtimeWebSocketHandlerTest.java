package uy.pensiones.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.*;

class RealtimeWebSocketHandlerTest {

    @Test
    void registersSocketAgainstAuthenticatedMarketplaceUser() throws Exception {
        UserRepository users = mock(UserRepository.class);
        RealtimeWebSocketSessionRegistry registry = mock(RealtimeWebSocketSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = Map.of("email", "owner@example.com");
        var oauthUser = new DefaultOAuth2User(
                Set.of(new OAuth2UserAuthority(attributes)), attributes, "email");
        var authentication = new OAuth2AuthenticationToken(oauthUser, oauthUser.getAuthorities(), "google");

        when(session.getPrincipal()).thenReturn(authentication);
        when(users.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(User.builder().id(42L).email("owner@example.com").build()));

        var handler = new RealtimeWebSocketHandler(users, registry, new ObjectMapper());
        handler.afterConnectionEstablished(session);

        verify(registry).register(42L, session);
    }

    @Test
    void answersApplicationHeartbeatWithoutAcceptingBusinessCommands() throws Exception {
        UserRepository users = mock(UserRepository.class);
        RealtimeWebSocketSessionRegistry registry = mock(RealtimeWebSocketSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-1");

        var handler = new RealtimeWebSocketHandler(users, registry, new ObjectMapper());
        handler.handleMessage(session, new TextMessage("{\"type\":\"PING\"}"));

        verify(registry).sendPong("ws-1");
    }
}
