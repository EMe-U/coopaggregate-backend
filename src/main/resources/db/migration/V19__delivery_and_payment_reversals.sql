-- A reversal must change the business data too, not only add a ledger entry. A reversed delivery or payment
-- stays in the table for the history but is no longer counted in any total.
ALTER TABLE delivery
    ADD COLUMN reversed_at              TIMESTAMPTZ,
    ADD COLUMN reversal_reason          TEXT,
    ADD COLUMN reversal_ledger_entry_id BIGINT REFERENCES ledger_entry (id),
    -- A correction replaces exactly one delivery, so the link is unique.
    ADD COLUMN corrects_id              BIGINT UNIQUE REFERENCES delivery (id);

ALTER TABLE produce_payment
    ADD COLUMN reversed_at              TIMESTAMPTZ,
    ADD COLUMN reversal_reason          TEXT,
    ADD COLUMN reversal_ledger_entry_id BIGINT REFERENCES ledger_entry (id);
