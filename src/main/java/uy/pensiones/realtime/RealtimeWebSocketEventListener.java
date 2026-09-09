package uy.pensiones.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RealtimeWebSocketEventListener {

    private final RealtimeWebSocketSessionRegistry registry;

    public RealtimeWebSocketEventListener(RealtimeWebSocketSessionRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRealtimeEvent(RealtimeUserEvent event) {
        if (event == null || event.event() == null) return;
        registry.send(event.userId(), event.event());
    }
}
