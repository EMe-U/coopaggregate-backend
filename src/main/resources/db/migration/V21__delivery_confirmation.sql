-- The member confirms each delivery, with their PIN when it is recorded or later by SMS, or disputes it.
-- A disputed delivery is either found correct by the manager (MANAGER_REVIEW) or fixed with a correction or
-- reversal. pin_mismatch marks a delivery recorded with a wrong PIN; it is saved anyway so nothing is lost.
ALTER TABLE delivery
    ADD COLUMN confirmation_status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (confirmation_status IN ('PENDING', 'CONFIRMED', 'DISPUTED')),
    ADD COLUMN confirmation_method VARCHAR(15)
        CHECK (confirmation_method IN ('PIN', 'SMS', 'MANAGER_REVIEW')),
    ADD COLUMN confirmed_at        TIMESTAMPTZ,
    ADD COLUMN dispute_reason      VARCHAR(20)
        CHECK (dispute_reason IN ('WRONG_KG', 'WRONG_GRADE', 'NOT_MY_DELIVERY', 'OTHER')),
    ADD COLUMN dispute_note        TEXT,
    ADD COLUMN disputed_at         TIMESTAMPTZ,
    ADD COLUMN resolution_note     TEXT,
    ADD COLUMN pin_mismatch        BOOLEAN NOT NULL DEFAULT FALSE;

-- Deliveries recorded before confirmations existed were accepted under the old process. They have no
-- confirmation method, so they can be told apart from deliveries a member confirmed.
UPDATE delivery SET confirmation_status = 'CONFIRMED';

CREATE INDEX idx_delivery_confirmation_status ON delivery (confirmation_status);
