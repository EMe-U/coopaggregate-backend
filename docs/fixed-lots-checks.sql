-- Fixed-size lots: read-only checks.
--
-- Run in the Neon SQL Editor on a TEST branch, after V36. Every statement is a SELECT: nothing is changed, and no
-- table (temporary or not) is created. Run the whole file, or one block at a time; each block returns its own result.
-- A check passes when its true/false column is true. The lists under the checks show the rows to look at when one
-- is false (they are empty when everything is right).
--
-- Terms: a portion is the part of one delivery that is in one lot (lot_portion). Portions of reversed deliveries stay
-- as a record but no longer count. Lots from before fixed-size lots have no capacity (capacity_kg is NULL).

-- 1. Summary: one row, every check true -------------------------------------------------------------------------
WITH lot_kg AS (
    SELECT l.id, l.grade_id, l.status, l.capacity_kg,
           COALESCE(SUM(p.quantity_kg), 0) AS placed_kg,
           COALESCE(SUM(p.quantity_kg) FILTER (WHERE d.reversed_at IS NULL), 0) AS in_lot_kg
    FROM lot l
    LEFT JOIN lot_portion p ON p.lot_id = l.id
    LEFT JOIN delivery d ON d.id = p.delivery_id
    GROUP BY l.id, l.grade_id, l.status, l.capacity_kg
),
delivery_totals AS (
    SELECT d.id, d.sold_kg, d.lost_kg,
           (SELECT COALESCE(SUM(p.sold_kg), 0) FROM lot_portion p WHERE p.delivery_id = d.id) AS portions_sold,
           (SELECT COALESCE(SUM(p.lost_kg), 0) FROM lot_portion p WHERE p.delivery_id = d.id) AS portions_lost,
           (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM sale_allocation a WHERE a.delivery_id = d.id) AS allocated_sold,
           (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM loss_allocation a WHERE a.delivery_id = d.id) AS allocated_lost
    FROM delivery d
),
sale_totals AS (
    SELECT s.id, s.quantity_kg, s.total_amount,
           (SELECT SUM(a.quantity_kg) FROM sale_allocation a WHERE a.sale_id = s.id) AS allocated_kg,
           (SELECT SUM(a.gross_rwf) FROM sale_allocation a WHERE a.sale_id = s.id) AS gross_rwf,
           (SELECT SUM(a.deduction_rwf) FROM sale_allocation a WHERE a.sale_id = s.id) AS deduction_rwf,
           (SELECT COUNT(*) FROM sale_allocation a WHERE a.sale_id = s.id AND a.gross_rwf IS NULL) AS uncredited_lines,
           (SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entry e JOIN sale_allocation a
                ON e.related_entity_type = 'SALE_ALLOCATION' AND e.related_entity_id = a.id
            WHERE a.sale_id = s.id AND e.entry_type = 'SALE_CREDIT') AS credited_net_rwf,
           (SELECT e.amount FROM ledger_entry e
            WHERE e.entry_type = 'SALE' AND e.related_entity_type = 'SALE' AND e.related_entity_id = s.id) AS ledger_amount,
           (SELECT e.quantity_kg FROM ledger_entry e
            WHERE e.entry_type = 'SALE' AND e.related_entity_type = 'SALE' AND e.related_entity_id = s.id) AS ledger_kg
    FROM sale s
)
SELECT
    -- Lots
    (SELECT COUNT(*) FROM lot_kg WHERE capacity_kg IS NOT NULL AND in_lot_kg > capacity_kg) = 0
        AS no_lot_over_capacity,
    (SELECT COUNT(*) FROM lot_kg WHERE status = 'FILLING' AND in_lot_kg >= capacity_kg) = 0
        AS every_filling_lot_has_space,
    (SELECT COUNT(*) FROM lot_kg WHERE status IN ('FULL', 'SOLD') AND capacity_kg IS NOT NULL
        AND placed_kg < capacity_kg) = 0
        AS every_full_lot_was_filled_to_capacity,
    (SELECT COUNT(*) FROM (SELECT grade_id FROM lot WHERE status = 'FILLING' GROUP BY grade_id HAVING COUNT(*) > 1) x) = 0
        AS at_most_one_filling_lot_per_grade,
    (SELECT COUNT(*) FROM lot WHERE status IN ('FILLING', 'FULL') AND capacity_kg IS NULL) = 0
        AS every_fixed_size_lot_has_a_capacity,
    -- Portions
    (SELECT COUNT(*) FROM lot_portion WHERE remaining_kg < 0 OR sold_kg < 0 OR lost_kg < 0) = 0
        AS no_portion_overdrawn,
    (SELECT COUNT(*) FROM lot_portion p JOIN delivery d ON d.id = p.delivery_id JOIN lot l ON l.id = p.lot_id
        WHERE l.grade_id <> d.grade_id) = 0
        AS every_portion_in_a_lot_of_its_grade,
    (SELECT COUNT(*) FROM delivery d WHERE d.reversed_at IS NULL AND d.lot_id IS NULL
        AND d.confirmation_status = 'PENDING'
        AND EXISTS (SELECT 1 FROM lot_portion p WHERE p.delivery_id = d.id)) = 0
        AS no_waiting_delivery_in_a_lot,
    (SELECT COUNT(*) FROM delivery d WHERE d.reversed_at IS NULL AND d.lot_id IS NULL
        AND d.confirmation_status IN ('CONFIRMED', 'DISPUTED')
        AND EXISTS (SELECT 1 FROM lot_portion p WHERE p.delivery_id = d.id)
        AND d.quantity_kg <> (SELECT SUM(p.quantity_kg) FROM lot_portion p WHERE p.delivery_id = d.id)) = 0
        AS every_delivery_in_lots_for_all_its_kg,
    -- Deliveries against their portions and allocations
    (SELECT COUNT(*) FROM delivery_totals WHERE sold_kg <> portions_sold) = 0 AS sold_kg_match_portions,
    (SELECT COUNT(*) FROM delivery_totals WHERE lost_kg <> portions_lost) = 0 AS lost_kg_match_portions,
    (SELECT COUNT(*) FROM delivery_totals WHERE sold_kg <> allocated_sold) = 0 AS sold_kg_match_sale_allocations,
    (SELECT COUNT(*) FROM delivery_totals WHERE lost_kg <> allocated_lost) = 0 AS lost_kg_match_loss_allocations,
    -- Losses
    (SELECT COUNT(*) FROM lot l
        WHERE (SELECT COALESCE(SUM(x.quantity_kg), 0) FROM loss x WHERE x.lot_id = l.id)
           <> (SELECT COALESCE(SUM(p.lost_kg), 0) FROM lot_portion p JOIN delivery d ON d.id = p.delivery_id
               WHERE p.lot_id = l.id AND d.reversed_at IS NULL)) = 0
        AS every_lot_carries_exactly_its_losses,
    (SELECT COUNT(*) FROM loss x
        WHERE x.quantity_kg <> (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM loss_allocation a WHERE a.loss_id = x.id)) = 0
        AS every_loss_fully_allocated,
    -- Sales
    (SELECT COUNT(*) FROM sale_totals WHERE allocated_kg IS DISTINCT FROM quantity_kg) = 0
        AS every_sale_kg_match_allocations,
    (SELECT COUNT(*) FROM sale_totals WHERE ledger_kg IS DISTINCT FROM quantity_kg
        OR ledger_amount IS DISTINCT FROM total_amount) = 0
        AS every_sale_matches_its_sale_entry,
    (SELECT COUNT(*) FROM sale_totals WHERE uncredited_lines = 0 AND gross_rwf IS DISTINCT FROM ledger_amount) = 0
        AS every_credited_sale_gross_match_sale_entry,
    (SELECT COUNT(*) FROM sale_totals WHERE uncredited_lines = 0
        AND credited_net_rwf + deduction_rwf IS DISTINCT FROM ledger_amount) = 0
        AS every_credited_sale_credits_plus_deduction_match_sale_entry,
    (SELECT COUNT(*) FROM sale_allocation a WHERE a.gross_rwf IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ledger_entry e WHERE e.entry_type = 'SALE_CREDIT' AND e.related_entity_type = 'SALE_ALLOCATION'
            AND e.related_entity_id = a.id)) = 0
        AS every_credited_line_has_one_sale_credit;

