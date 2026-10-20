-- V21 marked deliveries recorded before confirmations existed as CONFIRMED with no method, which reads as if
-- members confirmed deliveries they never saw. V21 is not edited because it may already be applied; instead
-- those deliveries get their own method. They stay CONFIRMED so they are not listed as waiting for 48 hours.
ALTER TABLE delivery DROP CONSTRAINT delivery_confirmation_method_check;

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
