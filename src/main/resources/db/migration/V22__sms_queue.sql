-- Outgoing SMS are queued, sent by the configured provider and retried when they fail.
ALTER TABLE sms_log DROP CONSTRAINT sms_log_status_check;
UPDATE sms_log SET status = 'QUEUED' WHERE status = 'PENDING';
ALTER TABLE sms_log ALTER COLUMN status SET DEFAULT 'QUEUED';
ALTER TABLE sms_log ADD CONSTRAINT sms_log_status_check CHECK (status IN ('QUEUED', 'SENT', 'FAILED'));

ALTER TABLE sms_log
    ADD COLUMN delivery_id BIGINT REFERENCES delivery (id),
    ADD COLUMN provider    VARCHAR(20),
    ADD COLUMN attempts    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_error  TEXT;

-- One message per delivery, even if the after-commit step runs twice.
CREATE UNIQUE INDEX uq_sms_log_delivery ON sms_log (delivery_id) WHERE delivery_id IS NOT NULL;
CREATE INDEX idx_sms_log_status ON sms_log (status);
