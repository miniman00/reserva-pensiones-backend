-- Bloque 14: canales de contacto público por pensión
-- Ejecutar antes de desplegar el backend 14 en ambientes con ddl-auto=validate.
-- Los flags nacen desactivados para no exponer datos existentes sin consentimiento.

ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS contact_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(30),
    ADD COLUMN IF NOT EXISTS contact_whatsapp VARCHAR(30),
    ADD COLUMN IF NOT EXISTS show_phone BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS show_whatsapp BOOLEAN NOT NULL DEFAULT FALSE;

-- Precarga privada para facilitar la edición de anuncios existentes.
-- Nada se hace público porque show_phone/show_whatsapp permanecen en FALSE.
UPDATE pensions p
SET contact_name = COALESCE(p.contact_name, u.name),
    contact_phone = COALESCE(p.contact_phone, u.phone),
    contact_whatsapp = COALESCE(p.contact_whatsapp, u.phone)
FROM users u
WHERE p.owner_id = u.id;
