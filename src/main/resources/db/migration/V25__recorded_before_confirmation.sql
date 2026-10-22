-- V21 marked deliveries recorded before confirmations existed as CONFIRMED with no method, which reads as if
-- members confirmed deliveries they never saw. V21 is not edited because it may already be applied; instead
-- those deliveries get their own method. They stay CONFIRMED so they are not listed as waiting for 48 hours.

-- V21 let Postgres name the CHECK, so it is looked up instead of assuming the generated name.
DO $$
DECLARE
    check_name TEXT;
BEGIN
    SELECT c.conname INTO STRICT check_name
    FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
    WHERE c.conrelid = 'delivery'::regclass
      AND c.contype = 'c'
      AND a.attname = 'confirmation_method'
      AND cardinality(c.conkey) = 1;

    EXECUTE format('ALTER TABLE delivery DROP CONSTRAINT %I', check_name);
END
$$;

ALTER TABLE delivery
    ALTER COLUMN confirmation_method TYPE VARCHAR(30),
    ADD CONSTRAINT delivery_confirmation_method_check
        CHECK (confirmation_method IN ('PIN', 'SMS', 'MANAGER_REVIEW', 'RECORDED_BEFORE_CONFIRMATION'));

-- Every confirmation through the app sets a method and a time, so only the old deliveries match.
UPDATE delivery
SET confirmation_method = 'RECORDED_BEFORE_CONFIRMATION'
WHERE confirmation_status = 'CONFIRMED'
  AND confirmation_method IS NULL
  AND confirmed_at IS NULL;
