-- Alertas operativas externas de pagos administradas desde Backoffice.
-- El transporte de correo (SMTP/Brevo) continúa siendo infraestructura; destinatarios/cooldown quedan en BD.
ALTER TABLE payment_settings
    ADD COLUMN IF NOT EXISTS operational_alert_email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS operational_alert_email_recipients TEXT,
    ADD COLUMN IF NOT EXISTS operational_alert_min_severity VARCHAR(16) NOT NULL DEFAULT 'CRITICAL',
    ADD COLUMN IF NOT EXISTS operational_alert_cooldown_minutes INTEGER NOT NULL DEFAULT 180;

ALTER TABLE payment_settings
    DROP CONSTRAINT IF EXISTS ck_payment_operational_alert_min_severity;
ALTER TABLE payment_settings
    ADD CONSTRAINT ck_payment_operational_alert_min_severity
        CHECK (operational_alert_min_severity IN ('MEDIUM','HIGH','CRITICAL'));
ALTER TABLE payment_settings
    DROP CONSTRAINT IF EXISTS ck_payment_operational_alert_cooldown;
ALTER TABLE payment_settings
    ADD CONSTRAINT ck_payment_operational_alert_cooldown
        CHECK (operational_alert_cooldown_minutes BETWEEN 5 AND 10080);

CREATE TABLE IF NOT EXISTS payment_operational_alert_state (
    id SMALLINT PRIMARY KEY,
    lease_owner VARCHAR(120),
    lease_until TIMESTAMPTZ,
    last_scan_at TIMESTAMPTZ,
    last_successful_scan_at TIMESTAMPTZ,
    last_email_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_payment_operational_alert_state_singleton CHECK (id = 1)
);
INSERT INTO payment_operational_alert_state (id) VALUES (1)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS payment_operational_alert_deliveries (
    id BIGSERIAL PRIMARY KEY,
    alert_key VARCHAR(300) NOT NULL,
    channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    alert_type VARCHAR(100) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    category VARCHAR(40) NOT NULL,
    recipients TEXT NOT NULL,
    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source_occurred_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_sent_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    send_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    claim_token VARCHAR(80),
    claim_until TIMESTAMPTZ,
    last_delivery_success BOOLEAN,
    last_error VARCHAR(1000),
    last_title VARCHAR(300),
    last_message VARCHAR(2000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_operational_alert_delivery UNIQUE (alert_key, channel),
    CONSTRAINT ck_payment_operational_alert_delivery_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_payment_operational_alert_delivery_severity CHECK (severity IN ('MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_payment_operational_alert_delivery_counts CHECK (send_count >= 0 AND failure_count >= 0)
);
CREATE INDEX IF NOT EXISTS idx_payment_operational_alert_delivery_due
    ON payment_operational_alert_deliveries (next_attempt_at, claim_until)
    WHERE channel = 'EMAIL';
