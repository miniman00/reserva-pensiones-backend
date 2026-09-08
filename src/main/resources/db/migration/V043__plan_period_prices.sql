CREATE TABLE IF NOT EXISTS plan_version_period_prices (
    id BIGSERIAL PRIMARY KEY,
    plan_version_id BIGINT NOT NULL,
    period_months INTEGER NOT NULL,
    total_price NUMERIC(12,2) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_plan_version_period_prices_version
        FOREIGN KEY (plan_version_id) REFERENCES plan_versions(id) ON DELETE CASCADE,
    CONSTRAINT uq_plan_version_period_prices_version_months
        UNIQUE (plan_version_id, period_months),
    CONSTRAINT ck_plan_version_period_prices_supported_period
        CHECK (period_months IN (1, 3, 6, 12)),
    CONSTRAINT ck_plan_version_period_prices_nonnegative
        CHECK (total_price >= 0)
);

CREATE INDEX IF NOT EXISTS idx_plan_version_period_prices_version_enabled
    ON plan_version_period_prices (plan_version_id, enabled, period_months);

-- Preserve the pricing semantics of every existing version. Before this migration the
-- checkout amount was monthly_price * subscription_period_months. Backfilling the four
-- supported periods makes the transition non-breaking for already published versions.
INSERT INTO plan_version_period_prices (plan_version_id, period_months, total_price, enabled)
SELECT id, 1, monthly_price, TRUE FROM plan_versions
ON CONFLICT (plan_version_id, period_months) DO NOTHING;

INSERT INTO plan_version_period_prices (plan_version_id, period_months, total_price, enabled)
SELECT id, 3, monthly_price * 3, TRUE FROM plan_versions
ON CONFLICT (plan_version_id, period_months) DO NOTHING;

INSERT INTO plan_version_period_prices (plan_version_id, period_months, total_price, enabled)
SELECT id, 6, monthly_price * 6, TRUE FROM plan_versions
ON CONFLICT (plan_version_id, period_months) DO NOTHING;

INSERT INTO plan_version_period_prices (plan_version_id, period_months, total_price, enabled)
SELECT id, 12, monthly_price * 12, TRUE FROM plan_versions
ON CONFLICT (plan_version_id, period_months) DO NOTHING;
