-- The phone number buyers see on the public pages. Stored as +2507XXXXXXXX; empty when not set.
ALTER TABLE setting ADD COLUMN cooperative_phone VARCHAR(20);
