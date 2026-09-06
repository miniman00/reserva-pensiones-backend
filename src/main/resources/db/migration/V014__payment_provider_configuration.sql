-- Configuración operativa de pagos administrable desde Backoffice.
-- APP_PAYMENTS_ALLOWED queda como fusible de infraestructura y nunca puede ser activado desde BD.
CREATE TABLE IF NOT EXISTS payment_settings (
    id SMALLINT PRIMARY KEY,
    payments_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_provider VARCHAR(40),
    updated_by_backoffice_user_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_payment_settings_singleton CHECK (id = 1),
    CONSTRAINT fk_payment_settings_updated_by FOREIGN KEY (updated_by_backoffice_user_id) REFERENCES backoffice_users(id)
);
INSERT INTO payment_settings (id, payments_enabled) VALUES (1, FALSE)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS payment_provider_configs (
    provider VARCHAR(40) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(20) NOT NULL DEFAULT 'TEST',
    priority INTEGER NOT NULL DEFAULT 100,
    subscriptions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    promotions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    supported_currencies VARCHAR(500),
    supported_countries VARCHAR(1000),
    configuration_json TEXT,
    last_connectivity_check_at TIMESTAMPTZ,
    last_connectivity_check_success BOOLEAN,
    last_connectivity_check_message VARCHAR(500),
    last_webhook_at TIMESTAMPTZ,
    updated_by_backoffice_user_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_payment_provider_priority CHECK (priority BETWEEN 1 AND 10000),
    CONSTRAINT fk_payment_provider_updated_by FOREIGN KEY (updated_by_backoffice_user_id) REFERENCES backoffice_users(id)
);

INSERT INTO payment_provider_configs (provider, display_name, enabled, mode, priority, subscriptions_enabled, promotions_enabled)
VALUES
    ('MOCK', 'Mock (pruebas)', FALSE, 'TEST', 1000, TRUE, TRUE),
    ('MERCADO_PAGO', 'Mercado Pago', FALSE, 'SANDBOX', 10, TRUE, TRUE),
    ('STRIPE', 'Stripe', FALSE, 'SANDBOX', 20, TRUE, TRUE),
    ('DLOCAL', 'dLocal', FALSE, 'SANDBOX', 30, TRUE, TRUE),
    ('PAYPAL', 'PayPal', FALSE, 'SANDBOX', 40, TRUE, TRUE)
ON CONFLICT (provider) DO NOTHING;

ALTER TABLE payment_settings
    ADD CONSTRAINT fk_payment_settings_default_provider FOREIGN KEY (default_provider) REFERENCES payment_provider_configs(provider);

CREATE TABLE IF NOT EXISTS payment_provider_credentials (
    provider VARCHAR(40) NOT NULL,
    credential_name VARCHAR(80) NOT NULL,
    encrypted_value TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    masked_suffix VARCHAR(12),
    updated_by_backoffice_user_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider, credential_name),
    CONSTRAINT fk_payment_provider_credentials_provider FOREIGN KEY (provider) REFERENCES payment_provider_configs(provider) ON DELETE CASCADE,
    CONSTRAINT fk_payment_provider_credentials_updated_by FOREIGN KEY (updated_by_backoffice_user_id) REFERENCES backoffice_users(id)
);
CREATE INDEX IF NOT EXISTS idx_payment_provider_configs_enabled_priority
    ON payment_provider_configs (enabled, priority, provider);
