-- Step-up authentication para operaciones críticas del Backoffice.
-- La marca queda en la sesión persistente para no depender de memoria local de una instancia.
ALTER TABLE backoffice_sessions
    ADD COLUMN IF NOT EXISTS last_reauthenticated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reauth_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reauth_locked_until TIMESTAMPTZ;

UPDATE backoffice_sessions
SET last_reauthenticated_at = created_at
WHERE last_reauthenticated_at IS NULL;

ALTER TABLE backoffice_sessions
    ALTER COLUMN last_reauthenticated_at SET NOT NULL;

ALTER TABLE backoffice_sessions
    DROP CONSTRAINT IF EXISTS ck_backoffice_session_reauth_attempts;
ALTER TABLE backoffice_sessions
    ADD CONSTRAINT ck_backoffice_session_reauth_attempts CHECK (reauth_failed_attempts >= 0);
