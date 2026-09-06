-- Bloque 19: tipo de admisión de la residencia.
-- No se inventa un valor para registros históricos: quedan NULL hasta que el propietario lo confirme.
ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS admission_type VARCHAR(20);

ALTER TABLE pensions
    DROP CONSTRAINT IF EXISTS chk_pensions_admission_type;

ALTER TABLE pensions
    ADD CONSTRAINT chk_pensions_admission_type
    CHECK (admission_type IS NULL OR admission_type IN ('MIXED', 'WOMEN_ONLY', 'MEN_ONLY'));
