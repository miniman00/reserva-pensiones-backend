-- Contracargos/disputas de pagos como incidencias financieras de primera clase.
CREATE TABLE IF NOT EXISTS payment_chargebacks (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT,
    provider VARCHAR(40) NOT NULL,
    provider_chargeback_id VARCHAR(190) NOT NULL,
    provider_payment_id VARCHAR(190),
    currency VARCHAR(3),
    amount NUMERIC(12,2),
    reason VARCHAR(160),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    coverage_eligible BOOLEAN,
    coverage_applied BOOLEAN,
    documentation_required BOOLEAN,
    documentation_status VARCHAR(80),
    documentation_deadline TIMESTAMPTZ,
    live_mode BOOLEAN,
    provider_created_at TIMESTAMPTZ,
    provider_updated_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    benefit_decision VARCHAR(30),
    benefit_decided_at TIMESTAMPTZ,
    benefit_decided_by_backoffice_user_id BIGINT,
    benefit_reason VARCHAR(1500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_chargebacks_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT fk_payment_chargebacks_benefit_actor FOREIGN KEY (benefit_decided_by_backoffice_user_id) REFERENCES backoffice_users(id),
    CONSTRAINT ck_payment_chargebacks_amount_nonnegative CHECK (amount IS NULL OR amount >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_chargebacks_provider_id
    ON payment_chargebacks (provider, provider_chargeback_id);
CREATE INDEX IF NOT EXISTS idx_payment_chargebacks_payment
    ON payment_chargebacks (payment_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_payment_chargebacks_status
    ON payment_chargebacks (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_chargebacks_provider_payment
    ON payment_chargebacks (provider, provider_payment_id);
