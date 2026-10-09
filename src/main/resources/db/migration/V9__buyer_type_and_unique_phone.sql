-- Optional kind of buyer, for example trader, school or restaurant. Free text until the list is agreed.
ALTER TABLE buyer ADD COLUMN type VARCHAR(30);

-- A phone number belongs to one buyer, the same rule as for members.
ALTER TABLE buyer ADD CONSTRAINT uq_buyer_phone UNIQUE (phone);
