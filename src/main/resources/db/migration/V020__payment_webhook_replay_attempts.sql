ALTER TABLE payment_provider_events
    ADD COLUMN IF NOT EXISTS manual_replay_in_progress BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS manual_replay_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_manual_replay_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS manual_replay_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS payment_provider_event_attempts (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES payment_provider_events(id) ON DELETE CASCADE,
    source VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    backoffice_user_id BIGINT REFERENCES backoffice_users(id),
    reason VARCHAR(1500),
    result_message VARCHAR(500),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_payment_provider_event_attempts_event
    ON payment_provider_event_attempts (event_id, started_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_payment_provider_event_attempts_status
    ON payment_provider_event_attempts (status, started_at DESC);

-- Los eventos históricos conservan al menos una representación de su procesamiento original.
INSERT INTO payment_provider_event_attempts (
    event_id, source, status, result_message, error_message, started_at, completed_at
)
SELECT e.id,
       'WEBHOOK_DELIVERY',
       CASE
           WHEN e.processed_at IS NOT NULL THEN 'SUCCEEDED'
           WHEN e.processing_error IS NOT NULL THEN 'FAILED'
           ELSE 'IN_PROGRESS'
       END,
       CASE WHEN e.processed_at IS NOT NULL THEN 'Evento histórico procesado' ELSE NULL END,
       CASE WHEN e.processed_at IS NULL THEN e.processing_error ELSE NULL END,
       e.created_at,
       e.processed_at
  FROM payment_provider_events e
 WHERE NOT EXISTS (
       SELECT 1 FROM payment_provider_event_attempts a WHERE a.event_id = e.id
 );
