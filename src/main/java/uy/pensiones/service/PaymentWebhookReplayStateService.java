package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.PaymentProviderEvent;
import uy.pensiones.model.PaymentProviderEventAttempt;
import uy.pensiones.repo.PaymentProviderEventAttemptRepository;
import uy.pensiones.repo.PaymentProviderEventRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PaymentWebhookReplayStateService {
    public static final int STALE_MINUTES = 10;

    private final PaymentProviderEventRepository events;
    private final PaymentProviderEventAttemptRepository attempts;
    private final AdminAuditService audit;

    public PaymentWebhookReplayStateService(PaymentProviderEventRepository events,
                                            PaymentProviderEventAttemptRepository attempts,
                                            AdminAuditService audit) {
        this.events = events;
        this.attempts = attempts;
        this.audit = audit;
    }

    @Transactional
    public ReplayReservation reserve(Long eventId, BackofficeUser actor, String rawReason) {
        String reason = audit.requireReason(rawReason);
        PaymentProviderEvent event = events.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook no encontrado"));
        if (event.getProcessedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El webhook ya fue procesado correctamente");
        }
        if (event.getProcessingError() == null || event.getProcessingError().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El webhook no tiene un error de procesamiento que requiera replay manual");
        }
        if (event.getProvider() != PaymentProvider.MERCADO_PAGO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor todavía no soporta replay administrativo de webhooks");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime staleBefore = now.minusMinutes(STALE_MINUTES);
        if (event.isManualReplayInProgress() && event.getManualReplayStartedAt() != null
                && event.getManualReplayStartedAt().isAfter(staleBefore)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un replay manual en curso para este webhook");
        }

        if (event.isManualReplayInProgress()) {
            for (PaymentProviderEventAttempt old : attempts.findByEventIdAndStatus(eventId, PaymentProviderEventAttemptStatus.IN_PROGRESS)) {
                if (old.getSource() != PaymentProviderEventAttemptSource.MANUAL_REPLAY) continue;
                old.setStatus(PaymentProviderEventAttemptStatus.FAILED);
                old.setErrorMessage("Replay anterior abandonado; se recuperó el bloqueo al iniciar un nuevo intento");
                old.setCompletedAt(now);
                attempts.save(old);
            }
        }

        Map<String, Object> before = snapshot(event);
        event.setManualReplayInProgress(true);
        event.setManualReplayStartedAt(now);
        events.save(event);

        PaymentProviderEventAttempt attempt = attempts.save(PaymentProviderEventAttempt.builder()
                .event(event)
                .source(PaymentProviderEventAttemptSource.MANUAL_REPLAY)
                .status(PaymentProviderEventAttemptStatus.IN_PROGRESS)
                .backofficeUser(actor)
                .reason(reason)
                .startedAt(now)
                .build());
        return new ReplayReservation(eventId, attempt.getId(), event.getProvider(), reason, before);
    }

    @Transactional
    public void completeSuccess(ReplayReservation reservation, BackofficeUser actor, String resultMessage) {
        PaymentProviderEvent event = events.findByIdForUpdate(reservation.eventId()).orElseThrow();
        PaymentProviderEventAttempt attempt = attempts.findById(reservation.attemptId()).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        attempt.setStatus(PaymentProviderEventAttemptStatus.SUCCEEDED);
        attempt.setResultMessage(trim(resultMessage, 500));
        attempt.setErrorMessage(null);
        attempt.setCompletedAt(now);
        attempts.save(attempt);
        finishEventReplay(event, now);
        audit.record(actor, AdminAuditAction.ADMIN_REPLAY_PAYMENT_WEBHOOK,
                AdminAuditEntityType.PAYMENT_PROVIDER_EVENT, event.getId(), reservation.beforeSnapshot(),
                replayOutcome(event, attempt), reservation.reason());
    }

    @Transactional
    public void completeFailure(ReplayReservation reservation, BackofficeUser actor, Throwable failure) {
        PaymentProviderEvent event = events.findByIdForUpdate(reservation.eventId()).orElseThrow();
        PaymentProviderEventAttempt attempt = attempts.findById(reservation.attemptId()).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        attempt.setStatus(PaymentProviderEventAttemptStatus.FAILED);
        attempt.setErrorMessage(trim(safeMessage(failure), 500));
        attempt.setCompletedAt(now);
        attempts.save(attempt);
        finishEventReplay(event, now);
        audit.record(actor, AdminAuditAction.ADMIN_REPLAY_PAYMENT_WEBHOOK,
                AdminAuditEntityType.PAYMENT_PROVIDER_EVENT, event.getId(), reservation.beforeSnapshot(),
                replayOutcome(event, attempt), reservation.reason());
    }

    private void finishEventReplay(PaymentProviderEvent event, OffsetDateTime now) {
        event.setManualReplayInProgress(false);
        event.setManualReplayStartedAt(null);
        event.setLastManualReplayAt(now);
        event.setManualReplayCount(event.getManualReplayCount() + 1);
        events.save(event);
    }

    private Map<String, Object> snapshot(PaymentProviderEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("processedAt", event.getProcessedAt());
        map.put("processingError", event.getProcessingError());
        map.put("manualReplayCount", event.getManualReplayCount());
        return map;
    }

    private Map<String, Object> replayOutcome(PaymentProviderEvent event, PaymentProviderEventAttempt attempt) {
        Map<String, Object> map = snapshot(event);
        map.put("attemptId", attempt.getId());
        map.put("attemptStatus", attempt.getStatus());
        map.put("resultMessage", attempt.getResultMessage());
        map.put("errorMessage", attempt.getErrorMessage());
        return map;
    }

    private String safeMessage(Throwable error) {
        if (error instanceof ResponseStatusException r && r.getReason() != null) return r.getReason();
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "Error de replay sin detalle" : message;
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    public record ReplayReservation(Long eventId, Long attemptId, PaymentProvider provider,
                                    String reason, Map<String, Object> beforeSnapshot) {}
}
