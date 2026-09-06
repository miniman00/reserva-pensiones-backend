ALTER TABLE pension_inquiries
    ADD COLUMN attributed_promotion_id BIGINT,
    ADD COLUMN attributed_promotion_product_code VARCHAR(80),
    ADD COLUMN attributed_promotion_product_name VARCHAR(160),
    ADD COLUMN attributed_promotion_target_type VARCHAR(30);

ALTER TABLE pension_inquiries
    ADD CONSTRAINT fk_pension_inquiries_attributed_promotion
    FOREIGN KEY (attributed_promotion_id)
    REFERENCES pension_promotions(id)
    ON DELETE SET NULL;

CREATE INDEX idx_pension_inquiries_attributed_promotion
    ON pension_inquiries(attributed_promotion_id)
    WHERE attributed_promotion_id IS NOT NULL;
