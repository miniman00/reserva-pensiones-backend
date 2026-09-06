-- Bloque 20: perfil de residentes / orientación a estudiantes.
-- Se deja nullable en registros históricos para no inventar una política que el propietario no confirmó.

ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS resident_profile VARCHAR(24);

ALTER TABLE pensions
    DROP CONSTRAINT IF EXISTS ck_pensions_resident_profile;

ALTER TABLE pensions
    ADD CONSTRAINT ck_pensions_resident_profile
    CHECK (resident_profile IS NULL OR resident_profile IN (
        'OPEN_TO_ALL',
        'STUDENTS_PREFERRED',
        'STUDENTS_ONLY'
    ));
