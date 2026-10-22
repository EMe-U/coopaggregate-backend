-- Kinyarwanda name and public details shown to buyers. Size info uses the existing size_description column.
ALTER TABLE grade
    ADD COLUMN name_rw     VARCHAR(50),
    ADD COLUMN description TEXT,
    ADD COLUMN packaging   VARCHAR(100);
