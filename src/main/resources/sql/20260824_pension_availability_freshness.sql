-- Bloque 12: vigencia de disponibilidad
-- Ejecutar antes de desplegar el backend 12 en ambientes con ddl-auto=validate.
-- Las pensiones existentes quedan sin fecha hasta que propietario/colaborador
-- confirme los cupos; no se inventa una fecha histórica de confirmación.

ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS availability_updated_at TIMESTAMPTZ;
