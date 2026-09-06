-- Bloque 22: catálogo georreferenciado de centros de estudio.
-- Mantiene pension_study_centers como fuente de asociaciones por nombre y agrega
-- un catálogo global para coordenadas/verificación sin romper datos existentes.

CREATE TABLE IF NOT EXISTS study_center_catalog (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(140) NOT NULL,
    normalized_name VARCHAR(140) NOT NULL,
    country_code VARCHAR(2),
    city VARCHAR(100),
    lat DOUBLE PRECISION,
    lng DOUBLE PRECISION,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_study_center_catalog_normalized_name UNIQUE (normalized_name),
    CONSTRAINT ck_study_center_catalog_lat CHECK (lat IS NULL OR (lat >= -90 AND lat <= 90)),
    CONSTRAINT ck_study_center_catalog_lng CHECK (lng IS NULL OR (lng >= -180 AND lng <= 180)),
    CONSTRAINT ck_study_center_catalog_coords_pair CHECK ((lat IS NULL) = (lng IS NULL))
);

-- Importa automáticamente todos los nombres ya utilizados en pensiones.
-- Se conservan sin coordenadas y quedan pendientes de georreferenciar/verificar.
INSERT INTO study_center_catalog (name, normalized_name, country_code, city, verified)
SELECT MIN(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g'))) AS name,
       LOWER(TRANSLATE(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g')),
             'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun')) AS normalized_name,
       MIN(p.country_code) AS country_code,
       MIN(p.city) AS city,
       FALSE
FROM pension_study_centers psc
JOIN pensions p ON p.id = psc.pension_id
WHERE psc.study_center IS NOT NULL
  AND TRIM(psc.study_center) <> ''
GROUP BY LOWER(TRANSLATE(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g')),
              'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun'))
ON CONFLICT (normalized_name) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_study_center_catalog_name_lower
    ON study_center_catalog (LOWER(name));

CREATE INDEX IF NOT EXISTS idx_study_center_catalog_verified
    ON study_center_catalog (verified);
