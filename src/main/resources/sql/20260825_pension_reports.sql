ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS moderation_blocked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS pension_reports (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL REFERENCES pensions(id) ON DELETE CASCADE,
    reporter_user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    reporter_key_hash VARCHAR(64) NULL,
    reporter_email VARCHAR(190) NULL,
    reason VARCHAR(40) NOT NULL,
    details VARCHAR(1200) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'NEW',
    admin_notes VARCHAR(1500) NULL,
    reviewed_by_user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pension_reports_status_created
    ON pension_reports(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_pension_reports_pension
    ON pension_reports(pension_id);

CREATE INDEX IF NOT EXISTS idx_pension_reports_reporter_user
    ON pension_reports(reporter_user_id);

CREATE INDEX IF NOT EXISTS idx_pension_reports_reporter_key
    ON pension_reports(reporter_key_hash);
