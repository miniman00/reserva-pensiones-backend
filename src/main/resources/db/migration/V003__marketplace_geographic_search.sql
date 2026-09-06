-- Búsqueda exacta dentro de un polígono dibujado en el mapa.
-- Usa los tipos geométricos nativos de PostgreSQL; no requiere PostGIS.
CREATE OR REPLACE FUNCTION pension_point_in_polygon(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_polygon TEXT
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $function$
    SELECT CASE
        WHEN p_lat IS NULL OR p_lng IS NULL OR p_polygon IS NULL OR btrim(p_polygon) = '' THEN FALSE
        ELSE point(p_lng, p_lat) <@ CAST(p_polygon AS polygon)
    END
$function$;
