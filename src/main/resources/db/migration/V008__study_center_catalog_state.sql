-- Estado operativo del catálogo de centros de estudio.
-- No se eliminan centros porque su id será la referencia estable para funcionalidades comerciales futuras.
ALTER TABLE study_center_catalog
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_study_center_catalog_active
    ON study_center_catalog (active);

CREATE INDEX IF NOT EXISTS idx_study_center_catalog_active_verified
    ON study_center_catalog (active, verified);
