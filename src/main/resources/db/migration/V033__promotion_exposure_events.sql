CREATE TABLE IF NOT EXISTS promotion_exposure_events (
    id BIGSERIAL PRIMARY KEY,
    promotion_id BIGINT NOT NULL,
    pension_id BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    visitor_hash VARCHAR(64) NOT NULL,
    occurred_on DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_promotion_exposure_event_type CHECK (event_type IN ('IMPRESSION', 'CLICK')),
    CONSTRAINT uk_promotion_exposure_event UNIQUE (promotion_id, event_type, visitor_hash, occurred_on),
    CONSTRAINT fk_promotion_exposure_event_promotion FOREIGN KEY (promotion_id)
        REFERENCES pension_promotions(id) ON DELETE CASCADE,
    CONSTRAINT fk_promotion_exposure_event_pension FOREIGN KEY (pension_id)
        REFERENCES pensions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_promotion_exposure_events_promotion_type_day
    ON promotion_exposure_events (promotion_id, event_type, occurred_on);

CREATE INDEX IF NOT EXISTS idx_promotion_exposure_events_pension_day
    ON promotion_exposure_events (pension_id, occurred_on);
