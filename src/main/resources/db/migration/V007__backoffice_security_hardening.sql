-- Endurecimiento de autenticación interna del Backoffice.
-- La credencial real del SUPER_ADMIN administrado por infraestructura no se replica en BD.
ALTER TABLE backoffice_users
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS managed_credential_fingerprint VARCHAR(64);

CREATE TABLE IF NOT EXISTS backoffice_sessions (
    id BIGSERIAL PRIMARY KEY,
    backoffice_user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    csrf_token VARCHAR(100) NOT NULL,
    session_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_backoffice_sessions_user
        FOREIGN KEY (backoffice_user_id)
        REFERENCES backoffice_users(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_backoffice_sessions_user
    ON backoffice_sessions (backoffice_user_id);
CREATE INDEX IF NOT EXISTS idx_backoffice_sessions_expires
    ON backoffice_sessions (expires_at);
