-- Optimización del plan del marketplace público.
-- pg_trgm permite que LIKE '%texto%' use índices GIN en lugar de depender de
-- recorridos secuenciales cuando el catálogo crezca.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Mantener la misma semántica histórica de q: nombre, descripción, dirección,
-- ciudad y barrio. Los centros de estudio continúan resolviéndose por EXISTS.
CREATE OR REPLACE FUNCTION pension_public_search_text(
    p_name TEXT,
    p_description TEXT,
    p_address_line1 TEXT,
    p_city TEXT,
    p_neighborhood TEXT
)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $function$
    SELECT lower(
        coalesce(p_name, '') || ' ' ||
        coalesce(p_description, '') || ' ' ||
        coalesce(p_address_line1, '') || ' ' ||
        coalesce(p_city, '') || ' ' ||
        coalesce(p_neighborhood, '')
    )
$function$;

-- Búsqueda libre principal. El predicado parcial coincide con la visibilidad
-- básica del marketplace y evita indexar borradores/pausadas/bloqueadas.
CREATE INDEX IF NOT EXISTS idx_pensions_public_search_trgm_v3
    ON pensions USING gin (
        pension_public_search_text(name, description, address_line1, city, neighborhood) gin_trgm_ops
    )
    WHERE status = 'PUBLISHED' AND moderation_blocked = false;

-- qTerms también contempla state, mientras que los filtros explícitos de ciudad
-- y barrio conservan LIKE por separado. Estos índices permiten BitmapAnd/BitmapOr
-- según la combinación concreta elegida por el usuario.
CREATE INDEX IF NOT EXISTS idx_pensions_public_state_trgm_v3
    ON pensions USING gin (lower(state) gin_trgm_ops)
    WHERE status = 'PUBLISHED' AND moderation_blocked = false;

CREATE INDEX IF NOT EXISTS idx_pensions_public_city_trgm_v3
    ON pensions USING gin (lower(city) gin_trgm_ops)
    WHERE status = 'PUBLISHED' AND moderation_blocked = false;

CREATE INDEX IF NOT EXISTS idx_pensions_public_neighborhood_trgm_v3
    ON pensions USING gin (lower(neighborhood) gin_trgm_ops)
    WHERE status = 'PUBLISHED' AND moderation_blocked = false;

-- Autocomplete y filtro por centro de estudio usan búsqueda contiene.
CREATE INDEX IF NOT EXISTS idx_pension_study_centers_lower_trgm_v3
    ON pension_study_centers USING gin (lower(study_center) gin_trgm_ops);

-- El filtro de país aplica UPPER(country_code); indexamos exactamente esa expresión.
CREATE INDEX IF NOT EXISTS idx_pensions_public_country_upper_v3
    ON pensions (upper(country_code), id)
    WHERE status = 'PUBLISHED' AND moderation_blocked = false;

-- La búsqueda pública siempre exige al menos una habitación disponible. Separar
-- ambos tipos permite al planner combinar los índices para el OR de disponibilidad.
CREATE INDEX IF NOT EXISTS idx_pensions_public_available_simple_v3
    ON pensions (updated_at DESC, id)
    WHERE status = 'PUBLISHED'
      AND moderation_blocked = false
      AND available_simple > 0;

CREATE INDEX IF NOT EXISTS idx_pensions_public_available_matrimonial_v3
    ON pensions (updated_at DESC, id)
    WHERE status = 'PUBLISHED'
      AND moderation_blocked = false
      AND available_matrimonial > 0;

-- Actualiza estadísticas inmediatamente después de crear los índices para que el
-- primer tráfico posterior al deploy pueda aprovecharlos sin esperar autovacuum.
ANALYZE pensions;
ANALYZE pension_study_centers;
ANALYZE pension_amenities;
