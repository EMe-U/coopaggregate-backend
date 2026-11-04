-- Fixed-size lots. A lot has one grade and a fixed capacity (the lot size setting when it was opened). The system fills
-- one lot per grade at a time with confirmed deliveries, in the order they are confirmed; a delivery that does not fit
-- completes the lot and the rest starts the next one. What part of a delivery is in which lot is a lot portion.
--
-- Only adds: no ledger row is touched. Existing lots keep their size and status (OPEN, CLOSED, SOLD, SHARED) and get no
-- new kg; each existing delivery gets one portion in the lot it is already in, with the same sold and lost kg. The only
-- row update fills in each delivery's grade from its lot. The checks at the end roll everything back on any difference.

-- The size of new lots. Editable until the first fixed-size lot exists (checked by the app).
ALTER TABLE setting ADD COLUMN lot_size_kg NUMERIC(12,2) NOT NULL DEFAULT 100;
ALTER TABLE setting ADD CONSTRAINT setting_lot_size_kg_check CHECK (lot_size_kg > 0);

-- A fixed-size lot is FILLING until it holds its capacity, then FULL, then SOLD once nothing is left.
ALTER TABLE lot ADD COLUMN capacity_kg NUMERIC(12,2);
ALTER TABLE lot DROP CONSTRAINT lot_status_check;
ALTER TABLE lot ADD CONSTRAINT lot_status_check
    CHECK (status IN ('OPEN', 'CLOSED', 'SOLD', 'SHARED', 'FILLING', 'FULL'));
ALTER TABLE lot ADD CONSTRAINT lot_capacity_check
    CHECK (capacity_kg IS NULL OR capacity_kg > 0);
ALTER TABLE lot ADD CONSTRAINT lot_fixed_size_check
    CHECK (status NOT IN ('FILLING', 'FULL') OR capacity_kg IS NOT NULL);
CREATE UNIQUE INDEX uq_lot_one_filling_per_grade ON lot (grade_id) WHERE status = 'FILLING';

-- A delivery has its own grade: a pending delivery is in no lot yet.
ALTER TABLE delivery ADD COLUMN grade_id BIGINT REFERENCES grade (id);
UPDATE delivery d SET grade_id = l.grade_id FROM lot l WHERE l.id = d.lot_id;
ALTER TABLE delivery ALTER COLUMN grade_id SET NOT NULL;
CREATE INDEX idx_delivery_grade_id ON delivery (grade_id);
-- Only deliveries recorded before fixed-size lots have a lot of their own; newer ones are in lots through portions.
ALTER TABLE delivery ALTER COLUMN lot_id DROP NOT NULL;

CREATE TABLE lot_portion (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lot_id       BIGINT        NOT NULL REFERENCES lot (id),
    delivery_id  BIGINT        NOT NULL REFERENCES delivery (id),
    quantity_kg  NUMERIC(12,2) NOT NULL CHECK (quantity_kg > 0),
    sold_kg      NUMERIC(12,2) NOT NULL DEFAULT 0,
    lost_kg      NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Computed by the database; the app never writes it.
    remaining_kg NUMERIC(12,2) GENERATED ALWAYS AS (quantity_kg - sold_kg - lost_kg) STORED,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT lot_portion_kg_check CHECK (sold_kg >= 0 AND lost_kg >= 0 AND sold_kg + lost_kg <= quantity_kg),
    UNIQUE (lot_id, delivery_id)
);
CREATE INDEX idx_lot_portion_delivery ON lot_portion (delivery_id);

-- Which portion a sale or a loss took kg from. Empty for rows made before fixed-size lots: their delivery has exactly
-- one portion, in delivery.lot_id.
ALTER TABLE sale_allocation ADD COLUMN lot_portion_id BIGINT REFERENCES lot_portion (id);
ALTER TABLE loss_allocation ADD COLUMN lot_portion_id BIGINT REFERENCES lot_portion (id);
CREATE INDEX idx_sale_allocation_portion ON sale_allocation (lot_portion_id);
CREATE INDEX idx_loss_allocation_portion ON loss_allocation (lot_portion_id);

-- A sale can take two portions of one delivery (one at the end of a lot, one at the start of the next), so a sale is
-- unique per portion. Older rows stay unique per delivery. V31 let Postgres name the constraint, so it is looked up.
DO $$
DECLARE
    unique_name TEXT;
