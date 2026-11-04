-- A delivery can now get two kinds of SMS: the message with its one-time code (up to 4, see V26) and, once the
-- delivery is confirmed, one receipt. Every message so far was a code message.
ALTER TABLE sms_log ADD COLUMN message_type VARCHAR(20) NOT NULL DEFAULT 'DELIVERY_CODE';
ALTER TABLE sms_log ADD CONSTRAINT sms_log_message_type_check CHECK (message_type IN ('DELIVERY_CODE', 'RECEIPT'));

-- Still never the same message twice: one per delivery, kind and send number.
DROP INDEX uq_sms_log_delivery_send;
CREATE UNIQUE INDEX uq_sms_log_delivery_type_send ON sms_log (delivery_id, message_type, send_number)
    WHERE delivery_id IS NOT NULL;
