ALTER TABLE pension_reports
    ADD COLUMN IF NOT EXISTS resolution VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_pension_reports_resolution_created
    ON pension_reports (resolution, created_at DESC);
