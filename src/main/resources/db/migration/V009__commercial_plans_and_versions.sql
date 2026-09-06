-- Catálogo comercial versionado. No activa monetización ni pagos por sí mismo:
-- los master switches continúan controlados exclusivamente por configuración del servidor.
CREATE TABLE IF NOT EXISTS plans (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(30) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_plans_code_upper ON plans (UPPER(code));
CREATE INDEX IF NOT EXISTS idx_plans_active_name ON plans (active, name);

CREATE TABLE IF NOT EXISTS plan_versions (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    monthly_price NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'UYU',
    max_pensions INTEGER,
    max_collaborators INTEGER,
    max_photos INTEGER,
    max_videos INTEGER,
    featured_days INTEGER NOT NULL DEFAULT 0,
    advanced_analytics BOOLEAN NOT NULL DEFAULT FALSE,
    inquiry_history BOOLEAN NOT NULL DEFAULT FALSE,
    consolidated_analytics BOOLEAN NOT NULL DEFAULT FALSE,
    export_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    effective_from TIMESTAMPTZ,
    effective_until TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    published_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_versions_plan
        FOREIGN KEY (plan_id) REFERENCES plans(id),
    CONSTRAINT ck_plan_versions_version_positive CHECK (version > 0),
    CONSTRAINT ck_plan_versions_price_nonnegative CHECK (monthly_price >= 0),
    CONSTRAINT ck_plan_versions_max_pensions CHECK (max_pensions IS NULL OR max_pensions >= 0),
    CONSTRAINT ck_plan_versions_max_collaborators CHECK (max_collaborators IS NULL OR max_collaborators >= 0),
    CONSTRAINT ck_plan_versions_max_photos CHECK (max_photos IS NULL OR max_photos >= 0),
    CONSTRAINT ck_plan_versions_max_videos CHECK (max_videos IS NULL OR max_videos >= 0),
    CONSTRAINT ck_plan_versions_featured_days CHECK (featured_days >= 0),
    CONSTRAINT ck_plan_versions_dates CHECK (effective_until IS NULL OR effective_from IS NULL OR effective_until > effective_from)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_plan_versions_plan_version
    ON plan_versions (plan_id, version);
CREATE INDEX IF NOT EXISTS idx_plan_versions_plan_status_from
    ON plan_versions (plan_id, status, effective_from);

-- Los planes base existen desde el inicio, pero deliberadamente no se crean versiones
-- con precios/beneficios ficticios. El Backoffice debe definir y publicar cada versión.
INSERT INTO plans (code, name, description, active)
VALUES
    ('FREE', 'Free', 'Plan base para propietarios que comienzan a publicar.', TRUE),
    ('PRO', 'Pro', 'Plan profesional para propietarios con mayores necesidades operativas.', TRUE),
    ('BUSINESS', 'Business', 'Plan empresarial para residencias y organizaciones con varias operaciones.', TRUE)
ON CONFLICT DO NOTHING;
