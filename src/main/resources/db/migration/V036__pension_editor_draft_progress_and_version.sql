-- Persistencia del progreso del wizard y bloqueo optimista para evitar lost updates.
ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS draft_step varchar(24);

ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

ALTER TABLE pensions
    ADD CONSTRAINT ck_pensions_draft_step
    CHECK (draft_step IS NULL OR draft_step IN ('basic','location','characteristics','pricing','media','contact','publish'));
