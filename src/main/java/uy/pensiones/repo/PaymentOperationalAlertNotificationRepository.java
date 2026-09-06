package uy.pensiones.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class PaymentOperationalAlertNotificationRepository {
    private static final short STATE_ID = 1;
    private final NamedParameterJdbcTemplate jdbc;

    public PaymentOperationalAlertNotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquireScanLease(String owner, OffsetDateTime leaseUntil) {
        String sql = """
                UPDATE payment_operational_alert_state
                   SET lease_owner = :owner,
                       lease_until = :leaseUntil,
                       last_scan_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :id
                   AND (lease_owner IS NULL OR lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                RETURNING id
                """;
        return !jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("owner", owner).addValue("leaseUntil", leaseUntil).addValue("id", STATE_ID),
                (rs, rowNum) -> rs.getShort("id")).isEmpty();
    }

    public void completeScan(String owner, boolean success, boolean emailSent, String error) {
        String sql = """
                UPDATE payment_operational_alert_state
                   SET lease_owner = NULL,
                       lease_until = NULL,
                       last_successful_scan_at = CASE WHEN :success THEN CURRENT_TIMESTAMP ELSE last_successful_scan_at END,
                       last_email_at = CASE WHEN :emailSent THEN CURRENT_TIMESTAMP ELSE last_email_at END,
                       last_error = :error,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :id AND lease_owner = :owner
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("success", success).addValue("emailSent", emailSent).addValue("error", trim(error, 1000))
                .addValue("id", STATE_ID).addValue("owner", owner));
    }

    public Long claimEmail(PaymentOperationalAlertQueryRepository.AlertRow alert,
                           String recipients,
                           String claimToken,
                           OffsetDateTime claimUntil) {
        String sql = """
                INSERT INTO payment_operational_alert_deliveries
                    (alert_key, channel, alert_type, severity, category, recipients, first_detected_at,
                     last_detected_at, source_occurred_at, last_attempt_at, claim_token, claim_until,
                     last_title, last_message, updated_at)
                VALUES
                    (:alertKey, 'EMAIL', :alertType, :severity, :category, :recipients, CURRENT_TIMESTAMP,
                     CURRENT_TIMESTAMP, :sourceOccurredAt, CURRENT_TIMESTAMP, :claimToken, :claimUntil,
                     :title, :message, CURRENT_TIMESTAMP)
                ON CONFLICT (alert_key, channel) DO UPDATE SET
                    alert_type = EXCLUDED.alert_type,
                    severity = EXCLUDED.severity,
                    category = EXCLUDED.category,
                    recipients = EXCLUDED.recipients,
                    last_detected_at = CURRENT_TIMESTAMP,
                    source_occurred_at = EXCLUDED.source_occurred_at,
                    last_attempt_at = CURRENT_TIMESTAMP,
                    claim_token = EXCLUDED.claim_token,
                    claim_until = EXCLUDED.claim_until,
                    last_title = EXCLUDED.last_title,
                    last_message = EXCLUDED.last_message,
                    updated_at = CURRENT_TIMESTAMP
                WHERE ((payment_operational_alert_deliveries.next_attempt_at IS NULL
                        OR payment_operational_alert_deliveries.next_attempt_at <= CURRENT_TIMESTAMP)
                       OR CASE EXCLUDED.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 99 END
                          < CASE payment_operational_alert_deliveries.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 99 END)
                  AND (payment_operational_alert_deliveries.claim_until IS NULL
                       OR payment_operational_alert_deliveries.claim_until <= CURRENT_TIMESTAMP)
                RETURNING id
                """;
        List<Long> ids = jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("alertKey", trim(alert.alertKey(), 300))
                        .addValue("alertType", trim(alert.alertType(), 100))
                        .addValue("severity", alert.severity())
                        .addValue("category", trim(alert.category(), 40))
                        .addValue("recipients", recipients)
                        .addValue("sourceOccurredAt", alert.occurredAt())
                        .addValue("claimToken", claimToken)
                        .addValue("claimUntil", claimUntil)
                        .addValue("title", trim(alert.title(), 300))
                        .addValue("message", trim(alert.message(), 2000)),
                (rs, rowNum) -> rs.getLong("id"));
        return ids.isEmpty() ? null : ids.get(0);
    }

    public boolean markSent(Long id, String claimToken, int cooldownMinutes) {
        String sql = """
                UPDATE payment_operational_alert_deliveries
                   SET last_sent_at = CURRENT_TIMESTAMP,
                       next_attempt_at = CURRENT_TIMESTAMP + make_interval(mins => :cooldownMinutes),
                       send_count = send_count + 1,
                       claim_token = NULL,
                       claim_until = NULL,
                       last_delivery_success = TRUE,
                       last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :id AND claim_token = :claimToken
                """;
        return jdbc.update(sql, new MapSqlParameterSource().addValue("cooldownMinutes", cooldownMinutes)
                .addValue("id", id).addValue("claimToken", claimToken)) == 1;
    }

    public boolean markFailed(Long id, String claimToken, String error) {
        String sql = """
                UPDATE payment_operational_alert_deliveries
                   SET next_attempt_at = CURRENT_TIMESTAMP + interval '10 minutes',
                       failure_count = failure_count + 1,
                       claim_token = NULL,
                       claim_until = NULL,
                       last_delivery_success = FALSE,
                       last_error = :error,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = :id AND claim_token = :claimToken
                """;
        return jdbc.update(sql, new MapSqlParameterSource().addValue("error", trim(error, 1000))
                .addValue("id", id).addValue("claimToken", claimToken)) == 1;
    }

    public RuntimeState state() {
        String sql = """
                SELECT lease_owner, lease_until, last_scan_at, last_successful_scan_at, last_email_at, last_error
                  FROM payment_operational_alert_state
                 WHERE id = :id
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource("id", STATE_ID), (rs, rowNum) -> new RuntimeState(
                rs.getString("lease_owner"),
                rs.getObject("lease_until", OffsetDateTime.class),
                rs.getObject("last_scan_at", OffsetDateTime.class),
                rs.getObject("last_successful_scan_at", OffsetDateTime.class),
                rs.getObject("last_email_at", OffsetDateTime.class),
                rs.getString("last_error")
        ));
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String result = value.trim();
        return result.length() <= max ? result : result.substring(0, max);
    }

    public record RuntimeState(String leaseOwner, OffsetDateTime leaseUntil, OffsetDateTime lastScanAt,
                               OffsetDateTime lastSuccessfulScanAt, OffsetDateTime lastEmailAt, String lastError) {}
}
