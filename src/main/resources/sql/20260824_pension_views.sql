-- Ejecutar en producción antes de desplegar esta versión porque application-prod.yml usa ddl-auto=validate.
-- Una fila representa una visita única por navegador, pensión y día.
-- El identificador del navegador se guarda como SHA-256; no se almacena IP ni información personal.

CREATE TABLE IF NOT EXISTS pension_views (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    visitor_hash VARCHAR(64) NOT NULL,
    viewed_on DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_pension_views_pension
        FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE,
    CONSTRAINT uk_pension_views_pension_visitor_day
        UNIQUE (pension_id, visitor_hash, viewed_on)
);

CREATE INDEX IF NOT EXISTS idx_pension_views_pension_day
    ON pension_views (pension_id, viewed_on);

CREATE INDEX IF NOT EXISTS idx_pension_views_day
    ON pension_views (viewed_on);
