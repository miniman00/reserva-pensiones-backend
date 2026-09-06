-- Integra la Campaña Fundadores con el motor real de entitlements y destacados.
-- El plan otorgado queda congelado por beneficiario para que cambios posteriores de la campaña
-- no alteren retroactivamente los beneficios ya concedidos.
ALTER TABLE launch_campaign_beneficiaries
    ADD COLUMN IF NOT EXISTS plan_version_id BIGINT;

UPDATE launch_campaign_beneficiaries b
   SET plan_version_id = c.benefit_plan_version_id
  FROM launch_campaigns c
 WHERE b.campaign_id = c.id
   AND b.plan_version_id IS NULL
   AND c.benefit_plan_version_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'fk_launch_campaign_beneficiaries_plan_version'
    ) THEN
        ALTER TABLE launch_campaign_beneficiaries
            ADD CONSTRAINT fk_launch_campaign_beneficiaries_plan_version
            FOREIGN KEY (plan_version_id) REFERENCES plan_versions(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_launch_campaign_beneficiaries_effective_user
    ON launch_campaign_beneficiaries (user_id, status, granted_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_launch_campaign_beneficiaries_plan_version
    ON launch_campaign_beneficiaries (plan_version_id);

-- Los destacados Fundador reutilizan pension_promotions con importe cero y una fuente explícita.
-- No se crea PaymentRecord y por tanto no contaminan ventas, conciliación ni métricas de cobro.
CREATE INDEX IF NOT EXISTS idx_pension_promotions_owner_source_effective
    ON pension_promotions (source, status, starts_at, ends_at, pension_id);
