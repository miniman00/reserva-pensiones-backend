ALTER TABLE payment_chargebacks
    ADD COLUMN IF NOT EXISTS documentation_submission_state VARCHAR(20),
    ADD COLUMN IF NOT EXISTS documentation_submission_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS documentation_submitted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS documentation_submitted_by_backoffice_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS documentation_submission_reason VARCHAR(1500),
    ADD COLUMN IF NOT EXISTS documentation_file_count INTEGER,
    ADD COLUMN IF NOT EXISTS documentation_total_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS documentation_manifest_json TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_payment_chargebacks_documentation_actor'
    ) THEN
        ALTER TABLE payment_chargebacks
            ADD CONSTRAINT fk_payment_chargebacks_documentation_actor
            FOREIGN KEY (documentation_submitted_by_backoffice_user_id) REFERENCES backoffice_users(id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_payment_chargebacks_documentation_counts'
    ) THEN
        ALTER TABLE payment_chargebacks
            ADD CONSTRAINT ck_payment_chargebacks_documentation_counts
            CHECK ((documentation_file_count IS NULL OR documentation_file_count >= 0)
               AND (documentation_total_bytes IS NULL OR documentation_total_bytes >= 0));
    END IF;
END $$;
