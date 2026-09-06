-- Endurecimiento de autenticación interna del Backoffice.
-- Permite aplicar una ventana temporal real a los intentos fallidos sin acumularlos indefinidamente.
ALTER TABLE backoffice_users
    ADD COLUMN IF NOT EXISTS last_failed_login_at TIMESTAMPTZ;
