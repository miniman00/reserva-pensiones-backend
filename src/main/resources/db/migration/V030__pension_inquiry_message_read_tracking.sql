ALTER TABLE pension_inquiry_messages
    ADD COLUMN recipient_user_id BIGINT;

ALTER TABLE pension_inquiry_messages
    ADD COLUMN read_at TIMESTAMPTZ;

ALTER TABLE pension_inquiry_messages
    ADD CONSTRAINT fk_pension_inquiry_messages_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users(id) ON DELETE SET NULL;

-- Los mensajes anteriores a esta funcionalidad no deben aparecer como no leídos
-- al desplegar. Los mensajes nuevos dejan read_at en NULL hasta que el destinatario
-- abra la conversación.
UPDATE pension_inquiry_messages
   SET read_at = created_at
 WHERE read_at IS NULL;

CREATE INDEX idx_pension_inquiry_messages_recipient_unread
    ON pension_inquiry_messages (recipient_user_id, inquiry_id)
    WHERE read_at IS NULL;
