-- Every delivery is a batch: kg received (quantity_kg), sold, lost and remaining. Sales and losses take kg from
-- their lot's oldest batches first (by delivered_at, then id) and record which batches they used. Lot totals do not
-- change: the backfill below is checked against them and the whole migration rolls back if anything differs.

ALTER TABLE delivery
    ADD COLUMN sold_kg NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN lost_kg NUMERIC(12,2) NOT NULL DEFAULT 0;
-- Computed by the database; the app never writes it.
ALTER TABLE delivery
    ADD COLUMN remaining_kg NUMERIC(12,2) GENERATED ALWAYS AS (quantity_kg - sold_kg - lost_kg) STORED;
ALTER TABLE delivery ADD CONSTRAINT delivery_batch_kg_check
    CHECK (sold_kg >= 0 AND lost_kg >= 0 AND sold_kg + lost_kg <= quantity_kg);

CREATE TABLE sale_allocation (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sale_id     BIGINT        NOT NULL REFERENCES sale (id),
    delivery_id BIGINT        NOT NULL REFERENCES delivery (id),
    quantity_kg NUMERIC(12,2) NOT NULL CHECK (quantity_kg > 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (sale_id, delivery_id)
);

CREATE TABLE loss_allocation (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    loss_id     BIGINT        NOT NULL REFERENCES loss (id),
    delivery_id BIGINT        NOT NULL REFERENCES delivery (id),
    quantity_kg NUMERIC(12,2) NOT NULL CHECK (quantity_kg > 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (loss_id, delivery_id)
);

CREATE INDEX idx_sale_allocation_delivery ON sale_allocation (delivery_id);
CREATE INDEX idx_loss_allocation_delivery ON loss_allocation (delivery_id);
-- The oldest batches of a lot that still have kg, in the order they are used.
CREATE INDEX idx_delivery_batch_fifo ON delivery (lot_id, delivered_at, id)
    WHERE reversed_at IS NULL AND remaining_kg > 0;

-- ---------------------------------------------------------------------------------------------------------------
-- Backfill. The lot totals as they are now, to compare with at the end.
CREATE TEMP TABLE backfill_lot_totals ON COMMIT DROP AS
SELECT l.id AS lot_id,
       (SELECT COALESCE(SUM(d.quantity_kg), 0) FROM delivery d WHERE d.lot_id = l.id AND d.reversed_at IS NULL)
           AS delivered_kg,
       (SELECT COALESCE(SUM(x.quantity_kg), 0) FROM loss x WHERE x.lot_id = l.id) AS lost_kg,
       (SELECT COALESCE(SUM(s.quantity_kg), 0) FROM sale s WHERE s.lot_id = l.id) AS sold_kg
FROM lot l;

-- Each lot's batches laid end to end, oldest first: batch b covers kg [b_start, b_end) of the lot.
-- Reversed deliveries are not batches any more and get nothing.
CREATE TEMP TABLE backfill_batches ON COMMIT DROP AS
SELECT d.id AS delivery_id,
       d.lot_id,
       SUM(d.quantity_kg) OVER w - d.quantity_kg AS b_start,
       SUM(d.quantity_kg) OVER w AS b_end
FROM delivery d
WHERE d.reversed_at IS NULL
WINDOW w AS (PARTITION BY d.lot_id ORDER BY d.delivered_at, d.id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW);

-- Each lot's sales and losses in the order they were recorded, laid end to end the same way: event e takes kg
-- [e_start, e_end). Taking kg in this order from the oldest batch first is exactly the overlap of the two ranges.
CREATE TEMP TABLE backfill_events ON COMMIT DROP AS
SELECT kind, event_id, lot_id,
       SUM(quantity_kg) OVER w - quantity_kg AS e_start,
       SUM(quantity_kg) OVER w AS e_end
FROM (
    SELECT 'SALE' AS kind, s.id AS event_id, s.lot_id, s.quantity_kg, s.created_at FROM sale s
    UNION ALL
    SELECT 'LOSS' AS kind, x.id AS event_id, x.lot_id, x.quantity_kg, x.created_at FROM loss x
) events
WINDOW w AS (PARTITION BY lot_id ORDER BY created_at, kind, event_id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW);

CREATE TEMP TABLE backfill_overlaps ON COMMIT DROP AS
SELECT e.kind, e.event_id, b.delivery_id,
       LEAST(b.b_end, e.e_end) - GREATEST(b.b_start, e.e_start) AS quantity_kg
FROM backfill_events e
JOIN backfill_batches b ON b.lot_id = e.lot_id
WHERE LEAST(b.b_end, e.e_end) > GREATEST(b.b_start, e.e_start);

INSERT INTO sale_allocation (sale_id, delivery_id, quantity_kg)
SELECT event_id, delivery_id, quantity_kg FROM backfill_overlaps WHERE kind = 'SALE';

INSERT INTO loss_allocation (loss_id, delivery_id, quantity_kg)
SELECT event_id, delivery_id, quantity_kg FROM backfill_overlaps WHERE kind = 'LOSS';

UPDATE delivery d SET sold_kg = a.kg
FROM (SELECT delivery_id, SUM(quantity_kg) AS kg FROM sale_allocation GROUP BY delivery_id) a
WHERE a.delivery_id = d.id;

UPDATE delivery d SET lost_kg = a.kg
FROM (SELECT delivery_id, SUM(quantity_kg) AS kg FROM loss_allocation GROUP BY delivery_id) a
WHERE a.delivery_id = d.id;

-- ---------------------------------------------------------------------------------------------------------------
-- Checks. Any difference stops the migration, and the whole of it is rolled back.
DO $$
DECLARE
    bad_lots   INTEGER;
    bad_sales  INTEGER;
    bad_losses INTEGER;
BEGIN
    SELECT COUNT(*) INTO bad_lots
    FROM backfill_lot_totals t
    LEFT JOIN (
        SELECT lot_id, SUM(quantity_kg) AS received, SUM(sold_kg) AS sold, SUM(lost_kg) AS lost,
               SUM(remaining_kg) AS remaining
        FROM delivery WHERE reversed_at IS NULL GROUP BY lot_id
    ) b ON b.lot_id = t.lot_id
    WHERE COALESCE(b.received, 0) <> t.delivered_kg
       OR COALESCE(b.sold, 0) <> t.sold_kg
       OR COALESCE(b.lost, 0) <> t.lost_kg
       OR COALESCE(b.remaining, 0) <> t.delivered_kg - t.lost_kg - t.sold_kg;

    SELECT COUNT(*) INTO bad_sales
    FROM sale s
    WHERE s.quantity_kg <> (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM sale_allocation a WHERE a.sale_id = s.id);

    SELECT COUNT(*) INTO bad_losses
    FROM loss x
    WHERE x.quantity_kg <> (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM loss_allocation a WHERE a.loss_id = x.id);

    IF bad_lots > 0 OR bad_sales > 0 OR bad_losses > 0 THEN
        RAISE EXCEPTION 'Batch backfill does not match: % lot(s), % sale(s), % loss(es) differ. Nothing was changed.',
            bad_lots, bad_sales, bad_losses;
    END IF;
END
$$;
