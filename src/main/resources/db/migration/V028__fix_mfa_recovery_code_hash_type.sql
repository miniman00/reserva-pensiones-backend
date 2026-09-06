-- V026 created code_hash as CHAR(64), while the JPA entity maps it as VARCHAR(64).
-- Hibernate schema validation distinguishes PostgreSQL bpchar from varchar, so align
-- the physical column type without changing the already-applied V026 migration.
ALTER TABLE backoffice_mfa_recovery_codes
    ALTER COLUMN code_hash TYPE VARCHAR(64)
    USING BTRIM(code_hash);
