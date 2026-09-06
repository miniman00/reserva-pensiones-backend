CREATE TABLE IF NOT EXISTS pension_study_centers (
    pension_id BIGINT NOT NULL,
    study_center VARCHAR(140) NOT NULL,
    CONSTRAINT pk_pension_study_centers PRIMARY KEY (pension_id, study_center),
    CONSTRAINT fk_pension_study_centers_pension
        FOREIGN KEY (pension_id) REFERENCES pensions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_pension_study_centers_lower_name
    ON pension_study_centers (LOWER(study_center));
