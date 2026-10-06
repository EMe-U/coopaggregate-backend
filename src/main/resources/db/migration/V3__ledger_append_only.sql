-- The ledger history must be permanent. Mistakes are fixed by adding REVERSAL rows, never by editing or deleting.
CREATE FUNCTION prevent_ledger_entry_change() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entry_append_only
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_entry_change();
