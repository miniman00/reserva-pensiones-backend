-- Suscripciones de propietarios a versiones inmutables de planes.
-- Este modelo no activa paywalls ni pagos por sí mismo: APP_MONETIZATION_ENABLED y
-- APP_PAYMENTS_ENABLED continúan siendo master switches de servidor.
CREATE TABLE IF NOT EXISTS owner_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    plan_version_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    provider VARCHAR(40),
    provider_subscription_id VARCHAR(190),
    source VARCHAR(30) NOT NULL,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_owner_subscriptions_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_owner_subscriptions_plan_version
        FOREIGN KEY (plan_version_id) REFERENCES plan_versions(id),
    CONSTRAINT ck_owner_subscriptions_status
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_owner_subscriptions_source
        CHECK (source IN ('PAYMENT', 'ADMIN_GRANT', 'PROMOTION', 'PARTNERSHIP')),
    CONSTRAINT ck_owner_subscriptions_dates
        CHECK (expires_at > started_at),
    CONSTRAINT ck_owner_subscriptions_cancelled
        CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL) OR status <> 'CANCELLED')
);

CREATE INDEX IF NOT EXISTS idx_owner_subscriptions_user_status
    ON owner_subscriptions (user_id, status, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_owner_subscriptions_plan_version
    ON owner_subscriptions (plan_version_id);
CREATE INDEX IF NOT EXISTS idx_owner_subscriptions_expires
    ON owner_subscriptions (expires_at, status);
CREATE INDEX IF NOT EXISTS idx_owner_subscriptions_source_created
    ON owner_subscriptions (source, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_owner_subscriptions_provider_reference
    ON owner_subscriptions (provider, provider_subscription_id)
    WHERE provider IS NOT NULL AND provider_subscription_id IS NOT NULL;
