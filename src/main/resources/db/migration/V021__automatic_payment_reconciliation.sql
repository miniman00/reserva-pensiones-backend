-- Conciliación preventiva: configurable desde Backoffice, segura para múltiples instancias.
ALTER TABLE payment_settings
    ADD COLUMN IF NOT EXISTS automatic_reconciliation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reconciliation_interval_minutes INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS reconciliation_batch_size INTEGER NOT NULL DEFAULT 50,
    ADD COLUMN IF NOT EXISTS reconciliation_lease_owner VARCHAR(120),
    ADD COLUMN IF NOT EXISTS reconciliation_lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reconciliation_last_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reconciliation_last_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reconciliation_last_success BOOLEAN,
    ADD COLUMN IF NOT EXISTS reconciliation_last_message VARCHAR(500);

ALTER TABLE payment_settings
    DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_interval;
ALTER TABLE payment_settings
    ADD CONSTRAINT ck_payment_reconciliation_interval CHECK (reconciliation_interval_minutes BETWEEN 1 AND 1440);
ALTER TABLE payment_settings
    DROP CONSTRAINT IF EXISTS ck_payment_reconciliation_batch;
ALTER TABLE payment_settings
    ADD CONSTRAINT ck_payment_reconciliation_batch CHECK (reconciliation_batch_size BETWEEN 1 AND 200);

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS last_reconciliation_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_reconciliation_error VARCHAR(600);
ALTER TABLE payment_refunds
    ADD COLUMN IF NOT EXISTS last_reconciliation_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_reconciliation_error VARCHAR(600);
ALTER TABLE payment_chargebacks
    ADD COLUMN IF NOT EXISTS last_reconciliation_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_reconciliation_error VARCHAR(600);

CREATE INDEX IF NOT EXISTS idx_payments_auto_reconciliation
    ON payments (status, last_reconciliation_attempt_at, last_provider_sync_at, created_at)
    WHERE status IN ('CREATED','PENDING');
CREATE INDEX IF NOT EXISTS idx_refunds_auto_reconciliation
    ON payment_refunds (status, last_reconciliation_attempt_at, updated_at)
    WHERE status IN ('REQUESTED','UNKNOWN');
CREATE INDEX IF NOT EXISTS idx_chargebacks_auto_reconciliation
    ON payment_chargebacks (status, last_reconciliation_attempt_at, last_synced_at)
    WHERE status = 'OPEN';

CREATE TABLE IF NOT EXISTS payment_reconciliation_runs (
    id BIGSERIAL PRIMARY KEY,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_by_backoffice_user_id BIGINT,
    lease_owner VARCHAR(120) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    payments_checked INTEGER NOT NULL DEFAULT 0,
    payments_changed INTEGER NOT NULL DEFAULT 0,
    refunds_checked INTEGER NOT NULL DEFAULT 0,
    refunds_changed INTEGER NOT NULL DEFAULT 0,
    chargebacks_checked INTEGER NOT NULL DEFAULT 0,
    chargebacks_changed INTEGER NOT NULL DEFAULT 0,
    errors_count INTEGER NOT NULL DEFAULT 0,
    summary_message VARCHAR(1000),
    CONSTRAINT fk_payment_reconciliation_started_by FOREIGN KEY (started_by_backoffice_user_id) REFERENCES backoffice_users(id),
    CONSTRAINT ck_payment_reconciliation_trigger CHECK (trigger_type IN ('AUTOMATIC','MANUAL')),
    CONSTRAINT ck_payment_reconciliation_run_status CHECK (status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_payment_reconciliation_runs_started
    ON payment_reconciliation_runs (started_at DESC, id DESC);
