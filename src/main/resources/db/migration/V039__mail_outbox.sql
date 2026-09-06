CREATE TABLE IF NOT EXISTS mail_outbox (
    id BIGSERIAL PRIMARY KEY,
    recipient VARCHAR(320) NOT NULL,
    reply_to VARCHAR(320),
    subject VARCHAR(300) NOT NULL,
    html_body TEXT NOT NULL,
    category VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner VARCHAR(80),
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_mail_outbox_status CHECK (status IN ('PENDING', 'DEAD'))
);

CREATE INDEX IF NOT EXISTS idx_mail_outbox_pending_delivery
    ON mail_outbox (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_mail_outbox_lease
    ON mail_outbox (lease_owner)
    WHERE lease_owner IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mail_outbox_dead_created
    ON mail_outbox (created_at)
    WHERE status = 'DEAD';
