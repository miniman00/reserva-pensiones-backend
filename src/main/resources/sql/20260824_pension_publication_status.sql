-- Ejecutar en producción antes de desplegar esta versión porque application-prod.yml usa ddl-auto=validate.
-- Es incremental y parte de los scripts de los bloques anteriores ya aplicados.
-- Al incorporarse el estado, las pensiones existentes completas quedan PUBLICADAS y las incompletas quedan en BORRADOR.

ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);

UPDATE pensions p
SET status = CASE
    WHEN p.name IS NOT NULL AND btrim(p.name) <> ''
     AND p.description IS NOT NULL AND btrim(p.description) <> ''
     AND p.country_code IS NOT NULL AND btrim(p.country_code) <> ''
     AND p.city IS NOT NULL AND btrim(p.city) <> ''
     AND p.address_line1 IS NOT NULL AND btrim(p.address_line1) <> ''
     AND p.lat IS NOT NULL AND p.lat BETWEEN -90 AND 90
     AND p.lng IS NOT NULL AND p.lng BETWEEN -180 AND 180
     AND (
          (COALESCE(p.capacity_simple, 0) > 0 AND p.price_simple IS NOT NULL AND p.price_simple > 0)
          OR
          (COALESCE(p.capacity_matrimonial, 0) > 0 AND p.price_matrimonial IS NOT NULL AND p.price_matrimonial > 0)
     )
     AND NOT (COALESCE(p.capacity_simple, 0) > 0 AND (p.price_simple IS NULL OR p.price_simple <= 0))
     AND NOT (COALESCE(p.capacity_matrimonial, 0) > 0 AND (p.price_matrimonial IS NULL OR p.price_matrimonial <= 0))
     AND p.featured_image IS NOT NULL AND btrim(p.featured_image) <> ''
     AND EXISTS (
          SELECT 1
          FROM pension_media m
          WHERE m.pension_id = p.id
            AND m.kind = 'IMAGE'
            AND m.filename = p.featured_image
     )
    THEN 'PUBLISHED'
    ELSE 'DRAFT'
END
WHERE p.status IS NULL;

ALTER TABLE pensions
    ALTER COLUMN status SET DEFAULT 'DRAFT';

ALTER TABLE pensions
    ALTER COLUMN status SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pensions_status_updated
    ON pensions (status, updated_at);
