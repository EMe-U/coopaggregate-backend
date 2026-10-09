-- A lot becomes SOLD when nothing is left to sell, before the money is shared among its members (SHARED).
ALTER TABLE lot DROP CONSTRAINT lot_status_check;
ALTER TABLE lot ADD CONSTRAINT lot_status_check CHECK (status IN ('OPEN', 'CLOSED', 'SOLD', 'SHARED'));