-- 2. Each lot: capacity, kg in it, shortfall or space, and whether it is right -----------------------------------
SELECT l.lot_code, l.status, l.capacity_kg,
       COALESCE(SUM(p.quantity_kg), 0) AS placed_kg,
       COALESCE(SUM(p.quantity_kg) FILTER (WHERE d.reversed_at IS NULL), 0) AS in_lot_kg,
       COALESCE(SUM(p.sold_kg) FILTER (WHERE d.reversed_at IS NULL), 0) AS sold_kg,
       COALESCE(SUM(p.lost_kg) FILTER (WHERE d.reversed_at IS NULL), 0) AS lost_kg,
       COALESCE(SUM(p.remaining_kg) FILTER (WHERE d.reversed_at IS NULL), 0) AS remaining_kg,
       CASE WHEN l.status = 'FILLING'
            THEN l.capacity_kg - COALESCE(SUM(p.quantity_kg) FILTER (WHERE d.reversed_at IS NULL), 0) END AS space_left_kg,
       CASE WHEN l.status IN ('FULL', 'SOLD') AND l.capacity_kg IS NOT NULL
            THEN GREATEST(l.capacity_kg - COALESCE(SUM(p.quantity_kg) FILTER (WHERE d.reversed_at IS NULL), 0), 0)
       END AS shortfall_kg,
       COUNT(DISTINCT d.member_id) FILTER (WHERE d.reversed_at IS NULL) AS members,
       l.capacity_kg IS NULL
           OR (COALESCE(SUM(p.quantity_kg) FILTER (WHERE d.reversed_at IS NULL), 0) <= l.capacity_kg
               AND (l.status <> 'FILLING'
                    OR COALESCE(SUM(p.quantity_kg) FILTER (WHERE d.reversed_at IS NULL), 0) < l.capacity_kg)
               AND (l.status NOT IN ('FULL', 'SOLD') OR COALESCE(SUM(p.quantity_kg), 0) >= l.capacity_kg))
           AS ok
