-- Productos comerciales de destacado. Cada SKU define un target y duración estables.
-- El precio se versiona para que futuras compras/promociones puedan conservar las condiciones históricas.
CREATE TABLE IF NOT EXISTS promotion_products (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(700),
    target_type VARCHAR(30) NOT NULL,
    duration_days INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_promotion_products_duration CHECK (duration_days > 0 AND duration_days <= 3650)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_promotion_products_code_upper
    ON promotion_products (UPPER(code));
CREATE INDEX IF NOT EXISTS idx_promotion_products_target_duration
    ON promotion_products (target_type, duration_days, active);

CREATE TABLE IF NOT EXISTS promotion_product_versions (
    id BIGSERIAL PRIMARY KEY,
    promotion_product_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    price NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'UYU',
    effective_from TIMESTAMPTZ,
    effective_until TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    published_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_promotion_product_versions_product
        FOREIGN KEY (promotion_product_id) REFERENCES promotion_products(id),
    CONSTRAINT ck_promotion_product_versions_version CHECK (version > 0),
    CONSTRAINT ck_promotion_product_versions_price CHECK (price >= 0),
    CONSTRAINT ck_promotion_product_versions_dates
        CHECK (effective_until IS NULL OR effective_from IS NULL OR effective_until > effective_from)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_promotion_product_versions_product_version
    ON promotion_product_versions (promotion_product_id, version);
CREATE INDEX IF NOT EXISTS idx_promotion_product_versions_product_status_from
    ON promotion_product_versions (promotion_product_id, status, effective_from);

-- Catálogo inicial discutido. No se insertan precios: cada SKU debe recibir una versión
-- de precio explícita desde Backoffice antes de poder considerarse ofrecible.
INSERT INTO promotion_products (code, name, description, target_type, duration_days, active)
VALUES
    ('FEATURE_GLOBAL_7D', 'Destacado general · 7 días', 'Mayor visibilidad general durante 7 días.', 'GLOBAL', 7, TRUE),
    ('FEATURE_GLOBAL_15D', 'Destacado general · 15 días', 'Mayor visibilidad general durante 15 días.', 'GLOBAL', 15, TRUE),
    ('FEATURE_GLOBAL_30D', 'Destacado general · 30 días', 'Mayor visibilidad general durante 30 días.', 'GLOBAL', 30, TRUE),
    ('FEATURE_STUDY_CENTER_7D', 'Destacado por centro · 7 días', 'Mayor visibilidad cerca de un centro de estudio seleccionado durante 7 días.', 'STUDY_CENTER', 7, TRUE),
    ('FEATURE_STUDY_CENTER_15D', 'Destacado por centro · 15 días', 'Mayor visibilidad cerca de un centro de estudio seleccionado durante 15 días.', 'STUDY_CENTER', 15, TRUE),
    ('FEATURE_STUDY_CENTER_30D', 'Destacado por centro · 30 días', 'Mayor visibilidad cerca de un centro de estudio seleccionado durante 30 días.', 'STUDY_CENTER', 30, TRUE)
ON CONFLICT DO NOTHING;
