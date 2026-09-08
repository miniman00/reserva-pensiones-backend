-- Un reembolso total confirmado invalida automáticamente el beneficio concedido por ese pago.
-- Esta migración corrige datos históricos creados antes de que la regla fuera automática.
UPDATE owner_subscriptions s
SET status = 'CANCELLED',
    cancelled_at = COALESCE(s.cancelled_at, p.refunded_at, CURRENT_TIMESTAMP),
    cancellation_reason = LEFT('Cancelada automáticamente por reembolso total del pago #' || p.id, 500),
    updated_at = CURRENT_TIMESTAMP
FROM payments p
WHERE p.status = 'REFUNDED'
  AND p.owner_subscription_id = s.id
  AND s.status <> 'CANCELLED';

-- Revoca también destacados consumidos como beneficio de la suscripción reembolsada.
UPDATE pension_promotions pp
SET status = 'CANCELLED',
    cancelled_at = COALESCE(pp.cancelled_at, p.refunded_at, CURRENT_TIMESTAMP),
    cancellation_reason = LEFT('Cancelada automáticamente por reembolso total del pago #' || p.id, 1500),
    updated_at = CURRENT_TIMESTAMP
FROM subscription_featured_day_usage u
JOIN payments p ON p.owner_subscription_id = u.subscription_id
WHERE p.status = 'REFUNDED'
  AND u.promotion_id = pp.id
  AND pp.status <> 'CANCELLED';

-- Revoca promociones compradas directamente cuando su pago fue reembolsado por completo.
UPDATE pension_promotions pp
SET status = 'CANCELLED',
    cancelled_at = COALESCE(pp.cancelled_at, p.refunded_at, CURRENT_TIMESTAMP),
    cancellation_reason = LEFT('Cancelada automáticamente por reembolso total del pago #' || p.id, 1500),
    updated_at = CURRENT_TIMESTAMP
FROM payments p
WHERE p.status = 'REFUNDED'
  AND p.pension_promotion_id = pp.id
  AND pp.status <> 'CANCELLED';

UPDATE payments p
SET refund_benefit_decision = 'REVOKE_BENEFIT',
    refund_benefit_decided_at = COALESCE(p.refund_benefit_decided_at, p.refunded_at, CURRENT_TIMESTAMP),
    refund_benefit_decided_by_backoffice_user_id = NULL,
    refund_benefit_reason = 'Revocación automática por reembolso total confirmado por el proveedor',
    fulfillment_error_code = NULL,
    fulfillment_error_message = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE p.status = 'REFUNDED'
  AND p.fulfilled_at IS NOT NULL
  AND (p.owner_subscription_id IS NOT NULL OR p.pension_promotion_id IS NOT NULL);
