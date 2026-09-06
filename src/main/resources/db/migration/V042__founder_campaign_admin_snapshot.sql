-- Congela por beneficiario el máximo de destacados prometido al momento del grant.
-- Así un cambio posterior de configuración de campaña solo afecta a futuros Fundadores.
ALTER TABLE launch_campaign_beneficiaries
    ADD COLUMN IF NOT EXISTS max_featured_pensions_snapshot INTEGER;

UPDATE launch_campaign_beneficiaries b
   SET max_featured_pensions_snapshot = c.max_featured_pensions
  FROM launch_campaigns c
 WHERE b.campaign_id = c.id
   AND b.max_featured_pensions_snapshot IS NULL;

ALTER TABLE launch_campaign_beneficiaries
    ALTER COLUMN max_featured_pensions_snapshot SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_launch_campaign_beneficiary_featured_snapshot'
    ) THEN
        ALTER TABLE launch_campaign_beneficiaries
            ADD CONSTRAINT ck_launch_campaign_beneficiary_featured_snapshot
            CHECK (max_featured_pensions_snapshot >= 0);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_launch_campaign_events_actor_created
    ON launch_campaign_events (backoffice_user_id, created_at DESC);
