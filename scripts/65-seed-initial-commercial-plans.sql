-- Reserva de Pensiones - catálogo comercial inicial
-- Requiere migraciones hasta V044 aplicadas.
--
-- IMPORTANTE:
--   * Este script reemplaza TODO el catálogo de planes/versions existente.
--   * Está pensado para bootstrap antes de tener historial comercial real.
--   * Por seguridad ABORTA si ya existen suscripciones, pagos de planes,
--     beneficiarios Founder o historiales de trial.
--   * Configura y ACTIVA la prueba gratuita de 90 días + 7 días de gracia.
--   * Si ya existen propietarios con pensiones PUBLISHED, inicia sus 90 días
--     desde el momento de ejecución, igual que la activación desde Backoffice.
--
-- Ejecutar solamente después de desplegar el backend con V043/V044 y los
-- cambios de trial, y preferentemente cuando Portal y Backoffice compatibles
-- también estén publicados.

BEGIN;

-- Evita destruir referencias comerciales/históricas reales.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM owner_subscriptions) THEN
        RAISE EXCEPTION
            'No se puede reinicializar el catálogo: existen owner_subscriptions. Usa versionado desde Backoffice en vez de borrar planes.';
    END IF;

    IF EXISTS (SELECT 1 FROM payments WHERE plan_version_id IS NOT NULL) THEN
        RAISE EXCEPTION
            'No se puede reinicializar el catálogo: existen pagos asociados a versiones de planes.';
    END IF;

    IF EXISTS (SELECT 1 FROM launch_campaign_beneficiaries) THEN
        RAISE EXCEPTION
            'No se puede reinicializar el catálogo: existen beneficiarios de campañas de lanzamiento.';
    END IF;

    IF EXISTS (SELECT 1 FROM owner_trial_lifecycles) THEN
        RAISE EXCEPTION
            'No se puede reinicializar el catálogo: existen historiales de prueba gratuita.';
    END IF;
END $$;

-- Desengancha referencias configurables antes de reemplazar versiones.
UPDATE owner_trial_settings
   SET enabled = FALSE,
       trial_plan_version_id = NULL,
       updated_at = CURRENT_TIMESTAMP
 WHERE id = 1;

UPDATE launch_campaigns
   SET benefit_plan_version_id = NULL,
       updated_at = CURRENT_TIMESTAMP
 WHERE code = 'PROPIETARIOS_FUNDADORES';

-- Reemplaza el catálogo actual.
DELETE FROM plan_version_period_prices;
DELETE FROM plan_versions;
DELETE FROM plans;

-- ---------------------------------------------------------------------------
-- PLANES
-- ---------------------------------------------------------------------------
INSERT INTO plans (code, name, description, active, created_at, updated_at)
VALUES
    (
        'FREE',
        'Prueba gratuita',
        'Base interna de la prueba gratuita única para nuevos propietarios. No disponible para compra.',
        TRUE,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'ESENCIAL',
        'Esencial',
        'Plan de entrada para propietarios con una sola pensión.',
        TRUE,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'PRO',
        'Pro',
        'Plan recomendado para propietarios con hasta 3 pensiones, visibilidad destacada y herramientas avanzadas.',
        TRUE,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'BUSINESS',
        'Business',
        'Plan para residencias, organizaciones y propietarios con múltiples pensiones.',
        TRUE,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    );

-- ---------------------------------------------------------------------------
-- VERSIONES v1 PUBLICADAS Y VIGENTES
-- ---------------------------------------------------------------------------
-- FREE / TRIAL: 1 pensión, 15 fotos, 1 video; sin premium.
INSERT INTO plan_versions (
    plan_id, version, monthly_price, currency,
    max_pensions, max_collaborators, max_photos, max_videos,
    featured_days, advanced_analytics, inquiry_history,
    consolidated_analytics, export_enabled,
    effective_from, effective_until, status, published_at, retired_at,
    created_at, updated_at
)
SELECT
    p.id, 1, 0.00, 'UYU',
    1, 0, 15, 1,
    0, FALSE, FALSE,
    FALSE, FALSE,
    CURRENT_TIMESTAMP, NULL, 'PUBLISHED', CURRENT_TIMESTAMP, NULL,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plans p
WHERE p.code = 'FREE';

-- ESENCIAL: 1 pensión, 15 fotos, 1 video.
INSERT INTO plan_versions (
    plan_id, version, monthly_price, currency,
    max_pensions, max_collaborators, max_photos, max_videos,
    featured_days, advanced_analytics, inquiry_history,
    consolidated_analytics, export_enabled,
    effective_from, effective_until, status, published_at, retired_at,
    created_at, updated_at
)
SELECT
    p.id, 1, 390.00, 'UYU',
    1, 0, 15, 1,
    0, FALSE, FALSE,
    FALSE, FALSE,
    CURRENT_TIMESTAMP, NULL, 'PUBLISHED', CURRENT_TIMESTAMP, NULL,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plans p
