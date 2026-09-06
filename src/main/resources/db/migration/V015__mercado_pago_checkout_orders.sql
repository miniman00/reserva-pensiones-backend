-- Checkout Pro (Orders API): conserva el ID de order y la URL de checkout separados del payment ID.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS provider_checkout_id VARCHAR(190),
    ADD COLUMN IF NOT EXISTS checkout_url TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_provider_checkout
    ON payments (provider, provider_checkout_id)
    WHERE provider_checkout_id IS NOT NULL;
