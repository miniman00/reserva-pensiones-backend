ALTER TABLE pension_inquiries
    ADD COLUMN closure_reason VARCHAR(30),
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD COLUMN converted_at TIMESTAMPTZ;

-- Las consultas cerradas antes de esta funcionalidad conservan su estado sin
-- inventar un resultado comercial que no conocemos.
UPDATE pension_inquiries
   SET closure_reason = 'OTHER',
       closed_at = COALESCE(updated_at, created_at)
 WHERE status = 'CLOSED'
   AND closure_reason IS NULL;

CREATE INDEX idx_pension_inquiries_converted_at
    ON pension_inquiries (converted_at)
    WHERE converted_at IS NOT NULL;
