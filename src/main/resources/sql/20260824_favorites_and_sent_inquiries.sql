-- Ejecutar en producción antes de desplegar esta versión porque application-prod.yml usa ddl-auto=validate.
-- Este script parte de 20260824_pension_inquiries.sql aplicado.

ALTER TABLE pension_inquiries
    ADD COLUMN IF NOT EXISTS requester_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_inquiries_requester'
    ) THEN
        ALTER TABLE pension_inquiries
            ADD CONSTRAINT fk_pension_inquiries_requester
            FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_pension_inquiries_requester_created
    ON pension_inquiries (requester_id, created_at);

CREATE TABLE IF NOT EXISTS pension_favorites (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    pension_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_pension_favorites_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_pension_favorites_pension
        FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE,
    CONSTRAINT uk_pension_favorites_user_pension UNIQUE (user_id, pension_id)
);

CREATE INDEX IF NOT EXISTS idx_pension_favorites_user_created
    ON pension_favorites (user_id, created_at);

CREATE INDEX IF NOT EXISTS idx_pension_favorites_pension
    ON pension_favorites (pension_id);
