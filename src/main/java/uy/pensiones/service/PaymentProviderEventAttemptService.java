package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.PaymentProviderEventAttemptSource;
import uy.pensiones.enums.PaymentProviderEventAttemptStatus;
import uy.pensiones.model.PaymentProviderEvent;
import uy.pensiones.model.PaymentProviderEventAttempt;
import uy.pensiones.repo.PaymentProviderEventAttemptRepository;
import uy.pensiones.repo.PaymentProviderEventRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class PaymentProviderEventAttemptService {
    private final PaymentProviderEventRepository events;
    private final PaymentProviderEventAttemptRepository attempts;

    public PaymentProviderEventAttemptService(PaymentProviderEventRepository events,
                                              PaymentProviderEventAttemptRepository attempts) {
        this.events = events;
        this.attempts = attempts;
    }

    @Transactional
    public Long startWebhookAttempt(Long eventId) {
        PaymentProviderEvent event = events.findById(eventId).orElseThrow();
        return attempts.save(PaymentProviderEventAttempt.builder()
                .event(event)
                .source(PaymentProviderEventAttemptSource.WEBHOOK_DELIVERY)
                .status(PaymentProviderEventAttemptStatus.IN_PROGRESS)
                .startedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build()).getId();
    }

    @Transactional
    public void finishWebhookAttempt(Long attemptId, PaymentProviderEventAttemptStatus status,
                                     String resultMessage, String errorMessage) {
        PaymentProviderEventAttempt attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getStatus() != PaymentProviderEventAttemptStatus.IN_PROGRESS) return;
        attempt.setStatus(status);
        attempt.setResultMessage(trim(resultMessage, 500));
        attempt.setErrorMessage(trim(errorMessage, 500));
        attempt.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        attempts.save(attempt);
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
