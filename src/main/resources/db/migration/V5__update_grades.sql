-- The manager sets floor prices later in Settings. No floor price means no below-floor warning.
ALTER TABLE grade ALTER COLUMN floor_price_per_kg DROP NOT NULL;

-- The cooperative sorts potatoes by size by eye, so grades have no exact size range.
UPDATE grade SET name = 'Big', floor_price_per_kg = NULL, size_description = NULL WHERE code = 'A';
UPDATE grade SET name = 'Normal', floor_price_per_kg = NULL, size_description = NULL WHERE code = 'B';

INSERT INTO grade (code, name, size_description, floor_price_per_kg, active) VALUES
    ('C', 'Small', NULL, NULL, TRUE);
