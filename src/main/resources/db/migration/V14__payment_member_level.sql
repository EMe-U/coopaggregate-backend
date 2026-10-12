-- A payment settles what a member is owed across all shared lots, so it no longer belongs to one lot.
ALTER TABLE produce_payment ALTER COLUMN lot_id DROP NOT NULL;

-- Created by the client, so a payment sent twice (a retry or a double tap) is saved only once.
-- NOT NULL is safe here because no payments could be recorded before this version.
ALTER TABLE produce_payment ADD COLUMN client_uuid UUID NOT NULL UNIQUE;

-- The cooperative pays members in cash or by mobile money (MoMo).
ALTER TABLE produce_payment DROP CONSTRAINT produce_payment_method_check;
ALTER TABLE produce_payment ADD CONSTRAINT produce_payment_method_check CHECK (method IN ('CASH', 'MOMO'));
