-- Buyers send requests from the public page without an account. Their contact details stay on the request until the
-- manager turns the request into a buyer record, so strangers cannot fill the buyer list. The existing statuses
-- (PENDING, ACCEPTED, REJECTED) are kept.
ALTER TABLE buyer_request ALTER COLUMN buyer_id DROP NOT NULL;

ALTER TABLE buyer_request
    ADD COLUMN requester_name  VARCHAR(150),
    ADD COLUMN requester_phone VARCHAR(20),
    ADD COLUMN requester_email VARCHAR(150),
    -- What the buyer may read when they look up their request. manager_note stays private.
    ADD COLUMN public_message  VARCHAR(500),
    ADD COLUMN updated_at      TIMESTAMPTZ;

ALTER TABLE buyer_request ADD CONSTRAINT buyer_request_has_contact
    CHECK (buyer_id IS NOT NULL OR (requester_name IS NOT NULL AND requester_phone IS NOT NULL));

CREATE INDEX idx_buyer_request_status ON buyer_request (status);
CREATE INDEX idx_buyer_request_requester_phone ON buyer_request (requester_phone);
