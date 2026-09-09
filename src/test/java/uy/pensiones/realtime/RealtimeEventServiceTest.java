package uy.pensiones.realtime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeEventServiceTest {

    @Test
    void publishesUserScopedEventAndPreservesNullablePayloadValues() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RealtimeEventService service = new RealtimeEventService(publisher);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "APPROVED");
        data.put("providerStatus", null);

        service.publishToUser(7L, "PAYMENT_STATUS_CHANGED", 81L, data);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        RealtimeUserEvent event = assertInstanceOf(RealtimeUserEvent.class, captor.getValue());
        assertEquals(7L, event.userId());
        assertEquals("PAYMENT_STATUS_CHANGED", event.event().type());
        assertEquals(81L, event.event().entityId());
        assertEquals("APPROVED", event.event().data().get("status"));
        assertTrue(event.event().data().containsKey("providerStatus"));
        assertNotNull(event.event().eventId());
        assertNotNull(event.event().occurredAt());
    }
}
