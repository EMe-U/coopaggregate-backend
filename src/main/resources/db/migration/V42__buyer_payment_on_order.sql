-- A buyer pays a whole order once, in full. The order keeps how: payment_method (CASH, MOMO or BANK) and
-- payment_reference (required for MOMO and BANK, checked by the app). Orders saved before this have neither, which
-- the app shows as "not recorded".
ALTER TABLE sale_order ADD COLUMN payment_method VARCHAR(10)
    CONSTRAINT sale_order_payment_method_check CHECK (payment_method IN ('CASH', 'MOMO', 'BANK'));
ALTER TABLE sale_order ADD COLUMN payment_reference VARCHAR(50);

-- Every sale now belongs to an order: a sale of one grade is an order of its own. Each sale saved without one gets
-- an order here, coded ORD-S- and its sale id (the second hyphen keeps these codes apart from the random ORD- codes),
-- with the sale's buyer, date and creation time. No sale, amount or ledger entry changes; only the link is set.
INSERT INTO sale_order (order_code, client_uuid, buyer_id, sale_date, created_at)
SELECT 'ORD-S-' || s.id, gen_random_uuid(), s.buyer_id, s.sale_date, s.created_at
FROM sale s
WHERE s.sale_order_id IS NULL;

UPDATE sale s
SET sale_order_id = o.id
FROM sale_order o
WHERE s.sale_order_id IS NULL
  AND o.order_code = 'ORD-S-' || s.id;

-- Fails, and so undoes this whole migration, if any sale was left without an order.
ALTER TABLE sale ALTER COLUMN sale_order_id SET NOT NULL;