WHERE p.code = 'ESENCIAL';

-- PRO: plan recomendado, hasta 3 pensiones.
INSERT INTO plan_versions (
    plan_id, version, monthly_price, currency,
    max_pensions, max_collaborators, max_photos, max_videos,
    featured_days, advanced_analytics, inquiry_history,
    consolidated_analytics, export_enabled,
    effective_from, effective_until, status, published_at, retired_at,
    created_at, updated_at
)
SELECT
    p.id, 1, 590.00, 'UYU',
    3, 3, 20, 2,
    10, TRUE, TRUE,
    TRUE, TRUE,
    CURRENT_TIMESTAMP, NULL, 'PUBLISHED', CURRENT_TIMESTAMP, NULL,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plans p
WHERE p.code = 'PRO';

-- BUSINESS: hasta 10 pensiones.
INSERT INTO plan_versions (
    plan_id, version, monthly_price, currency,
    max_pensions, max_collaborators, max_photos, max_videos,
    featured_days, advanced_analytics, inquiry_history,
    consolidated_analytics, export_enabled,
    effective_from, effective_until, status, published_at, retired_at,
    created_at, updated_at
)
SELECT
    p.id, 1, 1290.00, 'UYU',
    10, 10, 30, 5,
    20, TRUE, TRUE,
    TRUE, TRUE,
    CURRENT_TIMESTAMP, NULL, 'PUBLISHED', CURRENT_TIMESTAMP, NULL,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plans p
WHERE p.code = 'BUSINESS';

-- ---------------------------------------------------------------------------
-- PRECIOS POR PERÍODO
-- ---------------------------------------------------------------------------
-- FREE no se compra: dejamos las cuatro opciones explícitas pero deshabilitadas.
INSERT INTO plan_version_period_prices (plan_version_id, period_months, total_price, enabled)
SELECT pv.id, x.period_months, x.total_price, FALSE
FROM plan_versions pv
JOIN plans p ON p.id = pv.plan_id
CROSS JOIN (
    VALUES
        (1,  0.00::NUMERIC(12,2)),
        (3,  0.00::NUMERIC(12,2)),
        (6,  0.00::NUMERIC(12,2)),
        (12, 0.00::NUMERIC(12,2))
) AS x(period_months, total_price)
WHERE p.code = 'FREE' AND pv.version = 1;

-- ESENCIAL
INSERT INTO plan_version_period_prices (plan_version_id, period_months, total_price, enabled)
SELECT pv.id, x.period_months, x.total_price, TRUE
FROM plan_versions pv
JOIN plans p ON p.id = pv.plan_id
CROSS JOIN (
    VALUES
        (1,   390.00::NUMERIC(12,2)),
        (3,  1050.00::NUMERIC(12,2)),
        (6,  1890.00::NUMERIC(12,2)),
        (12, 3290.00::NUMERIC(12,2))
) AS x(period_months, total_price)
WHERE p.code = 'ESENCIAL' AND pv.version = 1;

-- PRO
INSERT INTO plan_version_period_prices (plan_version_id, period_months, total_price, enabled)
SELECT pv.id, x.period_months, x.total_price, TRUE
FROM plan_versions pv
JOIN plans p ON p.id = pv.plan_id
CROSS JOIN (
    VALUES
        (1,   590.00::NUMERIC(12,2)),
        (3,  1590.00::NUMERIC(12,2)),
        (6,  2890.00::NUMERIC(12,2)),
        (12, 4990.00::NUMERIC(12,2))
) AS x(period_months, total_price)
WHERE p.code = 'PRO' AND pv.version = 1;

-- BUSINESS
INSERT INTO plan_version_period_prices (plan_version_id, period_months, total_price, enabled)
SELECT pv.id, x.period_months, x.total_price, TRUE
FROM plan_versions pv
JOIN plans p ON p.id = pv.plan_id
CROSS JOIN (
    VALUES
        (1,    1290.00::NUMERIC(12,2)),
        (3,    3490.00::NUMERIC(12,2)),
        (6,    6290.00::NUMERIC(12,2)),
        (12,  10990.00::NUMERIC(12,2))
) AS x(period_months, total_price)
WHERE p.code = 'BUSINESS' AND pv.version = 1;

-- ---------------------------------------------------------------------------
-- PRUEBA GRATUITA: única, 90 días + 7 días de gracia.
-- ---------------------------------------------------------------------------
INSERT INTO owner_trial_settings (
    id, enabled, duration_days, grace_days, trial_plan_version_id,
    created_at, updated_at
)
SELECT
    1, TRUE, 90, 7, pv.id,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plan_versions pv
