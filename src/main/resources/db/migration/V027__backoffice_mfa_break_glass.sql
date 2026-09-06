-- Recuperación extraordinaria del MFA del SUPER_ADMIN administrado por infraestructura.
ALTER TABLE backoffice_users
    ADD COLUMN IF NOT EXISTS mfa_break_glass_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS mfa_break_glass_locked_until TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS backoffice_mfa_break_glass_uses (
    id BIGSERIAL PRIMARY KEY,
    credential_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    backoffice_user_id BIGINT NOT NULL REFERENCES backoffice_users(id),
    used_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(1500) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_backoffice_mfa_break_glass_user
    ON backoffice_mfa_break_glass_uses(backoffice_user_id, used_at DESC);