BEGIN
    SELECT c.conname INTO STRICT unique_name
    FROM pg_constraint c
    WHERE c.conrelid = 'sale_allocation'::regclass
      AND c.contype = 'u'
      AND c.conkey = ARRAY[
          (SELECT attnum FROM pg_attribute WHERE attrelid = 'sale_allocation'::regclass AND attname = 'sale_id'),
          (SELECT attnum FROM pg_attribute WHERE attrelid = 'sale_allocation'::regclass AND attname = 'delivery_id')
      ]::smallint[];

    EXECUTE format('ALTER TABLE sale_allocation DROP CONSTRAINT %I', unique_name);
END
$$;
CREATE UNIQUE INDEX uq_sale_allocation_delivery ON sale_allocation (sale_id, delivery_id) WHERE lot_portion_id IS NULL;
CREATE UNIQUE INDEX uq_sale_allocation_portion ON sale_allocation (sale_id, lot_portion_id)
    WHERE lot_portion_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------
-- Backfill: every existing delivery is one portion of the lot it is in, with the same kg.
INSERT INTO lot_portion (lot_id, delivery_id, quantity_kg, sold_kg, lost_kg, created_at)
SELECT d.lot_id, d.id, d.quantity_kg, d.sold_kg, d.lost_kg, d.created_at
FROM delivery d
WHERE d.lot_id IS NOT NULL AND d.quantity_kg > 0
ORDER BY d.id;

-- ---------------------------------------------------------------------------------------------------------------
-- Checks. Any difference stops the migration, and the whole of it is rolled back.
DO $$
DECLARE
    missing_grade   INTEGER;
    wrong_grade     INTEGER;
    missing_portion INTEGER;
    wrong_portion   INTEGER;
    bad_lots        INTEGER;
BEGIN
    SELECT COUNT(*) INTO missing_grade FROM delivery WHERE grade_id IS NULL;

    SELECT COUNT(*) INTO wrong_grade
    FROM delivery d JOIN lot l ON l.id = d.lot_id
    WHERE d.grade_id <> l.grade_id;

    SELECT COUNT(*) INTO missing_portion
    FROM delivery d
    WHERE d.lot_id IS NOT NULL AND d.quantity_kg > 0
      AND (SELECT COUNT(*) FROM lot_portion p WHERE p.delivery_id = d.id) <> 1;

    SELECT COUNT(*) INTO wrong_portion
    FROM delivery d JOIN lot_portion p ON p.delivery_id = d.id
    WHERE p.lot_id <> d.lot_id OR p.quantity_kg <> d.quantity_kg OR p.sold_kg <> d.sold_kg
       OR p.lost_kg <> d.lost_kg OR p.remaining_kg <> d.remaining_kg;

    -- Per lot, the portions of deliveries that are not reversed add up to the lot's batches.
    SELECT COUNT(*) INTO bad_lots
    FROM lot l
    LEFT JOIN (
        SELECT lot_id, SUM(quantity_kg) AS received, SUM(sold_kg) AS sold, SUM(lost_kg) AS lost
        FROM delivery WHERE reversed_at IS NULL GROUP BY lot_id
    ) b ON b.lot_id = l.id
    LEFT JOIN (
        SELECT p.lot_id, SUM(p.quantity_kg) AS received, SUM(p.sold_kg) AS sold, SUM(p.lost_kg) AS lost
        FROM lot_portion p JOIN delivery d ON d.id = p.delivery_id
        WHERE d.reversed_at IS NULL GROUP BY p.lot_id
    ) p ON p.lot_id = l.id
    WHERE COALESCE(b.received, 0) <> COALESCE(p.received, 0)
       OR COALESCE(b.sold, 0) <> COALESCE(p.sold, 0)
       OR COALESCE(b.lost, 0) <> COALESCE(p.lost, 0);

    IF missing_grade > 0 OR wrong_grade > 0 OR missing_portion > 0 OR wrong_portion > 0 OR bad_lots > 0 THEN
        RAISE EXCEPTION 'Fixed-size lot backfill does not match: % without grade, % wrong grade, % without one '
            'portion, % portion(s) differ, % lot(s) differ. Nothing was changed.',
            missing_grade, wrong_grade, missing_portion, wrong_portion, bad_lots;
    END IF;
END
$$;
