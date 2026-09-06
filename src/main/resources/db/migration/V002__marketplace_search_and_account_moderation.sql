-- Mejoras del marketplace: anuncios destacados y suspensión de cuentas por moderación.

ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS featured BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE pensions SET featured = FALSE WHERE featured IS NULL;

CREATE INDEX IF NOT EXISTS idx_pensions_public_featured
    ON pensions (status, moderation_blocked, featured DESC, updated_at DESC);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS suspended BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(500);

UPDATE users SET suspended = FALSE WHERE suspended IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_suspended ON users (suspended);
