-- Recordatorios idempotentes incluso si el backend escala a más de una instancia.
ALTER TABLE user_notifications
    ADD COLUMN IF NOT EXISTS dedup_key varchar(190);

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_notifications_dedup_key_v4
    ON user_notifications(dedup_key);

-- Índices para mantener rápido el corte de frescura del catálogo y los recordatorios.
CREATE INDEX IF NOT EXISTS idx_pensions_public_availability_fresh_v4
    ON pensions (availability_updated_at DESC, id)
    WHERE status = 'PUBLISHED' AND moderation_blocked = false;

CREATE INDEX IF NOT EXISTS idx_pensions_draft_updated_v4
    ON pensions (updated_at ASC, id)
    WHERE status = 'DRAFT';

ANALYZE pensions;
