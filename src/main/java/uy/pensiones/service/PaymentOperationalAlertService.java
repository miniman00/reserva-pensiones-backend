package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import uy.pensiones.repo.PaymentOperationalAlertQueryRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Service
public class PaymentOperationalAlertService {
    private static final java.util.Set<String> SEVERITIES = java.util.Set.of("CRITICAL", "HIGH", "MEDIUM");
    private static final java.util.Set<String> CATEGORIES = java.util.Set.of("PAYMENT", "REFUND", "CHARGEBACK", "WEBHOOK", "PROVIDER", "RECONCILIATION");

    private final PaymentOperationalAlertQueryRepository queries;

    public PaymentOperationalAlertService(PaymentOperationalAlertQueryRepository queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public OperationalAlertsDTO get(String rawSeverity, String rawCategory, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String severity = normalize(rawSeverity, SEVERITIES, "severidad");
        String category = normalize(rawCategory, CATEGORIES, "categoría");
        var summary = queries.summary();
        var result = queries.page(severity, category, safePage, safeSize);
        long totalPages = result.total() == 0 ? 0 : (result.total() + safeSize - 1) / safeSize;
        return new OperationalAlertsDTO(
                new SummaryDTO(summary.total(), summary.critical(), summary.high(), summary.medium(), summary.dueWithin24h()),
                result.content().stream().map(r -> new AlertDTO(r.alertKey(), r.alertType(), r.severity(), r.category(),
                        r.title(), r.message(), r.entityType(), r.entityId(), r.provider(), r.occurredAt(), r.dueAt(), r.actionPath())).toList(),
                result.total(), safePage, safeSize, totalPages, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String normalize(String raw, java.util.Set<String> allowed, String label) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro de " + label + " inválido");
        return value;
    }

    public record OperationalAlertsDTO(SummaryDTO summary, java.util.List<AlertDTO> content,
                                       long totalElements, int page, int size, long totalPages, OffsetDateTime generatedAt) {}
    public record SummaryDTO(long total, long critical, long high, long medium, long dueWithin24h) {}
    public record AlertDTO(String key, String type, String severity, String category, String title, String message,
                           String entityType, String entityId, String provider, OffsetDateTime occurredAt,
                           OffsetDateTime dueAt, String actionPath) {}
}
