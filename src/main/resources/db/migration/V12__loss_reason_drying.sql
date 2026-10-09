-- Potatoes lose weight while drying in the store, which the manager records as its own loss reason.
ALTER TABLE loss DROP CONSTRAINT loss_reason_check;
ALTER TABLE loss ADD CONSTRAINT loss_reason_check CHECK (reason IN ('ROT', 'DRYING', 'DAMAGE', 'THEFT', 'OTHER'));
