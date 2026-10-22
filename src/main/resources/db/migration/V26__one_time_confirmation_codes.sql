-- Deliveries are now confirmed with a one-time code sent by SMS for each delivery, instead of a permanent member
-- PIN. Only a BCrypt hash of the code is stored. A code expires after 10 minutes and dies after 3 wrong tries;
-- the manager can then send a new one, at most 3 times per delivery.
ALTER TABLE delivery
    ADD COLUMN confirmation_code_hash            VARCHAR(100),
    ADD COLUMN confirmation_code_expires_at      TIMESTAMPTZ,
    ADD COLUMN confirmation_code_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN confirmation_code_resends         INTEGER NOT NULL DEFAULT 0;

-- The PIN is gone. There is no real PIN data, so the columns are dropped. pin_mismatch only marked deliveries
-- recorded with a wrong PIN. audit_log keeps its PIN_SET and PIN_RESET rows as history.
ALTER TABLE member
    DROP COLUMN pin_hash,
    DROP COLUMN pin_set_at,
    DROP COLUMN pin_failed_attempts,
    DROP COLUMN pin_locked_at;
ALTER TABLE delivery DROP COLUMN pin_mismatch;

-- 'PIN' stays allowed only if a delivery was already confirmed with a PIN, so those rows remain valid.
ALTER TABLE delivery DROP CONSTRAINT delivery_confirmation_method_check;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM delivery WHERE confirmation_method = 'PIN') THEN
        ALTER TABLE delivery ADD CONSTRAINT delivery_confirmation_method_check CHECK (confirmation_method IN
            ('OTP', 'SMS', 'MANAGER_REVIEW', 'RECORDED_BEFORE_CONFIRMATION', 'PIN'));
    ELSE
        ALTER TABLE delivery ADD CONSTRAINT delivery_confirmation_method_check CHECK (confirmation_method IN
            ('OTP', 'SMS', 'MANAGER_REVIEW', 'RECORDED_BEFORE_CONFIRMATION'));
    END IF;
END
$$;

-- A resent code is a new message for the same delivery, so one delivery can now have up to 4 messages:
-- send_number 0 is the first, 1 to 3 are resends. The unique index still stops the same message twice.
ALTER TABLE sms_log ADD COLUMN send_number INTEGER NOT NULL DEFAULT 0;
DROP INDEX uq_sms_log_delivery;
CREATE UNIQUE INDEX uq_sms_log_delivery_send ON sms_log (delivery_id, send_number) WHERE delivery_id IS NOT NULL;

-- CANCELLED: a code message that was not sent yet and is no longer needed (a newer code was requested, or the
-- delivery was confirmed, disputed or reversed in the meantime).
ALTER TABLE sms_log DROP CONSTRAINT sms_log_status_check;
ALTER TABLE sms_log ADD CONSTRAINT sms_log_status_check
    CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'CANCELLED'));
