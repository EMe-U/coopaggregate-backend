-- Each payment gets its own code (PAY-0001), separate from the MoMo or bank reference.
ALTER TABLE produce_payment ADD COLUMN payment_code VARCHAR(20);

-- Payments recorded before this version are numbered in the order they were saved.
UPDATE produce_payment p
SET payment_code = 'PAY-' || CASE WHEN numbered.n < 10000 THEN LPAD(numbered.n::text, 4, '0') ELSE numbered.n::text END
FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS n FROM produce_payment) numbered
WHERE p.id = numbered.id;

ALTER TABLE produce_payment ALTER COLUMN payment_code SET NOT NULL;
ALTER TABLE produce_payment ADD CONSTRAINT uq_produce_payment_code UNIQUE (payment_code);
