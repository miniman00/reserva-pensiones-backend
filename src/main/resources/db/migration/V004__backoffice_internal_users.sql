-- Usuarios internos del Backoffice. No se mezclan con users, que representa cuentas del marketplace.
CREATE TABLE IF NOT EXISTS backoffice_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    email VARCHAR(190),
    role VARCHAR(30) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    session_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_backoffice_users_username_lower
    ON backoffice_users (lower(username));
CREATE INDEX IF NOT EXISTS idx_backoffice_users_active
    ON backoffice_users (active);
CREATE INDEX IF NOT EXISTS idx_backoffice_users_role
    ON backoffice_users (role);

-- Compatibilidad: los reportes antiguos conservan reviewed_by_user_id.
-- Las nuevas revisiones administrativas usan exclusivamente el usuario interno.
ALTER TABLE pension_reports
    ADD COLUMN IF NOT EXISTS reviewed_by_backoffice_user_id BIGINT;

DO $do$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_reports_backoffice_reviewer'
    ) THEN
        ALTER TABLE pension_reports
            ADD CONSTRAINT fk_pension_reports_backoffice_reviewer
            FOREIGN KEY (reviewed_by_backoffice_user_id)
            REFERENCES backoffice_users(id)
            ON DELETE SET NULL;
    END IF;
END
$do$;

CREATE INDEX IF NOT EXISTS idx_pension_reports_backoffice_reviewer
    ON pension_reports (reviewed_by_backoffice_user_id);