FROM lot l
LEFT JOIN lot_portion p ON p.lot_id = l.id
LEFT JOIN delivery d ON d.id = p.delivery_id
GROUP BY l.id, l.lot_code, l.status, l.capacity_kg
ORDER BY l.grade_id, l.id;

-- 3. Deliveries whose sold or lost kg do not match their portions or allocations (empty when right) ---------------
SELECT d.receipt_code, d.sold_kg, d.lost_kg,
       (SELECT COALESCE(SUM(p.sold_kg), 0) FROM lot_portion p WHERE p.delivery_id = d.id) AS portions_sold_kg,
       (SELECT COALESCE(SUM(p.lost_kg), 0) FROM lot_portion p WHERE p.delivery_id = d.id) AS portions_lost_kg,
       (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM sale_allocation a WHERE a.delivery_id = d.id) AS sale_allocations_kg,
       (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM loss_allocation a WHERE a.delivery_id = d.id) AS loss_allocations_kg
FROM delivery d
WHERE d.sold_kg <> (SELECT COALESCE(SUM(p.sold_kg), 0) FROM lot_portion p WHERE p.delivery_id = d.id)
   OR d.lost_kg <> (SELECT COALESCE(SUM(p.lost_kg), 0) FROM lot_portion p WHERE p.delivery_id = d.id)
   OR d.sold_kg <> (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM sale_allocation a WHERE a.delivery_id = d.id)
   OR d.lost_kg <> (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM loss_allocation a WHERE a.delivery_id = d.id)
ORDER BY d.id
LIMIT 100;

-- 4. Each sale against its allocations, its credits and its SALE ledger entry -----------------------------------
-- Sales made before sales by grade have no credits (credited is false); their money was shared by lot instead.
SELECT s.sale_code, s.quantity_kg, s.total_amount,
       totals.allocated_kg, totals.gross_rwf, totals.deduction_rwf, totals.credited_net_rwf,
       e.quantity_kg AS sale_entry_kg, e.amount AS sale_entry_amount,
       totals.uncredited_lines = 0 AS credited,
       -- A sale without its SALE entry is not ok either (the comparisons with a missing entry are false).
       COALESCE(totals.allocated_kg = s.quantity_kg AND e.quantity_kg = s.quantity_kg AND e.amount = s.total_amount
           AND (totals.uncredited_lines > 0
                OR (totals.gross_rwf = e.amount AND totals.credited_net_rwf + totals.deduction_rwf = e.amount)),
           false) AS ok
FROM sale s
LEFT JOIN ledger_entry e ON e.entry_type = 'SALE' AND e.related_entity_type = 'SALE' AND e.related_entity_id = s.id
CROSS JOIN LATERAL (
    SELECT COALESCE(SUM(a.quantity_kg), 0) AS allocated_kg,
           SUM(a.gross_rwf) AS gross_rwf,
           SUM(a.deduction_rwf) AS deduction_rwf,
           COUNT(*) FILTER (WHERE a.gross_rwf IS NULL) AS uncredited_lines,
           (SELECT COALESCE(SUM(c.amount), 0) FROM ledger_entry c JOIN sale_allocation a2
                ON c.related_entity_type = 'SALE_ALLOCATION' AND c.related_entity_id = a2.id
            WHERE a2.sale_id = s.id AND c.entry_type = 'SALE_CREDIT') AS credited_net_rwf
    FROM sale_allocation a
    WHERE a.sale_id = s.id
) totals
ORDER BY s.id DESC;

-- 5. Ledger entries by type ---------------------------------------------------------------------------------------
SELECT entry_type, COUNT(*) AS entries, COALESCE(SUM(quantity_kg), 0) AS kg, COALESCE(SUM(amount), 0) AS amount_rwf
FROM ledger_entry
GROUP BY entry_type
ORDER BY entry_type;

-- 6. Lots by status (OPEN and CLOSED are lots from before fixed-size lots) ----------------------------------------
SELECT status, capacity_kg IS NULL AS older_lot, COUNT(*) AS lots
FROM lot
GROUP BY status, capacity_kg IS NULL
ORDER BY older_lot DESC, status;
