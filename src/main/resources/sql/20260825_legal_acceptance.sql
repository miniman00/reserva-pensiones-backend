-- Bloque 16: versionado de aceptación legal y consentimiento de formularios.
-- Las aceptaciones anteriores no se inventan: usuarios existentes deberán aceptar
-- los documentos vigentes y consultas/reportes históricos conservarán NULL.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS terms_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS privacy_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS legal_accepted_at TIMESTAMPTZ;

ALTER TABLE pension_inquiries
    ADD COLUMN IF NOT EXISTS terms_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS privacy_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS consent_accepted_at TIMESTAMPTZ;

ALTER TABLE pension_reports
    ADD COLUMN IF NOT EXISTS terms_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS privacy_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS consent_accepted_at TIMESTAMPTZ;
