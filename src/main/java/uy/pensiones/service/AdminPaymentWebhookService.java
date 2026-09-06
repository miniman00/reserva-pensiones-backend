package uy.pensiones.service;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderEventAttemptStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.PaymentProviderEvent;
import uy.pensiones.model.PaymentProviderEventAttempt;
import uy.pensiones.repo.PaymentProviderEventAttemptRepository;
import uy.pensiones.repo.PaymentProviderEventRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

@Service
public class AdminPaymentWebhookService {
    private final PaymentProviderEventRepository events;
    private final PaymentProviderEventAttemptRepository attempts;
    private final PaymentWebhookReplayStateService replayState;
    private final MercadoPagoWebhookService mercadoPagoWebhooks;

    public AdminPaymentWebhookService(PaymentProviderEventRepository events,
                                      PaymentProviderEventAttemptRepository attempts,
                                      PaymentWebhookReplayStateService replayState,
                                      MercadoPagoWebhookService mercadoPagoWebhooks) {
        this.events = events;
        this.attempts = attempts;
        this.replayState = replayState;
        this.mercadoPagoWebhooks = mercadoPagoWebhooks;
    }

    @Transactional(readOnly = true)
    public Page<EventDTO> list(String q, PaymentProvider provider, String rawStatus, String eventType, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String status = normalizeStatus(rawStatus);
        String cleanQ = clean(q, 190);
        String cleanType = clean(eventType, 80);
        Specification<PaymentProviderEvent> spec = Specification.where(null);
        if (provider != null) spec = spec.and((root, query, cb) -> cb.equal(root.<PaymentProvider>get("provider"), provider));
        if (cleanType != null) spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.<String>get("eventType")), cleanType.toLowerCase(Locale.ROOT)));
        if (cleanQ != null) {
            String like = "%" + cleanQ.toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.<String>get("providerEventId")), like),
                    cb.like(cb.lower(root.<String>get("resourceId")), like),
                    cb.like(cb.lower(root.<String>get("requestId")), like),
                    cb.like(cb.lower(root.<String>get("eventAction")), like),
                    cb.like(cb.lower(root.<String>get("processingError")), like)
            ));
        }
        spec = spec.and(statusSpec(status));
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return events.findAll(spec, pageable).map(this::toEventDTO);
    }

    @Transactional(readOnly = true)
    public SummaryDTO summary() {
        long total = events.count();
        long processed = events.count(statusSpec("PROCESSED"));
        long failed = events.count(statusSpec("FAILED"));
        long replaying = events.count(statusSpec("REPLAYING"));
        long pending = events.count(statusSpec("PENDING"));
        return new SummaryDTO(total, processed, failed, replaying, pending);
    }

    @Transactional(readOnly = true)
    public EventDetailDTO detail(Long id) {
        PaymentProviderEvent event = events.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook no encontrado"));
        List<AttemptDTO> history = attempts.findByEventIdOrderByStartedAtDescIdDesc(id).stream().map(this::toAttemptDTO).toList();
        return new EventDetailDTO(toEventDTO(event), history, replayAllowed(event), replayBlockReason(event));
    }

    public EventDetailDTO replay(Long id, String reason, BackofficeUser actor) {
        PaymentWebhookReplayStateService.ReplayReservation reservation = replayState.reserve(id, actor, reason);
        try {
            MercadoPagoWebhookService.WebhookResult result = switch (reservation.provider()) {
                case MERCADO_PAGO -> mercadoPagoWebhooks.replayStoredEvent(id);
                default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Replay no implementado para " + reservation.provider());
            };
            replayState.completeSuccess(reservation, actor, result.message());
        } catch (RuntimeException failure) {
            replayState.completeFailure(reservation, actor, failure);
            throw failure;
        }
        return detail(id);
    }

    private EventDTO toEventDTO(PaymentProviderEvent event) {
        return new EventDTO(event.getId(), event.getProvider(), event.getProviderEventId(), event.getEventType(),
                event.getEventAction(), event.getResourceId(), event.getRequestId(), event.getLiveMode(),
                event.getPayloadHash(), status(event), event.getProcessingError(), event.getCreatedAt(), event.getProcessedAt(),
                event.isManualReplayInProgress(), event.getManualReplayStartedAt(), event.getLastManualReplayAt(),
                event.getManualReplayCount());
    }

    private AttemptDTO toAttemptDTO(PaymentProviderEventAttempt attempt) {
        BackofficeUser actor = attempt.getBackofficeUser();
        return new AttemptDTO(attempt.getId(), attempt.getSource(), attempt.getStatus(),
                actor == null ? null : actor.getId(), actor == null ? null : actor.getDisplayName(),
                attempt.getReason(), attempt.getResultMessage(), attempt.getErrorMessage(),
                attempt.getStartedAt(), attempt.getCompletedAt());
    }

    private String status(PaymentProviderEvent event) {
        if (event.getProcessedAt() != null) return "PROCESSED";
        if (event.isManualReplayInProgress()) return "REPLAYING";
        if (event.getProcessingError() != null && !event.getProcessingError().isBlank()) return "FAILED";
        return "PENDING";
    }

    private Specification<PaymentProviderEvent> statusSpec(String status) {
        if (status == null || status.isBlank()) return Specification.where(null);
        return switch (status) {
            case "PROCESSED" -> (root, query, cb) -> cb.isNotNull(root.<OffsetDateTime>get("processedAt"));
            case "REPLAYING" -> (root, query, cb) -> cb.and(cb.isNull(root.<OffsetDateTime>get("processedAt")), cb.isTrue(root.<Boolean>get("manualReplayInProgress")));
            case "FAILED" -> (root, query, cb) -> cb.and(cb.isNull(root.<OffsetDateTime>get("processedAt")), cb.isFalse(root.<Boolean>get("manualReplayInProgress")), cb.isNotNull(root.<String>get("processingError")));
            case "PENDING" -> (root, query, cb) -> cb.and(cb.isNull(root.<OffsetDateTime>get("processedAt")), cb.isFalse(root.<Boolean>get("manualReplayInProgress")), cb.isNull(root.<String>get("processingError")));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de webhook inválido");
        };
    }

    private String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("PROCESSED", "FAILED", "REPLAYING", "PENDING").contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de webhook inválido");
        }
        return value;
    }

    private boolean replayAllowed(PaymentProviderEvent event) {
        if (event.getProcessedAt() != null || event.getProcessingError() == null || event.getProcessingError().isBlank()) return false;
        if (event.getProvider() != PaymentProvider.MERCADO_PAGO) return false;
        if (!event.isManualReplayInProgress()) return true;
        return event.getManualReplayStartedAt() == null || event.getManualReplayStartedAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(PaymentWebhookReplayStateService.STALE_MINUTES));
    }

    private String replayBlockReason(PaymentProviderEvent event) {
        if (replayAllowed(event)) return null;
        if (event.getProcessedAt() != null) return "El evento ya fue procesado.";
        if (event.getProcessingError() == null || event.getProcessingError().isBlank()) return "El evento no tiene un error de procesamiento pendiente.";
        if (event.getProvider() != PaymentProvider.MERCADO_PAGO) return "El proveedor todavía no soporta replay manual.";
        if (event.isManualReplayInProgress()) return "Ya existe un replay manual en curso.";
        return "Replay no disponible.";
    }

    private String clean(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim();
        if (clean.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro demasiado largo");
        return clean;
    }

    public record SummaryDTO(long total, long processed, long failed, long replaying, long pending) {}
    public record EventDTO(Long id, PaymentProvider provider, String providerEventId, String eventType, String eventAction,
                           String resourceId, String requestId, Boolean liveMode, String payloadHash, String status,
                           String processingError, OffsetDateTime createdAt, OffsetDateTime processedAt,
                           boolean manualReplayInProgress, OffsetDateTime manualReplayStartedAt,
                           OffsetDateTime lastManualReplayAt, int manualReplayCount) {}
    public record AttemptDTO(Long id, uy.pensiones.enums.PaymentProviderEventAttemptSource source,
                             PaymentProviderEventAttemptStatus status, Long backofficeUserId, String backofficeUserName,
                             String reason, String resultMessage, String errorMessage,
                             OffsetDateTime startedAt, OffsetDateTime completedAt) {}
    public record EventDetailDTO(EventDTO event, List<AttemptDTO> attempts, boolean replayAllowed, String replayBlockReason) {}
}
