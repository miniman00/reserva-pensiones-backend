-- Metadata mínima de webhooks para idempotencia, diagnóstico y conciliación sin persistir el payload completo.
ALTER TABLE payment_provider_events
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(80),
    ADD COLUMN IF NOT EXISTS event_action VARCHAR(120),
    ADD COLUMN IF NOT EXISTS resource_id VARCHAR(190),
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(190),
    ADD COLUMN IF NOT EXISTS live_mode BOOLEAN;

CREATE INDEX IF NOT EXISTS idx_payment_provider_events_resource
    ON payment_provider_events (provider, resource_id, created_at DESC);
