CREATE TABLE IF NOT EXISTS owner_trial_settings (
    id SMALLINT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    duration_days INTEGER NOT NULL DEFAULT 90,
    grace_days INTEGER NOT NULL DEFAULT 7,
    trial_plan_version_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_owner_trial_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_owner_trial_settings_duration CHECK (duration_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_owner_trial_settings_grace CHECK (grace_days BETWEEN 0 AND 365),
    CONSTRAINT fk_owner_trial_settings_plan_version
        FOREIGN KEY (trial_plan_version_id) REFERENCES plan_versions(id)
);

INSERT INTO owner_trial_settings (id, enabled, duration_days, grace_days)
VALUES (1, FALSE, 90, 7)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS owner_trial_lifecycles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    consumption_reason VARCHAR(30) NOT NULL,
    source_pension_id BIGINT,
    trial_plan_version_id BIGINT,
    consumed_at TIMESTAMPTZ NOT NULL,
    trial_started_at TIMESTAMPTZ,
    trial_expires_at TIMESTAMPTZ,
    grace_expires_at TIMESTAMPTZ,
    converted_at TIMESTAMPTZ,
    duration_days_snapshot INTEGER,
    grace_days_snapshot INTEGER NOT NULL DEFAULT 7,
    access_suspended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_owner_trial_lifecycles_user UNIQUE (user_id),
    CONSTRAINT fk_owner_trial_lifecycles_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_owner_trial_lifecycles_source_pension FOREIGN KEY (source_pension_id) REFERENCES pensions(id),
    CONSTRAINT fk_owner_trial_lifecycles_plan_version FOREIGN KEY (trial_plan_version_id) REFERENCES plan_versions(id),
    CONSTRAINT ck_owner_trial_lifecycles_reason CHECK (
        consumption_reason IN ('TRIAL_STARTED', 'FOUNDER_GRANTED', 'PAID_DIRECT')
    ),
    CONSTRAINT ck_owner_trial_lifecycles_grace_snapshot CHECK (grace_days_snapshot BETWEEN 0 AND 365),
    CONSTRAINT ck_owner_trial_lifecycles_duration_snapshot CHECK (
        duration_days_snapshot IS NULL OR duration_days_snapshot BETWEEN 1 AND 3650
    ),
    CONSTRAINT ck_owner_trial_lifecycles_trial_dates CHECK (
        (consumption_reason = 'TRIAL_STARTED'
            AND trial_plan_version_id IS NOT NULL
            AND duration_days_snapshot IS NOT NULL
            AND trial_started_at IS NOT NULL
            AND trial_expires_at IS NOT NULL
            AND grace_expires_at IS NOT NULL
            AND trial_expires_at > trial_started_at
            AND grace_expires_at >= trial_expires_at)
        OR consumption_reason <> 'TRIAL_STARTED'
    )
);

CREATE INDEX IF NOT EXISTS idx_owner_trial_lifecycles_expiration
    ON owner_trial_lifecycles (grace_expires_at, access_suspended_at)
    WHERE consumption_reason = 'TRIAL_STARTED';

CREATE INDEX IF NOT EXISTS idx_owner_trial_lifecycles_reason
    ON owner_trial_lifecycles (consumption_reason, consumed_at DESC);

ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS commercial_pause_reason VARCHAR(40),
    ADD COLUMN IF NOT EXISTS commercial_paused_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_pensions_commercial_pause
    ON pensions (commercial_pause_reason, commercial_paused_at)
    WHERE status = 'PAUSED' AND commercial_pause_reason IS NOT NULL;

-- Existing founders have already consumed their one-time free-entry opportunity.
INSERT INTO owner_trial_lifecycles (
    user_id, consumption_reason, source_pension_id, consumed_at, grace_days_snapshot
)
SELECT b.user_id, 'FOUNDER_GRANTED', b.source_pension_id, b.granted_at, 7
FROM launch_campaign_beneficiaries b
WHERE NOT EXISTS (
    SELECT 1 FROM owner_trial_lifecycles l WHERE l.user_id = b.user_id
)
ON CONFLICT (user_id) DO NOTHING;

-- A user who has already paid should never later receive a first-use trial.
INSERT INTO owner_trial_lifecycles (
    user_id, consumption_reason, consumed_at, grace_days_snapshot
)
SELECT s.user_id, 'PAID_DIRECT', MIN(s.started_at), 7
FROM owner_subscriptions s
WHERE s.source = 'PAYMENT'
  AND NOT EXISTS (
      SELECT 1 FROM owner_trial_lifecycles l WHERE l.user_id = s.user_id
  )
GROUP BY s.user_id
ON CONFLICT (user_id) DO NOTHING;
