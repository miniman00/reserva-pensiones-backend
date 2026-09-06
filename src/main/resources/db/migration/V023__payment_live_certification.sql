-- Certificación operativa Sandbox -> LIVE y trazabilidad del modo de proveedor por pago.
-- provider_mode queda nullable para pagos históricos: no es seguro inferir TEST/SANDBOX/LIVE a posteriori.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS provider_mode VARCHAR(20);

ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS ck_payments_provider_mode;
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_provider_mode
        CHECK (provider_mode IS NULL OR provider_mode IN ('TEST','SANDBOX','LIVE'));

CREATE INDEX IF NOT EXISTS idx_payments_provider_mode_created
    ON payments (provider, provider_mode, created_at DESC);

CREATE TABLE IF NOT EXISTS payment_provider_certification_runs (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_mode VARCHAR(20) NOT NULL,
    started_by_backoffice_user_id BIGINT NOT NULL REFERENCES backoffice_users(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pre_live_validated_by_backoffice_user_id BIGINT REFERENCES backoffice_users(id),
    pre_live_validated_at TIMESTAMPTZ,
    pre_live_snapshot_json TEXT,
    completed_at TIMESTAMPTZ,
    aborted_at TIMESTAMPTZ,
    completion_snapshot_json TEXT,
    abort_reason VARCHAR(1500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_payment_provider_certification_status CHECK (status IN ('ACTIVE','CERTIFIED','ABORTED')),
    CONSTRAINT ck_payment_provider_certification_started_mode CHECK (started_mode IN ('TEST','SANDBOX'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_provider_certification_active
    ON payment_provider_certification_runs (provider)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_payment_provider_certification_history
    ON payment_provider_certification_runs (provider, started_at DESC);

CREATE TABLE IF NOT EXISTS payment_provider_certification_checks (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES payment_provider_certification_runs(id) ON DELETE CASCADE,
    check_code VARCHAR(80) NOT NULL,
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_by_backoffice_user_id BIGINT REFERENCES backoffice_users(id),
    confirmed_at TIMESTAMPTZ,
    reason VARCHAR(1500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_provider_certification_check UNIQUE (run_id, check_code),
    CONSTRAINT ck_payment_provider_certification_check_code CHECK (check_code IN (
        'SANDBOX_ORDER_WEBHOOK_CONFIGURED',
        'SANDBOX_CHARGEBACK_WEBHOOK_CONFIGURED',
        'PRODUCTION_CREDENTIALS_ACTIVATED',
        'PRODUCTION_ORDER_WEBHOOK_CONFIGURED',
        'PRODUCTION_CHARGEBACK_WEBHOOK_CONFIGURED'
    )),
    CONSTRAINT ck_payment_provider_certification_confirmation CHECK (
        (confirmed = FALSE) OR (confirmed_by_backoffice_user_id IS NOT NULL AND confirmed_at IS NOT NULL)
    )
);
