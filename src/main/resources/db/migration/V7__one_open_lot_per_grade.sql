-- Deliveries go to the open lot of their grade, so a grade must never have two open lots.
CREATE UNIQUE INDEX uq_lot_one_open_per_grade ON lot (grade_id) WHERE status = 'OPEN';
