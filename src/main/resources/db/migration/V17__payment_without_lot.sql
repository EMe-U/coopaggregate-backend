-- Payments are made to a member for everything they are owed, never to one lot. The API never filled
-- this column (it has been optional since V14), so dropping it loses no data.
ALTER TABLE produce_payment DROP COLUMN lot_id;
