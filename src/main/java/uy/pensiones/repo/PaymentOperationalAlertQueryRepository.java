package uy.pensiones.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class PaymentOperationalAlertQueryRepository {

    private static final String ALERTS_CTE = """
            WITH alerts AS (
              SELECT concat('PAYMENT_FULFILLMENT:', p.id) AS alert_key,
                     'PAYMENT_FULFILLMENT' AS alert_type,
                     'CRITICAL' AS severity,
                     'PAYMENT' AS category,
                     'Pago aprobado sin beneficio aplicado' AS title,
                     concat('El pago #', p.id, ' está aprobado pero el beneficio no quedó aplicado',
                            CASE WHEN p.fulfillment_error_code IS NOT NULL THEN concat(' (', p.fulfillment_error_code, ')') ELSE '' END, '.') AS message,
                     'PAYMENT' AS entity_type,
                     p.id::text AS entity_id,
                     p.provider::text AS provider,
                     coalesce(p.approved_at, p.updated_at, p.created_at) AS occurred_at,
                     NULL::timestamptz AS due_at,
                     concat('/payments?paymentId=', p.id) AS action_path
                FROM payments p
               WHERE p.status = 'APPROVED'
                 AND (p.fulfilled_at IS NULL OR
                      (p.fulfillment_error_code IS NOT NULL AND p.fulfillment_error_code NOT IN
                       ('REFUND_REQUIRES_BENEFIT_REVIEW','PARTIAL_REFUND_REQUIRES_REVIEW')))

              UNION ALL

              SELECT concat('REFUND_BENEFIT:', p.id),
                     'REFUND_BENEFIT_REVIEW',
                     CASE WHEN coalesce(p.provider_refunded_amount, 0) >= p.amount THEN 'CRITICAL' ELSE 'HIGH' END,
                     'REFUND',
                     'Decisión pendiente después de una devolución',
                     concat('El pago #', p.id, ' ya había otorgado un beneficio y tiene una devolución que requiere decidir si se mantiene o revoca.'),
                     'PAYMENT', p.id::text, p.provider::text,
                     coalesce(p.refunded_at, p.updated_at, p.created_at), NULL::timestamptz,
                     concat('/payments?paymentId=', p.id)
                FROM payments p
               WHERE p.fulfilled_at IS NOT NULL
                 AND p.refund_benefit_decision IS NULL
                 AND p.fulfillment_error_code IN ('REFUND_REQUIRES_BENEFIT_REVIEW','PARTIAL_REFUND_REQUIRES_REVIEW')

              UNION ALL

              SELECT concat('REFUND_RESULT:', r.id),
                     'REFUND_RESULT_REVIEW',
                     CASE WHEN r.status IN ('UNKNOWN','REQUESTED') THEN 'HIGH' ELSE 'MEDIUM' END,
                     'REFUND',
                     CASE WHEN r.status = 'UNKNOWN' THEN 'Resultado de devolución incierto'
                          WHEN r.status = 'REQUESTED' THEN 'Devolución pendiente demasiado tiempo'
                          ELSE 'Devolución fallida pendiente de revisión' END,
                     concat('Reembolso #', r.id, ' del pago #', r.payment_id, ' · ', r.requested_amount, ' ', r.currency,
                            CASE WHEN r.last_error IS NOT NULL THEN concat(' · ', r.last_error) ELSE '' END),
                     'PAYMENT_REFUND', r.id::text, r.provider::text,
                     r.updated_at, NULL::timestamptz,
                     concat('/payments?paymentId=', r.payment_id)
                FROM payment_refunds r
               WHERE r.status IN ('UNKNOWN','FAILED')
                  OR (r.status = 'REQUESTED' AND r.updated_at < CURRENT_TIMESTAMP - interval '10 minutes')

              UNION ALL

              SELECT concat('CHARGEBACK_UNMATCHED:', c.id),
                     'CHARGEBACK_UNMATCHED', 'CRITICAL', 'CHARGEBACK',
                     'Contracargo sin pago interno asociado',
                     concat('El contracargo ', c.provider_chargeback_id, ' no pudo asociarse a un pago interno. Provider payment: ', coalesce(c.provider_payment_id, '—'), '.'),
                     'PAYMENT_CHARGEBACK', c.id::text, c.provider::text,
                     c.updated_at, c.documentation_deadline,
                     concat('/chargebacks?chargebackId=', c.id)
                FROM payment_chargebacks c
               WHERE c.payment_id IS NULL

              UNION ALL

              SELECT concat('CHARGEBACK_BENEFIT:', c.id),
                     'CHARGEBACK_BENEFIT_REVIEW', 'CRITICAL', 'CHARGEBACK',
                     'Contracargo perdido: decisión sobre beneficio pendiente',
                     concat('El contracargo ', c.provider_chargeback_id, ' fue resuelto contra la empresa y el pago ya había otorgado un beneficio.'),
                     'PAYMENT_CHARGEBACK', c.id::text, c.provider::text,
                     c.updated_at, NULL::timestamptz,
                     concat('/chargebacks?chargebackId=', c.id)
                FROM payment_chargebacks c
                JOIN payments p ON p.id = c.payment_id
               WHERE c.status = 'LOST' AND p.fulfilled_at IS NOT NULL AND c.benefit_decision IS NULL

              UNION ALL

              SELECT concat('CHARGEBACK_DOCUMENTATION:', c.id),
                     'CHARGEBACK_DOCUMENTATION',
                     CASE WHEN c.documentation_deadline <= CURRENT_TIMESTAMP THEN 'CRITICAL'
                          WHEN c.documentation_deadline <= CURRENT_TIMESTAMP + interval '24 hours' THEN 'CRITICAL'
                          WHEN c.documentation_deadline <= CURRENT_TIMESTAMP + interval '72 hours' THEN 'HIGH'
                          ELSE 'MEDIUM' END,
                     'CHARGEBACK',
                     CASE WHEN c.documentation_deadline <= CURRENT_TIMESTAMP THEN 'Plazo de evidencia de contracargo vencido'
                          WHEN c.documentation_deadline <= CURRENT_TIMESTAMP + interval '24 hours' THEN 'Evidencia de contracargo vence en menos de 24 h'
                          ELSE 'Contracargo requiere evidencia' END,
                     concat('El contracargo ', c.provider_chargeback_id, ' requiere documentación antes de ', c.documentation_deadline, '.'),
                     'PAYMENT_CHARGEBACK', c.id::text, c.provider::text,
                     c.updated_at, c.documentation_deadline,
                     concat('/chargebacks?chargebackId=', c.id)
                FROM payment_chargebacks c
               WHERE c.status = 'OPEN'
                 AND c.documentation_required = TRUE
                 AND lower(coalesce(c.documentation_status, '')) = 'not_supplied'
                 AND c.documentation_deadline IS NOT NULL
                 AND coalesce(c.documentation_submission_state::text, '') <> 'UNKNOWN'

              UNION ALL

              SELECT concat('CHARGEBACK_EVIDENCE_UNKNOWN:', c.id),
                     'CHARGEBACK_EVIDENCE_UNKNOWN', 'HIGH', 'CHARGEBACK',
                     'Resultado del envío de evidencia incierto',
                     concat('El envío de evidencia del contracargo ', c.provider_chargeback_id, ' debe sincronizarse antes de intentar nuevamente.'),
                     'PAYMENT_CHARGEBACK', c.id::text, c.provider::text,
                     coalesce(c.documentation_submission_started_at, c.updated_at), c.documentation_deadline,
                     concat('/chargebacks?chargebackId=', c.id)
                FROM payment_chargebacks c
               WHERE c.status = 'OPEN' AND c.documentation_submission_state = 'UNKNOWN'

              UNION ALL

              SELECT concat('CHARGEBACK_OPEN:', c.id),
                     'CHARGEBACK_OPEN', 'MEDIUM', 'CHARGEBACK',
                     'Contracargo abierto',
                     concat('El contracargo ', c.provider_chargeback_id, ' continúa abierto y debe mantenerse bajo seguimiento.'),
                     'PAYMENT_CHARGEBACK', c.id::text, c.provider::text,
                     c.updated_at, c.documentation_deadline,
                     concat('/chargebacks?chargebackId=', c.id)
                FROM payment_chargebacks c
               WHERE c.status = 'OPEN'
                 AND c.payment_id IS NOT NULL
                 AND coalesce(c.documentation_submission_state::text, '') <> 'UNKNOWN'
                 AND NOT (c.documentation_required = TRUE
                          AND lower(coalesce(c.documentation_status, '')) = 'not_supplied'
                          AND c.documentation_deadline IS NOT NULL)

              UNION ALL

              SELECT concat('WEBHOOK_FAILURE:', e.id),
                     'WEBHOOK_PROCESSING_FAILURE', 'HIGH', 'WEBHOOK',
                     'Webhook pendiente por error de procesamiento',
                     concat(e.provider::text, ' · ', coalesce(e.event_type, 'evento'),
                            CASE WHEN e.event_action IS NOT NULL THEN concat(' / ', e.event_action) ELSE '' END,
                            ' · ', coalesce(e.processing_error, 'Error sin detalle')),
                     'PAYMENT_PROVIDER_EVENT', e.id::text, e.provider::text,
                     e.created_at, NULL::timestamptz,
                     concat('/payment-webhooks?eventId=', e.id)
                FROM payment_provider_events e
               WHERE e.processed_at IS NULL
                 AND e.processing_error IS NOT NULL
                 AND e.manual_replay_in_progress = FALSE

              UNION ALL

              SELECT concat('WEBHOOK_REPLAY_STUCK:', e.id),
                     'WEBHOOK_REPLAY_STUCK', 'CRITICAL', 'WEBHOOK',
                     'Replay manual de webhook atascado',
                     concat(e.provider::text, ' · ', coalesce(e.event_type, 'evento'),
                            ' · el replay lleva más de 10 minutos marcado como en curso.'),
                     'PAYMENT_PROVIDER_EVENT', e.id::text, e.provider::text,
                     e.manual_replay_started_at, NULL::timestamptz,
                     concat('/payment-webhooks?eventId=', e.id)
                FROM payment_provider_events e
               WHERE e.processed_at IS NULL
                 AND e.manual_replay_in_progress = TRUE
                 AND e.manual_replay_started_at IS NOT NULL
                 AND e.manual_replay_started_at <= CURRENT_TIMESTAMP - interval '10 minutes'

              UNION ALL

              SELECT concat('PROVIDER_DEGRADED:', c.provider::text),
                     'PROVIDER_DEGRADED', 'HIGH', 'PROVIDER',
                     'Proveedor de pagos degradado',
                     concat(c.display_name, ' está habilitado pero su última prueba de conectividad falló: ',
                            coalesce(c.last_connectivity_check_message, 'sin detalle')),
                     'PAYMENT_PROVIDER', c.provider::text, c.provider::text,
                     coalesce(c.last_connectivity_check_at, c.updated_at), NULL::timestamptz,
                     '/payment-settings'
                FROM payment_provider_configs c
               WHERE c.enabled = TRUE AND c.last_connectivity_check_success = FALSE


              UNION ALL

              SELECT concat('RECONCILIATION_FAILURE:', r.id),
                     'RECONCILIATION_FAILURE',
                     CASE WHEN r.status = 'FAILED' THEN 'HIGH' ELSE 'MEDIUM' END,
                     'RECONCILIATION',
                     CASE WHEN r.status = 'FAILED' THEN 'Última conciliación terminó con errores'
                          ELSE 'Última conciliación terminó parcialmente' END,
                     coalesce(r.summary_message, 'La última ejecución preventiva no terminó correctamente.'),
                     'PAYMENT_RECONCILIATION_RUN', r.id::text, NULL::text,
                     coalesce(r.completed_at, r.started_at), NULL::timestamptz,
                     '/payment-reconciliation'
                FROM payment_reconciliation_runs r
               WHERE r.id = (SELECT r2.id
                               FROM payment_reconciliation_runs r2
                              WHERE r2.status <> 'RUNNING'
                              ORDER BY r2.completed_at DESC NULLS LAST, r2.id DESC
                              LIMIT 1)
                 AND r.status IN ('FAILED','PARTIAL')

              UNION ALL

              SELECT 'RECONCILIATION_LEASE_EXPIRED',
                     'RECONCILIATION_LEASE_EXPIRED', 'CRITICAL', 'RECONCILIATION',
                     'Lease de conciliación vencido',
                     concat('Existe un lease vencido desde ', coalesce(s.reconciliation_lease_until::text, 'fecha desconocida'),
                            '. El scheduler debe recuperarlo antes de iniciar una nueva ejecución.'),
                     'PAYMENT_SETTINGS', s.id::text, NULL::text,
                     coalesce(s.reconciliation_lease_until, s.reconciliation_last_started_at, s.updated_at), NULL::timestamptz,
                     '/payment-reconciliation'
                FROM payment_settings s
               WHERE s.reconciliation_lease_owner IS NOT NULL
                 AND (s.reconciliation_lease_until IS NULL OR s.reconciliation_lease_until <= CURRENT_TIMESTAMP)

              UNION ALL

              SELECT concat('RECONCILIATION_LONG_RUNNING:', r.id),
                     'RECONCILIATION_LONG_RUNNING', 'HIGH', 'RECONCILIATION',
                     'Conciliación en ejecución por más de 60 minutos',
                     concat('La ejecución #', r.id, ' continúa en RUNNING desde ', r.started_at,
                            ' y mantiene lease hasta ', s.reconciliation_lease_until,
                            '. Revisar latencia o bloqueo del proveedor.'),
                     'PAYMENT_RECONCILIATION_RUN', r.id::text, NULL::text,
                     r.started_at, NULL::timestamptz,
                     '/payment-reconciliation'
                FROM payment_reconciliation_runs r
                JOIN payment_settings s ON s.reconciliation_lease_owner = r.lease_owner
               WHERE r.status = 'RUNNING'
                 AND s.reconciliation_lease_until > CURRENT_TIMESTAMP
                 AND r.started_at <= CURRENT_TIMESTAMP - interval '60 minutes'

              UNION ALL

              SELECT 'RECONCILIATION_REPEATED_FAILURES',
                     'RECONCILIATION_REPEATED_FAILURES', 'CRITICAL', 'RECONCILIATION',
                     'Tres conciliaciones consecutivas con incidencias',
                     'Las últimas tres conciliaciones terminaron FAILED o PARTIAL. Revisar proveedor, conectividad y errores antes de continuar.',
                     'PAYMENT_RECONCILIATION', NULL::text, NULL::text,
                     recent.last_completed_at, NULL::timestamptz,
                     '/payment-reconciliation'
                FROM (
                     SELECT COUNT(*) AS total_runs,
                            COUNT(*) FILTER (WHERE x.status IN ('FAILED','PARTIAL')) AS problem_runs,
                            MAX(x.completed_at) AS last_completed_at
                       FROM (
                            SELECT r.status, r.completed_at
                              FROM payment_reconciliation_runs r
                             WHERE r.status <> 'RUNNING'
                             ORDER BY r.completed_at DESC NULLS LAST, r.id DESC
                             LIMIT 3
                       ) x
                ) recent
               WHERE recent.total_runs = 3 AND recent.problem_runs = 3

              UNION ALL

              SELECT 'AUTO_RECONCILIATION_STALE',
                     'AUTO_RECONCILIATION_STALE', 'CRITICAL', 'RECONCILIATION',
                     'Conciliación automática sin ejecuciones recientes',
                     concat('El reconciliador está habilitado pero no completa una ejecución desde hace más de ',
                            s.reconciliation_interval_minutes * 3, ' minutos.'),
                     'PAYMENT_SETTINGS', s.id::text, NULL::text,
                     coalesce(s.reconciliation_last_completed_at, s.updated_at), NULL::timestamptz,
                     '/payment-reconciliation'
                FROM payment_settings s
               WHERE s.automatic_reconciliation_enabled = TRUE
                 AND s.reconciliation_lease_owner IS NULL
                 AND coalesce(s.reconciliation_last_completed_at, s.updated_at)
                     < CURRENT_TIMESTAMP - make_interval(mins => s.reconciliation_interval_minutes * 3)
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentOperationalAlertQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AlertSummaryRow summary() {
        String sql = ALERTS_CTE + """
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE severity='CRITICAL') AS critical,
                       COUNT(*) FILTER (WHERE severity='HIGH') AS high,
                       COUNT(*) FILTER (WHERE severity='MEDIUM') AS medium,
                       COUNT(*) FILTER (WHERE due_at IS NOT NULL AND due_at <= CURRENT_TIMESTAMP + interval '24 hours') AS due_within_24h
                  FROM alerts
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), (rs, rowNum) ->
                new AlertSummaryRow(rs.getLong("total"), rs.getLong("critical"), rs.getLong("high"),
                        rs.getLong("medium"), rs.getLong("due_within_24h")));
    }

    public AlertPageRow page(String severity, String category, int page, int size) {
        String sql = ALERTS_CTE + """
                , filtered AS (
                    SELECT a.*
                      FROM alerts a
                     WHERE (:severity = '' OR a.severity = :severity)
                       AND (:category = '' OR a.category = :category)
                ), numbered AS (
                    SELECT f.*,
                           COUNT(*) OVER() AS filtered_total
                      FROM filtered f
                     ORDER BY CASE f.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END,
                              f.due_at ASC NULLS LAST,
                              f.occurred_at DESC
                     LIMIT :size OFFSET :offset
                )
                SELECT * FROM numbered
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("severity", severity == null ? "" : severity)
                .addValue("category", category == null ? "" : category)
                .addValue("size", size)
                .addValue("offset", page * size);
        List<AlertRow> rows = jdbc.query(sql, params, this::map);
        long total = rows.isEmpty() ? filteredCount(severity, category) : rows.get(0).filteredTotal();
        return new AlertPageRow(rows, total);
    }

    public List<AlertRow> notificationCandidates(String minSeverity, int size) {
        String sql = ALERTS_CTE + """
                SELECT a.*, 0::bigint AS filtered_total
                  FROM alerts a
                 WHERE CASE a.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 99 END
                       <= CASE :minSeverity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 1 END
                 ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END,
                          a.due_at ASC NULLS LAST,
                          a.occurred_at DESC
                 LIMIT :size
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("minSeverity", minSeverity)
                        .addValue("size", Math.min(100, Math.max(1, size))), this::map);
    }

    private long filteredCount(String severity, String category) {
        String sql = ALERTS_CTE + """
                SELECT COUNT(*)
                  FROM alerts a
                 WHERE (:severity = '' OR a.severity = :severity)
                   AND (:category = '' OR a.category = :category)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("severity", severity == null ? "" : severity)
                .addValue("category", category == null ? "" : category);
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private AlertRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AlertRow(
                rs.getString("alert_key"), rs.getString("alert_type"), rs.getString("severity"), rs.getString("category"),
                rs.getString("title"), rs.getString("message"), rs.getString("entity_type"), rs.getString("entity_id"),
                rs.getString("provider"), toOffset(rs, "occurred_at"), toOffset(rs, "due_at"), rs.getString("action_path"),
                rs.getLong("filtered_total")
        );
    }

    private OffsetDateTime toOffset(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        return rs.getObject(column, OffsetDateTime.class);
    }

    public record AlertSummaryRow(long total, long critical, long high, long medium, long dueWithin24h) {}
    public record AlertPageRow(List<AlertRow> content, long total) {}
    public record AlertRow(String alertKey, String alertType, String severity, String category,
                           String title, String message, String entityType, String entityId, String provider,
                           OffsetDateTime occurredAt, OffsetDateTime dueAt, String actionPath, long filteredTotal) {}
}
