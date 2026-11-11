-- EMPTIED: a full lot whose deliveries were all reversed or corrected before any of its kg were sold. It holds nothing
-- and earned nothing, so it is not SOLD and can never be shared. It gets no new kg (only the FILLING lot is filled).
-- Only the list of allowed statuses changes: no row is touched.
ALTER TABLE lot DROP CONSTRAINT lot_status_check;
ALTER TABLE lot ADD CONSTRAINT lot_status_check
    CHECK (status IN ('OPEN', 'CLOSED', 'SOLD', 'SHARED', 'FILLING', 'FULL', 'EMPTIED'));
