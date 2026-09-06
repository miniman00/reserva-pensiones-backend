-- Bitácora inmutable de acciones administrativas del Backoffice.
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    backoffice_user_id BIGINT NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(120) NOT NULL,
    before_json TEXT,
    after_json TEXT,
    reason VARCHAR(1500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_audit_log_backoffice_user
        FOREIGN KEY (backoffice_user_id)
        REFERENCES backoffice_users(id)
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created_at
    ON admin_audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_actor
    ON admin_audit_log (backoffice_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_action
    ON admin_audit_log (action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_entity
    ON admin_audit_log (entity_type, entity_id, created_at DESC);
