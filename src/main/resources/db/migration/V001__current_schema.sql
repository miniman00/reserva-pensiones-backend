-- Baseline versionado del esquema actual (bloques 01-22).
-- Diseñado para funcionar tanto en una base vacía como en una base existente
-- creada previamente por Hibernate ddl-auto=update o por los scripts manuales.
--
-- En una base existente sin flyway_schema_history, Flyway crea baseline=0 y
-- ejecuta esta V001. Todos los cambios aquí son deliberadamente idempotentes.

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(190) NOT NULL,
    name VARCHAR(255),
    phone VARCHAR(30),
    country_code VARCHAR(2),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    provider VARCHAR(40),
    provider_id VARCHAR(255),
    role VARCHAR(20) NOT NULL DEFAULT 'SEEKER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terms_accepted_version VARCHAR(20),
    privacy_accepted_version VARCHAR(20),
    legal_accepted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email VARCHAR(190),
    ADD COLUMN IF NOT EXISTS name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(30),
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2),
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS provider VARCHAR(40),
    ADD COLUMN IF NOT EXISTS provider_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'SEEKER',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS terms_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS privacy_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS legal_accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email ON users (email);

CREATE TABLE IF NOT EXISTS organizations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(140) NOT NULL,
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS name VARCHAR(140),
    ADD COLUMN IF NOT EXISTS owner_id BIGINT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS memberships (
    id BIGSERIAL PRIMARY KEY,
    org_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE memberships
    ADD COLUMN IF NOT EXISTS org_id BIGINT,
    ADD COLUMN IF NOT EXISTS user_id BIGINT,
    ADD COLUMN IF NOT EXISTS role VARCHAR(20),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uk_memberships_org_user ON memberships (org_id, user_id);

CREATE TABLE IF NOT EXISTS org_invites (
    id BIGSERIAL PRIMARY KEY,
    org_id BIGINT NOT NULL,
    email VARCHAR(190) NOT NULL,
    role VARCHAR(20) NOT NULL,
    token_hash VARCHAR(200) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(200) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS pensions (
    id BIGSERIAL PRIMARY KEY,
    org_id BIGINT NOT NULL,
    created_by_id BIGINT NOT NULL,
    owner_id BIGINT,
    name VARCHAR(140) NOT NULL,
    description VARCHAR(2000),
    country_code VARCHAR(2) NOT NULL,
    address_line1 VARCHAR(200),
    city VARCHAR(100),
    state VARCHAR(100),
    postal_code VARCHAR(20),
    neighborhood VARCHAR(120),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    moderation_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    lat DOUBLE PRECISION,
    lng DOUBLE PRECISION,
    capacity_simple INTEGER,
    capacity_matrimonial INTEGER,
    available_simple INTEGER,
    available_matrimonial INTEGER,
    price_simple NUMERIC(38,2),
    price_matrimonial NUMERIC(38,2),
    availability_updated_at TIMESTAMPTZ,
    admission_type VARCHAR(20),
    resident_profile VARCHAR(24),
    bathrooms_count INTEGER,
    bathroom_type VARCHAR(20),
    has_parking BOOLEAN,
    contact_name VARCHAR(120),
    contact_phone VARCHAR(30),
    contact_whatsapp VARCHAR(30),
    show_phone BOOLEAN NOT NULL DEFAULT FALSE,
    show_whatsapp BOOLEAN NOT NULL DEFAULT FALSE,
    featured_image VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE pensions
    ADD COLUMN IF NOT EXISTS org_id BIGINT,
    ADD COLUMN IF NOT EXISTS created_by_id BIGINT,
    ADD COLUMN IF NOT EXISTS owner_id BIGINT,
    ADD COLUMN IF NOT EXISTS name VARCHAR(140),
    ADD COLUMN IF NOT EXISTS description VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2),
    ADD COLUMN IF NOT EXISTS address_line1 VARCHAR(200),
    ADD COLUMN IF NOT EXISTS city VARCHAR(100),
    ADD COLUMN IF NOT EXISTS state VARCHAR(100),
    ADD COLUMN IF NOT EXISTS postal_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS neighborhood VARCHAR(120),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS moderation_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS lat DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS lng DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS capacity_simple INTEGER,
    ADD COLUMN IF NOT EXISTS capacity_matrimonial INTEGER,
    ADD COLUMN IF NOT EXISTS available_simple INTEGER,
    ADD COLUMN IF NOT EXISTS available_matrimonial INTEGER,
    ADD COLUMN IF NOT EXISTS price_simple NUMERIC(38,2),
    ADD COLUMN IF NOT EXISTS price_matrimonial NUMERIC(38,2),
    ADD COLUMN IF NOT EXISTS availability_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS admission_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS resident_profile VARCHAR(24),
    ADD COLUMN IF NOT EXISTS bathrooms_count INTEGER,
    ADD COLUMN IF NOT EXISTS bathroom_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS has_parking BOOLEAN,
    ADD COLUMN IF NOT EXISTS contact_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(30),
    ADD COLUMN IF NOT EXISTS contact_whatsapp VARCHAR(30),
    ADD COLUMN IF NOT EXISTS show_phone BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS show_whatsapp BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS featured_image VARCHAR(255),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE pensions SET status = 'DRAFT' WHERE status IS NULL;
UPDATE pensions SET moderation_blocked = FALSE WHERE moderation_blocked IS NULL;
UPDATE pensions SET show_phone = FALSE WHERE show_phone IS NULL;
UPDATE pensions SET show_whatsapp = FALSE WHERE show_whatsapp IS NULL;

CREATE INDEX IF NOT EXISTS idx_pensions_status_updated ON pensions (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_pensions_owner ON pensions (owner_id);
CREATE INDEX IF NOT EXISTS idx_pensions_org ON pensions (org_id);
CREATE INDEX IF NOT EXISTS idx_pensions_city ON pensions (city);
CREATE INDEX IF NOT EXISTS idx_pensions_lat_lng ON pensions (lat, lng);

CREATE TABLE IF NOT EXISTS pension_amenities (
    pension_id BIGINT NOT NULL,
    amenity VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pension_amenities_pension ON pension_amenities (pension_id);
CREATE INDEX IF NOT EXISTS idx_pension_amenities_value ON pension_amenities (amenity);

CREATE TABLE IF NOT EXISTS pension_nearby_tags (
    pension_id BIGINT NOT NULL,
    tag VARCHAR(80) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pension_nearby_tags_pension ON pension_nearby_tags (pension_id);

CREATE TABLE IF NOT EXISTS pension_study_centers (
    pension_id BIGINT NOT NULL,
    study_center VARCHAR(140) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pension_study_centers_pair
    ON pension_study_centers (pension_id, study_center);
CREATE INDEX IF NOT EXISTS idx_pension_study_centers_lower_name
    ON pension_study_centers (LOWER(study_center));

CREATE TABLE IF NOT EXISTS pension_media (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    filename VARCHAR(255),
    url VARCHAR(2048) NOT NULL,
    mime_type VARCHAR(120),
    size_bytes BIGINT,
    width INTEGER,
    height INTEGER,
    duration_sec INTEGER,
    sort_order INTEGER NOT NULL DEFAULT 0,
    cover BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pension_media_pension ON pension_media (pension_id);

CREATE TABLE IF NOT EXISTS pension_members (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(255) NOT NULL DEFAULT 'AVAIL_ONLY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pension_members_pension_user
    ON pension_members (pension_id, user_id);

CREATE TABLE IF NOT EXISTS pension_invites (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    email VARCHAR(320) NOT NULL,
    role VARCHAR(255) NOT NULL DEFAULT 'AVAIL_ONLY',
    token VARCHAR(64) NOT NULL,
    status VARCHAR(255) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_user_id BIGINT,
    accepted_by_user_id BIGINT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pension_invites_token ON pension_invites (token);
CREATE INDEX IF NOT EXISTS idx_pension_invites_email ON pension_invites (email);
CREATE INDEX IF NOT EXISTS idx_pension_invites_pension ON pension_invites (pension_id);

CREATE TABLE IF NOT EXISTS pension_inquiries (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    requester_id BIGINT,
    contact_name VARCHAR(120) NOT NULL,
    contact_email VARCHAR(190),
    contact_phone VARCHAR(40),
    room_type VARCHAR(20) NOT NULL DEFAULT 'ANY',
    move_in_date DATE,
    message VARCHAR(2000),
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    terms_accepted_version VARCHAR(20),
    privacy_accepted_version VARCHAR(20),
    consent_accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE pension_inquiries
    ADD COLUMN IF NOT EXISTS requester_id BIGINT,
    ADD COLUMN IF NOT EXISTS terms_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS privacy_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS consent_accepted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_pension_inquiries_pension_created
    ON pension_inquiries (pension_id, created_at);
CREATE INDEX IF NOT EXISTS idx_pension_inquiries_status
    ON pension_inquiries (status);
CREATE INDEX IF NOT EXISTS idx_pension_inquiries_requester_created
    ON pension_inquiries (requester_id, created_at);

CREATE TABLE IF NOT EXISTS pension_favorites (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    pension_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pension_favorites_user_pension
    ON pension_favorites (user_id, pension_id);
CREATE INDEX IF NOT EXISTS idx_pension_favorites_user_created
    ON pension_favorites (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_pension_favorites_pension
    ON pension_favorites (pension_id);

CREATE TABLE IF NOT EXISTS user_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(180) NOT NULL,
    message VARCHAR(700),
    link VARCHAR(500),
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_notifications_user_created
    ON user_notifications (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_user_notifications_user_read
    ON user_notifications (user_id, read_at);

CREATE TABLE IF NOT EXISTS pension_views (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    visitor_hash VARCHAR(64) NOT NULL,
    viewed_on DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pension_views_pension_visitor_day
    ON pension_views (pension_id, visitor_hash, viewed_on);
CREATE INDEX IF NOT EXISTS idx_pension_views_pension_day
    ON pension_views (pension_id, viewed_on);
CREATE INDEX IF NOT EXISTS idx_pension_views_day
    ON pension_views (viewed_on);

CREATE TABLE IF NOT EXISTS pension_reports (
    id BIGSERIAL PRIMARY KEY,
    pension_id BIGINT NOT NULL,
    reporter_user_id BIGINT,
    reporter_key_hash VARCHAR(64),
    reporter_email VARCHAR(190),
    reason VARCHAR(40) NOT NULL,
    details VARCHAR(1200),
    status VARCHAR(30) NOT NULL DEFAULT 'NEW',
    admin_notes VARCHAR(1500),
    reviewed_by_user_id BIGINT,
    reviewed_at TIMESTAMPTZ,
    terms_accepted_version VARCHAR(20),
    privacy_accepted_version VARCHAR(20),
    consent_accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE pension_reports
    ADD COLUMN IF NOT EXISTS terms_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS privacy_accepted_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS consent_accepted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_pension_reports_status_created
    ON pension_reports (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pension_reports_pension
    ON pension_reports (pension_id);
CREATE INDEX IF NOT EXISTS idx_pension_reports_reporter_user
    ON pension_reports (reporter_user_id);
CREATE INDEX IF NOT EXISTS idx_pension_reports_reporter_key
    ON pension_reports (reporter_key_hash);

CREATE TABLE IF NOT EXISTS study_center_catalog (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(140) NOT NULL,
    normalized_name VARCHAR(140) NOT NULL,
    country_code VARCHAR(2),
    city VARCHAR(100),
    lat DOUBLE PRECISION,
    lng DOUBLE PRECISION,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_study_center_catalog_normalized_name
    ON study_center_catalog (normalized_name);
CREATE INDEX IF NOT EXISTS idx_study_center_catalog_name_lower
    ON study_center_catalog (LOWER(name));
CREATE INDEX IF NOT EXISTS idx_study_center_catalog_verified
    ON study_center_catalog (verified);

-- Importa al catálogo cualquier centro ya asociado a una pensión.
INSERT INTO study_center_catalog (name, normalized_name, country_code, city, verified)
SELECT MIN(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g'))) AS name,
       LOWER(TRANSLATE(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g')),
             'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun')) AS normalized_name,
       MIN(p.country_code) AS country_code,
       MIN(p.city) AS city,
       FALSE
FROM pension_study_centers psc
JOIN pensions p ON p.id = psc.pension_id
WHERE psc.study_center IS NOT NULL
  AND TRIM(psc.study_center) <> ''
GROUP BY LOWER(TRANSLATE(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g')),
              'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun'))
ON CONFLICT (normalized_name) DO NOTHING;

-- Foreign keys. Se agregan solo si no existen para soportar bases heredadas.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_organizations_owner') THEN
        ALTER TABLE organizations ADD CONSTRAINT fk_organizations_owner FOREIGN KEY (owner_id) REFERENCES users(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_memberships_org') THEN
        ALTER TABLE memberships ADD CONSTRAINT fk_memberships_org FOREIGN KEY (org_id) REFERENCES organizations(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_memberships_user') THEN
        ALTER TABLE memberships ADD CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_org_invites_org') THEN
        ALTER TABLE org_invites ADD CONSTRAINT fk_org_invites_org FOREIGN KEY (org_id) REFERENCES organizations(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_email_verification_tokens_user') THEN
        ALTER TABLE email_verification_tokens ADD CONSTRAINT fk_email_verification_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pensions_org') THEN
        ALTER TABLE pensions ADD CONSTRAINT fk_pensions_org FOREIGN KEY (org_id) REFERENCES organizations(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pensions_created_by') THEN
        ALTER TABLE pensions ADD CONSTRAINT fk_pensions_created_by FOREIGN KEY (created_by_id) REFERENCES users(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pensions_owner') THEN
        ALTER TABLE pensions ADD CONSTRAINT fk_pensions_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_amenities_pension') THEN
        ALTER TABLE pension_amenities ADD CONSTRAINT fk_pension_amenities_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_nearby_tags_pension') THEN
        ALTER TABLE pension_nearby_tags ADD CONSTRAINT fk_pension_nearby_tags_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_study_centers_pension') THEN
        ALTER TABLE pension_study_centers ADD CONSTRAINT fk_pension_study_centers_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_media_pension') THEN
        ALTER TABLE pension_media ADD CONSTRAINT fk_pension_media_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_members_pension') THEN
        ALTER TABLE pension_members ADD CONSTRAINT fk_pension_members_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_members_user') THEN
        ALTER TABLE pension_members ADD CONSTRAINT fk_pension_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_invites_pension') THEN
        ALTER TABLE pension_invites ADD CONSTRAINT fk_pension_invites_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_inquiries_pension') THEN
        ALTER TABLE pension_inquiries ADD CONSTRAINT fk_pension_inquiries_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_inquiries_requester') THEN
        ALTER TABLE pension_inquiries ADD CONSTRAINT fk_pension_inquiries_requester FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_favorites_user') THEN
        ALTER TABLE pension_favorites ADD CONSTRAINT fk_pension_favorites_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_favorites_pension') THEN
        ALTER TABLE pension_favorites ADD CONSTRAINT fk_pension_favorites_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_notifications_user') THEN
        ALTER TABLE user_notifications ADD CONSTRAINT fk_user_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_views_pension') THEN
        ALTER TABLE pension_views ADD CONSTRAINT fk_pension_views_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_reports_pension') THEN
        ALTER TABLE pension_reports ADD CONSTRAINT fk_pension_reports_pension FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_reports_reporter') THEN
        ALTER TABLE pension_reports ADD CONSTRAINT fk_pension_reports_reporter FOREIGN KEY (reporter_user_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pension_reports_reviewed_by') THEN
        ALTER TABLE pension_reports ADD CONSTRAINT fk_pension_reports_reviewed_by FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END $$;

-- Checks de dominio que ya forman parte del modelo actual.
ALTER TABLE pensions DROP CONSTRAINT IF EXISTS chk_pensions_admission_type;
ALTER TABLE pensions ADD CONSTRAINT chk_pensions_admission_type
    CHECK (admission_type IS NULL OR admission_type IN ('MIXED', 'WOMEN_ONLY', 'MEN_ONLY'));

ALTER TABLE pensions DROP CONSTRAINT IF EXISTS ck_pensions_resident_profile;
ALTER TABLE pensions ADD CONSTRAINT ck_pensions_resident_profile
    CHECK (resident_profile IS NULL OR resident_profile IN ('OPEN_TO_ALL', 'STUDENTS_PREFERRED', 'STUDENTS_ONLY'));

ALTER TABLE study_center_catalog DROP CONSTRAINT IF EXISTS ck_study_center_catalog_lat;
ALTER TABLE study_center_catalog ADD CONSTRAINT ck_study_center_catalog_lat
    CHECK (lat IS NULL OR (lat >= -90 AND lat <= 90));

ALTER TABLE study_center_catalog DROP CONSTRAINT IF EXISTS ck_study_center_catalog_lng;
ALTER TABLE study_center_catalog ADD CONSTRAINT ck_study_center_catalog_lng
    CHECK (lng IS NULL OR (lng >= -180 AND lng <= 180));

ALTER TABLE study_center_catalog DROP CONSTRAINT IF EXISTS ck_study_center_catalog_coords_pair;
ALTER TABLE study_center_catalog ADD CONSTRAINT ck_study_center_catalog_coords_pair
    CHECK ((lat IS NULL) = (lng IS NULL));
