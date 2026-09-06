ALTER TABLE backoffice_users
    ADD COLUMN IF NOT EXISTS mfa_secret_ciphertext TEXT,
    ADD COLUMN IF NOT EXISTS mfa_pending_secret_ciphertext TEXT,
    ADD COLUMN IF NOT EXISTS mfa_pending_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS mfa_enabled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS mfa_last_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS mfa_last_accepted_counter BIGINT,
    ADD COLUMN IF NOT EXISTS mfa_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS mfa_locked_until TIMESTAMPTZ;

ALTER TABLE backoffice_sessions
    ADD COLUMN IF NOT EXISTS mfa_verified_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS backoffice_mfa_recovery_codes (
    id BIGSERIAL PRIMARY KEY,
    backoffice_user_id BIGINT NOT NULL REFERENCES backoffice_users(id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at TIMESTAMPTZ,
    CONSTRAINT uk_backoffice_mfa_recovery_code_hash UNIQUE (code_hash)
);

CREATE INDEX IF NOT EXISTS idx_backoffice_mfa_recovery_user_unused
    ON backoffice_mfa_recovery_codes(backoffice_user_id, used_at);
