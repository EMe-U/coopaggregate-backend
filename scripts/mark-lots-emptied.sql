-- Sets lots A-2026-01, A-2026-02, A-2026-03 and A-2026-04 from SOLD to EMPTIED.
--
-- Why: these full lots had every delivery reversed or corrected before anything was sold from them, and the old rule
-- marked them SOLD although they hold 0 kg, sold 0 kg and earned no money. EMPTIED (added by migration V40) is the
-- status for exactly that case.
--
-- Run it only AFTER the backend with V40 is deployed (Flyway must have run V40 first; the script checks this).
--
-- It changes ONLY the status column of those 4 lot rows. No delivery, sale, share, ledger or audit row is touched.
-- Everything runs in one transaction; if any check fails, an error is raised and nothing is changed.
--
-- TWO STEPS:
--   1. Rehearse: run it as it is (apply_changes := FALSE below). It runs every check and the update, prints what it
--      found, then raises "REHEARSAL ONLY" so everything is rolled back. Nothing is changed.
--   2. Apply: change apply_changes to TRUE, and run it again. It commits only if every check passes again.
--
-- Works in psql and in the Neon SQL editor (plain SQL, no psql commands).

BEGIN;

DO $$
DECLARE
    apply_changes CONSTANT BOOLEAN := FALSE;  -- step 2: set to TRUE
    lot_codes     CONSTANT TEXT[]  := ARRAY['A-2026-01', 'A-2026-02', 'A-2026-03', 'A-2026-04'];

    lot_ids          BIGINT[];
    found_lots       INTEGER;
    not_sold         TEXT;
    not_fixed        TEXT;
    live_deliveries  INTEGER;
    sold_kg          NUMERIC;
    allocations      INTEGER;
    lot_sales        INTEGER;
    shares           INTEGER;
    changed          INTEGER;
    other_changes    INTEGER;
BEGIN
    -- 0. V40 is applied: the database accepts EMPTIED.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'lot'::regclass AND conname = 'lot_status_check'
          AND pg_get_constraintdef(oid) LIKE '%EMPTIED%'
    ) THEN
        RAISE EXCEPTION 'Stopped: the database does not accept EMPTIED yet. Deploy the backend with V40 first. '
            'Nothing was changed.';
    END IF;

    -- 1. Exactly the 4 lots exist; lock them so nothing changes them meanwhile.
    PERFORM 1 FROM lot WHERE lot_code = ANY (lot_codes) FOR UPDATE;
    SELECT array_agg(id ORDER BY id), COUNT(*) INTO lot_ids, found_lots FROM lot WHERE lot_code = ANY (lot_codes);
    IF found_lots <> 4 THEN
        RAISE EXCEPTION 'Stopped: expected 4 lots, found %. Nothing was changed.', found_lots;
    END IF;

    -- 2. They are all SOLD now, and are fixed-size lots (they have a capacity).
    SELECT string_agg(lot_code || ' is ' || status, ', ') INTO not_sold
    FROM lot WHERE id = ANY (lot_ids) AND status <> 'SOLD';
    IF not_sold IS NOT NULL THEN
        RAISE EXCEPTION 'Stopped: not SOLD: %. Nothing was changed.', not_sold;
    END IF;
    SELECT string_agg(lot_code, ', ') INTO not_fixed FROM lot WHERE id = ANY (lot_ids) AND capacity_kg IS NULL;
    IF not_fixed IS NOT NULL THEN
        RAISE EXCEPTION 'Stopped: older lot(s) without a capacity: %. Nothing was changed.', not_fixed;
    END IF;

    -- 3. Every delivery in them is reversed: through a lot portion, or as the lot of its own (older deliveries).
    SELECT COUNT(DISTINCT d.id) INTO live_deliveries
    FROM delivery d
    WHERE d.reversed_at IS NULL
      AND (d.lot_id = ANY (lot_ids)
           OR EXISTS (SELECT 1 FROM lot_portion p WHERE p.delivery_id = d.id AND p.lot_id = ANY (lot_ids)));
    IF live_deliveries > 0 THEN
        RAISE EXCEPTION 'Stopped: % delivery(ies) in these lots are not reversed. Nothing was changed.',
            live_deliveries;
    END IF;

    -- 4. Nothing was ever sold from them: no sold kg, no sale_allocation rows, no sale made on the lot itself.
    SELECT COALESCE(SUM(p.sold_kg), 0) INTO sold_kg FROM lot_portion p WHERE p.lot_id = ANY (lot_ids);
    SELECT COUNT(*) INTO allocations
    FROM sale_allocation a
    WHERE a.lot_portion_id IN (SELECT p.id FROM lot_portion p WHERE p.lot_id = ANY (lot_ids))
       OR a.delivery_id IN (SELECT p.delivery_id FROM lot_portion p WHERE p.lot_id = ANY (lot_ids))
       OR a.delivery_id IN (SELECT d.id FROM delivery d WHERE d.lot_id = ANY (lot_ids));
    SELECT COUNT(*) INTO lot_sales FROM sale s WHERE s.lot_id = ANY (lot_ids);
    IF sold_kg <> 0 OR allocations > 0 OR lot_sales > 0 THEN
        RAISE EXCEPTION 'Stopped: these lots have sales (% kg sold, % sale_allocation row(s), % sale(s) on the '
            'lot). Nothing was changed.', sold_kg, allocations, lot_sales;
    END IF;

    -- 5. They were never shared.
    SELECT COUNT(*) INTO shares FROM lot_share WHERE lot_id = ANY (lot_ids);
    IF shares > 0 THEN
        RAISE EXCEPTION 'Stopped: % lot_share row(s) for these lots. Nothing was changed.', shares;
    END IF;

    RAISE NOTICE 'All checks passed for lots % (ids %).', lot_codes, lot_ids;

    -- 6. The change: only these 4 rows, only from SOLD.
    UPDATE lot SET status = 'EMPTIED' WHERE id = ANY (lot_ids) AND status = 'SOLD';
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 4 THEN
        RAISE EXCEPTION 'Stopped: % row(s) would change instead of 4. Nothing was changed.', changed;
    END IF;

    -- 7. After: the 4 lots are EMPTIED. The UPDATE is limited to their ids, so no other row can have changed.
    SELECT COUNT(*) INTO other_changes FROM lot WHERE id = ANY (lot_ids) AND status <> 'EMPTIED';
    IF other_changes > 0 THEN
        RAISE EXCEPTION 'Stopped: % of the lots is not EMPTIED after the update. Nothing was changed.',
            other_changes;
    END IF;
    RAISE NOTICE '% lot(s) set to EMPTIED.', changed;

    IF NOT apply_changes THEN
        RAISE EXCEPTION 'REHEARSAL ONLY: every check passed and 4 lots would be set to EMPTIED. Rolled back, nothing '
            'was changed. Set apply_changes to TRUE to apply.';
    END IF;
END
$$;

COMMIT;

-- After step 2, this should list the 4 lots as EMPTIED:
-- SELECT lot_code, status, capacity_kg, close_date FROM lot WHERE lot_code LIKE 'A-2026-0%' ORDER BY lot_code;
