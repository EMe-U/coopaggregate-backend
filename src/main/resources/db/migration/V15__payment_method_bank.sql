-- Members can also be paid by bank transfer.
ALTER TABLE produce_payment DROP CONSTRAINT produce_payment_method_check;
ALTER TABLE produce_payment ADD CONSTRAINT produce_payment_method_check CHECK (method IN ('CASH', 'MOMO', 'BANK'));
