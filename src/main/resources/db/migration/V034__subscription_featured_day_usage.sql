-- Ledger de consumo de los días destacados incluidos en una suscripción.
-- featured_days se interpreta como beneficio disponible por ciclo mensual de la suscripción.
CREATE TABLE IF NOT EXISTS subscription_featured_day_usage (
    id BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT NOT NULL,
    pension_id BIGINT NOT NULL,
    promotion_id BIGINT NOT NULL,
    cycle_start TIMESTAMPTZ NOT NULL,
    cycle_end TIMESTAMPTZ NOT NULL,
    days INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_subscription_featured_usage_subscription
        FOREIGN KEY (subscription_id) REFERENCES owner_subscriptions(id),
    CONSTRAINT fk_subscription_featured_usage_pension
        FOREIGN KEY (pension_id) REFERENCES pensions(id),
    CONSTRAINT fk_subscription_featured_usage_promotion
        FOREIGN KEY (promotion_id) REFERENCES pension_promotions(id),
    CONSTRAINT uq_subscription_featured_usage_promotion UNIQUE (promotion_id),
    CONSTRAINT ck_subscription_featured_usage_days CHECK (days > 0),
    CONSTRAINT ck_subscription_featured_usage_cycle CHECK (cycle_end > cycle_start)
);

CREATE INDEX IF NOT EXISTS idx_subscription_featured_usage_cycle
    ON subscription_featured_day_usage (subscription_id, cycle_start);
CREATE INDEX IF NOT EXISTS idx_subscription_featured_usage_pension
    ON subscription_featured_day_usage (pension_id, created_at DESC);
