-- A member confirms deliveries with a 4-digit PIN. Only a BCrypt hash is stored. After 3 wrong PINs in a row
-- the PIN is locked until the manager resets it.
ALTER TABLE member
    ADD COLUMN pin_hash            VARCHAR(100),
    ADD COLUMN pin_set_at          TIMESTAMPTZ,
    ADD COLUMN pin_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_at       TIMESTAMPTZ;

-- Who changed what and when, for actions that are not money and so not in the ledger (for example PIN resets).
CREATE TABLE audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action     VARCHAR(30) NOT NULL,
    member_id  BIGINT      REFERENCES member (id),
    manager_id BIGINT      REFERENCES manager (id),
    details    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
