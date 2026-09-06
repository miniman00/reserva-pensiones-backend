-- Reembolsos como operaciones de primera clase. Un pago puede tener múltiples devoluciones parciales.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS provider_refunded_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refund_benefit_decision VARCHAR(30),
    ADD COLUMN IF NOT EXISTS refund_benefit_decided_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS refund_benefit_decided_by_backoffice_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS refund_benefit_reason VARCHAR(1500);

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_refund_benefit_actor
        FOREIGN KEY (refund_benefit_decided_by_backoffice_user_id) REFERENCES backoffice_users(id);

ALTER TABLE mock_payment_states
    ADD COLUMN IF NOT EXISTS refunded_amount NUMERIC(12,2) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS payment_refunds (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    provider VARCHAR(40) NOT NULL,
    refund_type VARCHAR(20) NOT NULL,
    requested_amount NUMERIC(12,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    provider_status VARCHAR(120),
    reason VARCHAR(1500) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(600),
    requested_by_backoffice_user_id BIGINT NOT NULL,
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_refunds_actor FOREIGN KEY (requested_by_backoffice_user_id) REFERENCES backoffice_users(id),
    CONSTRAINT ck_payment_refunds_amount_positive CHECK (requested_amount > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_refunds_idempotency ON payment_refunds (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_payment_refunds_payment ON payment_refunds (payment_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_payment_refunds_status ON payment_refunds (status, created_at DESC);