JOIN plans p ON p.id = pv.plan_id
WHERE p.code = 'FREE' AND pv.version = 1
ON CONFLICT (id) DO UPDATE
SET enabled = EXCLUDED.enabled,
    duration_days = EXCLUDED.duration_days,
    grace_days = EXCLUDED.grace_days,
    trial_plan_version_id = EXCLUDED.trial_plan_version_id,
    updated_at = CURRENT_TIMESTAMP;

-- Si ya hay propietarios con una pensión publicada, arranca su trial ahora.
-- Esta inserción reproduce el objetivo del backfill al activar la política desde Backoffice.
WITH free_version AS (
    SELECT pv.id AS plan_version_id
    FROM plan_versions pv
    JOIN plans p ON p.id = pv.plan_id
    WHERE p.code = 'FREE' AND pv.version = 1
), first_published AS (
    SELECT DISTINCT ON (COALESCE(p.owner_id, p.created_by_id))
           COALESCE(p.owner_id, p.created_by_id) AS user_id,
           p.id AS pension_id
    FROM pensions p
    WHERE p.status = 'PUBLISHED'
    ORDER BY COALESCE(p.owner_id, p.created_by_id), p.id
)
INSERT INTO owner_trial_lifecycles (
    user_id,
    consumption_reason,
    source_pension_id,
    trial_plan_version_id,
    consumed_at,
    trial_started_at,
    trial_expires_at,
    grace_expires_at,
    duration_days_snapshot,
    grace_days_snapshot,
    created_at,
    updated_at
)
SELECT
    fp.user_id,
    'TRIAL_STARTED',
    fp.pension_id,
    fv.plan_version_id,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP + INTERVAL '90 days',
    CURRENT_TIMESTAMP + INTERVAL '97 days',
    90,
    7,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM first_published fp
CROSS JOIN free_version fv
ON CONFLICT (user_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- CAMPAÑA FUNDADORES: conserva su estado actual, pero usa PRO v1 como beneficio.
-- ---------------------------------------------------------------------------
UPDATE launch_campaigns c
   SET max_beneficiaries = 20,
       benefit_duration_days = 365,
       max_featured_pensions = 3,
       benefit_plan_version_id = (
           SELECT pv.id
           FROM plan_versions pv
           JOIN plans p ON p.id = pv.plan_id
           WHERE p.code = 'PRO' AND pv.version = 1
       ),
       updated_at = CURRENT_TIMESTAMP
 WHERE c.code = 'PROPIETARIOS_FUNDADORES';

COMMIT;

-- ---------------------------------------------------------------------------
-- VERIFICACIÓN
-- ---------------------------------------------------------------------------
SELECT
    p.code,
    p.name,
    p.active,
    pv.version,
    pv.status,
    pv.monthly_price,
    pv.currency,
    pv.max_pensions,
    pv.max_collaborators,
    pv.max_photos,
    pv.max_videos,
    pv.featured_days,
    pv.advanced_analytics,
    pv.inquiry_history,
    pv.consolidated_analytics,
    pv.export_enabled
FROM plans p
JOIN plan_versions pv ON pv.plan_id = p.id
ORDER BY CASE p.code
    WHEN 'FREE' THEN 1
    WHEN 'ESENCIAL' THEN 2
    WHEN 'PRO' THEN 3
    WHEN 'BUSINESS' THEN 4
    ELSE 99
END, pv.version;

SELECT
    p.code,
    pp.period_months,
    pp.total_price,
    pp.enabled
FROM plan_version_period_prices pp
JOIN plan_versions pv ON pv.id = pp.plan_version_id
JOIN plans p ON p.id = pv.plan_id
ORDER BY CASE p.code
    WHEN 'FREE' THEN 1
    WHEN 'ESENCIAL' THEN 2
    WHEN 'PRO' THEN 3
    WHEN 'BUSINESS' THEN 4
    ELSE 99
END, pp.period_months;

SELECT
    ots.enabled,
    ots.duration_days,
    ots.grace_days,
    p.code AS trial_plan,
    pv.version AS trial_plan_version
FROM owner_trial_settings ots
LEFT JOIN plan_versions pv ON pv.id = ots.trial_plan_version_id
LEFT JOIN plans p ON p.id = pv.plan_id
WHERE ots.id = 1;

SELECT
    code,
    status,
    max_beneficiaries,
    benefit_duration_days,
    max_featured_pensions,
    benefit_plan_version_id
FROM launch_campaigns
WHERE code = 'PROPIETARIOS_FUNDADORES';
