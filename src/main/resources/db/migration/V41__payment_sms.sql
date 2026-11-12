-- Members get an SMS when a payment to them is recorded (PAYMENT) and when it is reversed (PAYMENT_REVERSED).
-- Only adds: no existing message changes. A payment message names its payment; one message per payment and kind.
ALTER TABLE sms_log ADD COLUMN payment_id BIGINT REFERENCES produce_payment (id);

ALTER TABLE sms_log DROP CONSTRAINT sms_log_message_type_check;
ALTER TABLE sms_log ADD CONSTRAINT sms_log_message_type_check
    CHECK (message_type IN ('DELIVERY_CODE', 'RECEIPT', 'PAYMENT', 'PAYMENT_REVERSED'));
ALTER TABLE sms_log ADD CONSTRAINT sms_log_payment_message_check
    CHECK (message_type NOT IN ('PAYMENT', 'PAYMENT_REVERSED') OR payment_id IS NOT NULL);

CREATE UNIQUE INDEX uq_sms_log_payment_type ON sms_log (payment_id, message_type) WHERE payment_id IS NOT NULL;
