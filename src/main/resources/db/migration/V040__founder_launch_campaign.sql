-- Campaña de lanzamiento para captar los primeros propietarios con oferta real publicada.
-- Se crea pausada por seguridad: Backoffice deberá completar/configurar el beneficio y activarla al lanzar.
CREATE TABLE IF NOT EXISTS launch_campaigns (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(140) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PAUSED',
    max_beneficiaries INTEGER NOT NULL,
    granted_count INTEGER NOT NULL DEFAULT 0,
    benefit_duration_days INTEGER NOT NULL,
    max_featured_pensions INTEGER NOT NULL DEFAULT 0,
    benefit_plan_version_id BIGINT,
    enrollment_starts_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enrollment_ends_at TIMESTAMPTZ,
    show_remaining_slots BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_launch_campaigns_code UNIQUE (code),
    CONSTRAINT ck_launch_campaigns_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    CONSTRAINT ck_launch_campaigns_max_beneficiaries CHECK (max_beneficiaries > 0),
    CONSTRAINT ck_launch_campaigns_granted_count CHECK (granted_count >= 0 AND granted_count <= max_beneficiaries),
    CONSTRAINT ck_launch_campaigns_duration CHECK (benefit_duration_days > 0),
    CONSTRAINT ck_launch_campaigns_max_featured CHECK (max_featured_pensions >= 0),
    CONSTRAINT ck_launch_campaigns_enrollment_dates CHECK (enrollment_ends_at IS NULL OR enrollment_ends_at > enrollment_starts_at),
    CONSTRAINT fk_launch_campaigns_plan_version
        FOREIGN KEY (benefit_plan_version_id) REFERENCES plan_versions(id)
);

CREATE TABLE IF NOT EXISTS launch_campaign_beneficiaries (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source_pension_id BIGINT NOT NULL,
    granted_order INTEGER NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    granted_by_backoffice_user_id BIGINT,
    grant_reason VARCHAR(1000),
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_launch_campaign_beneficiaries_campaign
        FOREIGN KEY (campaign_id) REFERENCES launch_campaigns(id),
    CONSTRAINT fk_launch_campaign_beneficiaries_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_launch_campaign_beneficiaries_pension
        FOREIGN KEY (source_pension_id) REFERENCES pensions(id),
    CONSTRAINT fk_launch_campaign_beneficiaries_actor
        FOREIGN KEY (granted_by_backoffice_user_id) REFERENCES backoffice_users(id),
    CONSTRAINT uq_launch_campaign_beneficiary_user UNIQUE (campaign_id, user_id),
    CONSTRAINT uq_launch_campaign_beneficiary_order UNIQUE (campaign_id, granted_order),
    CONSTRAINT ck_launch_campaign_beneficiary_order CHECK (granted_order > 0),
    CONSTRAINT ck_launch_campaign_beneficiary_dates CHECK (expires_at > granted_at),
    CONSTRAINT ck_launch_campaign_beneficiary_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_launch_campaign_beneficiary_revoked CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL) OR status <> 'REVOKED'
    )
);

CREATE INDEX IF NOT EXISTS idx_launch_campaign_beneficiaries_user
    ON launch_campaign_beneficiaries (user_id, granted_at DESC);
CREATE INDEX IF NOT EXISTS idx_launch_campaign_beneficiaries_expiration
    ON launch_campaign_beneficiaries (status, expires_at);

-- Auditoría propia de campaña. Admite eventos automáticos sin inventar un usuario interno.
CREATE TABLE IF NOT EXISTS launch_campaign_events (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    beneficiary_id BIGINT,
    user_id BIGINT,
    pension_id BIGINT,
    backoffice_user_id BIGINT,
    event_type VARCHAR(30) NOT NULL,
    reason VARCHAR(1500),
    before_json TEXT,
    after_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_launch_campaign_events_campaign
        FOREIGN KEY (campaign_id) REFERENCES launch_campaigns(id),
    CONSTRAINT fk_launch_campaign_events_beneficiary
        FOREIGN KEY (beneficiary_id) REFERENCES launch_campaign_beneficiaries(id),
    CONSTRAINT fk_launch_campaign_events_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_launch_campaign_events_pension
        FOREIGN KEY (pension_id) REFERENCES pensions(id),
    CONSTRAINT fk_launch_campaign_events_actor
        FOREIGN KEY (backoffice_user_id) REFERENCES backoffice_users(id),
    CONSTRAINT ck_launch_campaign_events_type CHECK (
        event_type IN ('AUTO_GRANTED', 'MANUAL_GRANTED', 'REVOKED', 'EXTENDED', 'CONFIG_UPDATED', 'STATUS_CHANGED')
    )
);

CREATE INDEX IF NOT EXISTS idx_launch_campaign_events_campaign_created
    ON launch_campaign_events (campaign_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_launch_campaign_events_beneficiary_created
    ON launch_campaign_events (beneficiary_id, created_at DESC);

INSERT INTO launch_campaigns (
    code, name, status, max_beneficiaries, granted_count, benefit_duration_days,
    max_featured_pensions, enrollment_starts_at, show_remaining_slots
)
VALUES (
    'PROPIETARIOS_FUNDADORES', 'Propietarios Fundadores', 'PAUSED', 20, 0, 365,
    3, CURRENT_TIMESTAMP, FALSE
)
ON CONFLICT (code) DO NOTHING;
