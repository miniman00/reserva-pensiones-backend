-- Promociones otorgadas/contratadas por una pensión. El estado efectivo se deriva de status + vigencia,
-- evitando depender de un job para que una promoción vencida deje de considerarse activa.
CREATE TABLE IF NOT EXISTS pension_promotions (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    promotion_product_version_id BIGINT,
    promotion_type VARCHAR(30) NOT NULL DEFAULT 'FEATURED',
    target_type VARCHAR(30) NOT NULL,
    study_center_id BIGINT,
    target_value VARCHAR(160),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    price NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'UYU',
    payment_id VARCHAR(160),
    source VARCHAR(30) NOT NULL,
    created_by_backoffice_user_id BIGINT,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(1500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pension_promotions_pension
        FOREIGN KEY (pension_id) REFERENCES pensions(id),
    CONSTRAINT fk_pension_promotions_product_version
        FOREIGN KEY (promotion_product_version_id) REFERENCES promotion_product_versions(id),
    CONSTRAINT fk_pension_promotions_study_center
        FOREIGN KEY (study_center_id) REFERENCES study_center_catalog(id),
    CONSTRAINT fk_pension_promotions_backoffice_user
        FOREIGN KEY (created_by_backoffice_user_id) REFERENCES backoffice_users(id),
    CONSTRAINT ck_pension_promotions_price CHECK (price >= 0),
    CONSTRAINT ck_pension_promotions_dates CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_pension_promotions_study_center_target CHECK (
        (target_type = 'STUDY_CENTER' AND study_center_id IS NOT NULL)
        OR (target_type <> 'STUDY_CENTER' AND study_center_id IS NULL)
    ),
    CONSTRAINT ck_pension_promotions_legacy_end CHECK (
        ends_at IS NOT NULL OR source = 'LEGACY_COMPATIBILITY'
    )
);

CREATE INDEX IF NOT EXISTS idx_pension_promotions_pension_dates
    ON pension_promotions (pension_id, starts_at, ends_at);
CREATE INDEX IF NOT EXISTS idx_pension_promotions_effective
    ON pension_promotions (status, starts_at, ends_at);
CREATE INDEX IF NOT EXISTS idx_pension_promotions_target
    ON pension_promotions (target_type, study_center_id, starts_at, ends_at);
CREATE INDEX IF NOT EXISTS idx_pension_promotions_product_version
    ON pension_promotions (promotion_product_version_id);

-- Compatibilidad: los destacados manuales existentes se representan también como una promoción legada
-- sin vencimiento conocido. No se modifica el boolean pensions.featured ni el ranking público actual.
INSERT INTO pension_promotions (
    pension_id, promotion_product_version_id, promotion_type, target_type, study_center_id, target_value,
    starts_at, ends_at, status, price, currency, payment_id, source, created_by_backoffice_user_id,
    cancelled_at, cancellation_reason
)
SELECT p.id, NULL, 'FEATURED', 'GLOBAL', NULL, NULL,
       COALESCE(p.updated_at, p.created_at, CURRENT_TIMESTAMP), NULL, 'ACTIVE', 0, 'UYU', NULL,
       'LEGACY_COMPATIBILITY', NULL, NULL, NULL
FROM pensions p
WHERE COALESCE(p.featured, false) = true
  AND NOT EXISTS (
      SELECT 1 FROM pension_promotions pp
      WHERE pp.pension_id = p.id AND pp.source = 'LEGACY_COMPATIBILITY'
  );
