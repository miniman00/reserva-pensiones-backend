-- Ejecutar en producción antes de desplegar esta versión porque application-prod.yml usa ddl-auto=validate.
CREATE TABLE IF NOT EXISTS pension_inquiries (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    contact_name VARCHAR(120) NOT NULL,
    contact_email VARCHAR(190),
    contact_phone VARCHAR(40),
    room_type VARCHAR(20) NOT NULL,
    move_in_date DATE,
    message VARCHAR(2000),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_pension_inquiries_pension
        FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_pension_inquiries_pension_created
    ON pension_inquiries (pension_id, created_at);

CREATE INDEX IF NOT EXISTS idx_pension_inquiries_status
    ON pension_inquiries (status);
