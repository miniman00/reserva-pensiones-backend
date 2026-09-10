CREATE TABLE IF NOT EXISTS payment_provider_environment_checks (
    provider VARCHAR(40) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    credential_fingerprint VARCHAR(64),
    checked_at TIMESTAMPTZ NOT NULL,
    success BOOLEAN NOT NULL,
    message VARCHAR(500),
    checked_by_backoffice_user_id BIGINT REFERENCES backoffice_users(id),
    PRIMARY KEY (provider, mode)
);

CREATE INDEX IF NOT EXISTS idx_payment_provider_environment_checks_checked_at
    ON payment_provider_environment_checks (checked_at DESC);
