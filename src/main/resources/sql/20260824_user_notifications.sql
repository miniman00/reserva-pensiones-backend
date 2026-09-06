-- Ejecutar en producción antes de desplegar esta versión porque application-prod.yml usa ddl-auto=validate.
-- Este script es incremental y parte de los scripts de los bloques 03 y 04 ya aplicados.

CREATE TABLE IF NOT EXISTS user_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(180) NOT NULL,
    message VARCHAR(700),
    link VARCHAR(500),
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_user_notifications_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_created
    ON user_notifications (user_id, created_at);

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_read
    ON user_notifications (user_id, read_at);
