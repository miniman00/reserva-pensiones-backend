-- Infraestructura agnóstica de pagos. No habilita cobros por sí misma:
-- APP_PAYMENTS_ENABLED continúa siendo el master switch controlado por servidor.
CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    provider_payment_id VARCHAR(190),
    provider_subscription_id VARCHAR(190),
    merchant_reference VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    plan_version_id BIGINT,
    subscription_period_months INTEGER,
    pension_id BIGINT,
    promotion_product_version_id BIGINT,
    study_center_id BIGINT,
    amount NUMERIC(12,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    provider_status VARCHAR(80),
    fulfilled_at TIMESTAMPTZ,
    fulfillment_error_code VARCHAR(80),
    fulfillment_error_message VARCHAR(500),
    owner_subscription_id BIGINT,
    pension_promotion_id BIGINT,
    approved_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,
    last_provider_sync_at TIMESTAMPTZ,
    created_by_backoffice_user_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payments_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_payments_plan_version FOREIGN KEY (plan_version_id) REFERENCES plan_versions(id),
    CONSTRAINT fk_payments_pension FOREIGN KEY (pension_id) REFERENCES pensions(id),
    CONSTRAINT fk_payments_promotion_product_version FOREIGN KEY (promotion_product_version_id) REFERENCES promotion_product_versions(id),
    CONSTRAINT fk_payments_study_center FOREIGN KEY (study_center_id) REFERENCES study_center_catalog(id),
    CONSTRAINT fk_payments_backoffice_user FOREIGN KEY (created_by_backoffice_user_id) REFERENCES backoffice_users(id),
    CONSTRAINT fk_payments_fulfilled_subscription FOREIGN KEY (owner_subscription_id) REFERENCES owner_subscriptions(id),
    CONSTRAINT fk_payments_fulfilled_promotion FOREIGN KEY (pension_promotion_id) REFERENCES pension_promotions(id),
    CONSTRAINT ck_payments_amount_nonnegative CHECK (amount >= 0),
    CONSTRAINT ck_payments_subscription_period CHECK (subscription_period_months IS NULL OR subscription_period_months BETWEEN 1 AND 12),
    CONSTRAINT ck_payments_purpose_shape CHECK (
        (purpose = 'SUBSCRIPTION'
            AND plan_version_id IS NOT NULL
            AND subscription_period_months IS NOT NULL
            AND pension_id IS NULL
            AND promotion_product_version_id IS NULL
            AND study_center_id IS NULL)
        OR
        (purpose = 'PROMOTION'
            AND plan_version_id IS NULL
            AND subscription_period_months IS NULL
            AND pension_id IS NOT NULL
            AND promotion_product_version_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_merchant_reference ON payments (merchant_reference);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_idempotency_key ON payments (idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_provider_payment
    ON payments (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_payments_user_created ON payments (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_status_created ON payments (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_provider_status ON payments (provider, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_purpose_created ON payments (purpose, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_fulfilled_subscription ON payments (owner_subscription_id) WHERE owner_subscription_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_fulfilled_promotion ON payments (pension_promotion_id) WHERE pension_promotion_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS payment_status_history (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    provider_status VARCHAR(80),
    event_source VARCHAR(30) NOT NULL,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_status_history_payment
        FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_payment_status_history_payment
    ON payment_status_history (payment_id, created_at ASC, id ASC);

-- Registro reservado para idempotencia de webhooks de cualquier proveedor futuro.
CREATE TABLE IF NOT EXISTS payment_provider_events (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    provider_event_id VARCHAR(190) NOT NULL,
    payload_hash VARCHAR(64),
    processed_at TIMESTAMPTZ,
    processing_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_provider_events
    ON payment_provider_events (provider, provider_event_id);

-- Estado del proveedor MOCK. Se persiste para que reiniciar el backend local no cambie
-- el estado simulado ni convierta la conciliación en una prueba dependiente de memoria.
CREATE TABLE IF NOT EXISTS mock_payment_states (
    provider_payment_id VARCHAR(190) PRIMARY KEY,
    idempotency_key VARCHAR(120) NOT NULL,
    provider_subscription_id VARCHAR(190),
    status VARCHAR(30) NOT NULL,
    provider_status VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mock_payment_states_idempotency
    ON mock_payment_states (idempotency_key);

-- V012 reservó payment_id como texto para un proveedor futuro. Ahora que existe una
-- entidad interna de pagos, conservamos cualquier referencia histórica y reutilizamos
-- payment_id como FK estable hacia payments.
ALTER TABLE pension_promotions RENAME COLUMN payment_id TO legacy_payment_reference;
ALTER TABLE pension_promotions ADD COLUMN payment_id BIGINT;
ALTER TABLE pension_promotions
    ADD CONSTRAINT fk_pension_promotions_payment FOREIGN KEY (payment_id) REFERENCES payments(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_pension_promotions_payment
    ON pension_promotions (payment_id)
    WHERE payment_id IS NOT NULL;
