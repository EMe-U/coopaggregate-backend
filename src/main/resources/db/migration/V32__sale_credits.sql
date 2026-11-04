-- Prepares sales by grade, where each member is credited for the batches a sale used. Only adds columns, a
-- constraint and indexes: no ledger row is changed, and the only row update fills in the grade of existing sales.

-- A sale's grade. Existing sales take the grade of their lot. A sale by grade can take kg from several lots, so
-- the lot becomes optional.
ALTER TABLE sale ADD COLUMN grade_id BIGINT REFERENCES grade (id);
UPDATE sale s SET grade_id = l.grade_id FROM lot l WHERE l.id = s.lot_id;
ALTER TABLE sale ALTER COLUMN grade_id SET NOT NULL;
ALTER TABLE sale ALTER COLUMN lot_id DROP NOT NULL;
CREATE INDEX idx_sale_grade_id ON sale (grade_id);

-- What a sale pays the member for one batch: gross is the batch's part of the sale amount, the deduction is per kg
-- sold. Both stay empty for sales made before this; their members are paid through lot sharing instead.
ALTER TABLE sale_allocation
    ADD COLUMN gross_rwf BIGINT,
    ADD COLUMN deduction_rwf BIGINT;
ALTER TABLE sale_allocation ADD CONSTRAINT sale_allocation_credit_check CHECK (
    (gross_rwf IS NULL AND deduction_rwf IS NULL)
    OR (gross_rwf IS NOT NULL AND deduction_rwf IS NOT NULL AND deduction_rwf >= 0 AND gross_rwf >= deduction_rwf));

-- The ledger gets the SALE_CREDIT entry type. It has 11 characters, so the column is widened; making a VARCHAR
-- longer does not rewrite or update any row, so the append-only trigger is not involved.
-- V2 let Postgres name the CHECK, so it is looked up instead of assuming the generated name.
DO $$
DECLARE
    check_name TEXT;
BEGIN
    SELECT c.conname INTO STRICT check_name
    FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
    WHERE c.conrelid = 'ledger_entry'::regclass
      AND c.contype = 'c'
      AND a.attname = 'entry_type'
      AND cardinality(c.conkey) = 1;

    EXECUTE format('ALTER TABLE ledger_entry DROP CONSTRAINT %I', check_name);
END
$$;

ALTER TABLE ledger_entry
    ALTER COLUMN entry_type TYPE VARCHAR(20),
    ADD CONSTRAINT ledger_entry_entry_type_check
        CHECK (entry_type IN ('DELIVERY', 'LOSS', 'SALE', 'SHARE', 'PAYMENT', 'REVERSAL', 'SALE_CREDIT'));

-- A batch's part of a sale is credited once: the database refuses a second SALE_CREDIT for the same allocation.
CREATE UNIQUE INDEX uq_ledger_entry_sale_credit ON ledger_entry (related_entity_type, related_entity_id)
    WHERE entry_type = 'SALE_CREDIT';
