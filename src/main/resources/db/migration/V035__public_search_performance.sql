-- Índices enfocados en el marketplace público. Mantenerlos parciales reduce tamaño
-- y costo de mantenimiento porque los borradores/pausadas no participan en búsquedas.

CREATE INDEX IF NOT EXISTS idx_pensions_public_updated_v2
    ON pensions (updated_at DESC, id ASC)
    WHERE status = 'PUBLISHED' AND moderation_blocked = false;

CREATE INDEX IF NOT EXISTS idx_pensions_public_coordinates_v2
    ON pensions (lat, lng)
    WHERE status = 'PUBLISHED'
      AND moderation_blocked = false
      AND lat IS NOT NULL
      AND lng IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pensions_public_simple_price_v2
    ON pensions (price_simple, id)
    WHERE status = 'PUBLISHED'
      AND moderation_blocked = false
      AND available_simple > 0
      AND price_simple IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pensions_public_matrimonial_price_v2
    ON pensions (price_matrimonial, id)
    WHERE status = 'PUBLISHED'
      AND moderation_blocked = false
      AND available_matrimonial > 0
      AND price_matrimonial IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pension_amenities_value_pension
    ON pension_amenities (amenity, pension_id);

-- La búsqueda pública pregunta por promoción efectiva correlacionando primero por
-- pension_id. La condición status='ACTIVE' es constante, por lo que un índice parcial
-- evita recorrer promociones canceladas o históricas para el ranking.
CREATE INDEX IF NOT EXISTS idx_pension_promotions_public_active_scope
    ON pension_promotions (pension_id, target_type, study_center_id, starts_at, ends_at, id DESC)
    WHERE status = 'ACTIVE';
