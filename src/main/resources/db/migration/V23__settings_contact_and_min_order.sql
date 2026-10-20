-- Contact details shown to buyers, and the smallest order a buyer can ask for.
-- The committee emails stay in the existing committee_emails column, comma-separated.
ALTER TABLE setting
    ADD COLUMN cooperative_email VARCHAR(150),
    ADD COLUMN location          VARCHAR(150),
    ADD COLUMN min_order_kg      NUMERIC(12,2) NOT NULL DEFAULT 100;
