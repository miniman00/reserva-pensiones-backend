-- Mercado Pago: credenciales independientes para SANDBOX y LIVE.
-- Las credenciales históricas se migran al ambiente que estaba activo al momento del deploy.
-- No se duplican hacia ambos ambientes para evitar usar accidentalmente un secreto de pruebas en LIVE.
INSERT INTO payment_provider_credentials (
    provider, credential_name, encrypted_value, fingerprint, masked_suffix,
    updated_by_backoffice_user_id, created_at, updated_at
)
SELECT
    legacy.provider,
    CASE WHEN cfg.mode = 'LIVE' THEN 'LIVE_ACCESS_TOKEN' ELSE 'SANDBOX_ACCESS_TOKEN' END,
    legacy.encrypted_value, legacy.fingerprint, legacy.masked_suffix,
    legacy.updated_by_backoffice_user_id, legacy.created_at, legacy.updated_at
FROM payment_provider_credentials legacy
JOIN payment_provider_configs cfg ON cfg.provider = legacy.provider
WHERE legacy.provider = 'MERCADO_PAGO'
  AND legacy.credential_name = 'ACCESS_TOKEN'
ON CONFLICT (provider, credential_name) DO NOTHING;

INSERT INTO payment_provider_credentials (
    provider, credential_name, encrypted_value, fingerprint, masked_suffix,
    updated_by_backoffice_user_id, created_at, updated_at
)
SELECT
    legacy.provider,
    CASE WHEN cfg.mode = 'LIVE' THEN 'LIVE_WEBHOOK_SECRET' ELSE 'SANDBOX_WEBHOOK_SECRET' END,
    legacy.encrypted_value, legacy.fingerprint, legacy.masked_suffix,
    legacy.updated_by_backoffice_user_id, legacy.created_at, legacy.updated_at
FROM payment_provider_credentials legacy
JOIN payment_provider_configs cfg ON cfg.provider = legacy.provider
WHERE legacy.provider = 'MERCADO_PAGO'
  AND legacy.credential_name = 'WEBHOOK_SECRET'
ON CONFLICT (provider, credential_name) DO NOTHING;

DELETE FROM payment_provider_credentials
WHERE provider = 'MERCADO_PAGO'
  AND credential_name IN ('ACCESS_TOKEN', 'WEBHOOK_SECRET');
