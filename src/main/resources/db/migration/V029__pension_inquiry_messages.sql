CREATE TABLE IF NOT EXISTS pension_inquiry_messages (
    id BIGSERIAL PRIMARY KEY,
    inquiry_id BIGINT NOT NULL,
    sender_user_id BIGINT,
    sender_role VARCHAR(20) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pension_inquiry_messages_inquiry
        FOREIGN KEY (inquiry_id) REFERENCES pension_inquiries(id) ON DELETE CASCADE,
    CONSTRAINT fk_pension_inquiry_messages_sender
        FOREIGN KEY (sender_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_pension_inquiry_messages_inquiry_created
    ON pension_inquiry_messages (inquiry_id, created_at, id);
