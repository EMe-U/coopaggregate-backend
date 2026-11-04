-- Every detail eSignet can release is kept, not just the name and phone. This only adds columns; no row is changed.

-- The optional verified details, kept like verified_name and verified_phone (V37): only until the member is saved or
-- for at most 30 minutes, then cleared. Each is NULL when eSignet did not release it.
ALTER TABLE identity_verification
    ADD COLUMN verified_birthdate DATE,
    ADD COLUMN verified_gender    VARCHAR(20),
    ADD COLUMN verified_email     VARCHAR(254),
    ADD COLUMN verified_address   VARCHAR(500);

-- The member's own details. The address column already exists; it is widened from 255 to 500 characters so a verified
-- address fits (making a VARCHAR longer keeps every value and does not rewrite the table). Each flag says the detail
-- came from the identity check, like identity_verified (V27) does for the name and phone.
ALTER TABLE member
    ADD COLUMN birth_date          DATE,
    ADD COLUMN gender              VARCHAR(20),
    ADD COLUMN email               VARCHAR(254),
    ADD COLUMN birth_date_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN gender_verified     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verified      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN address_verified    BOOLEAN NOT NULL DEFAULT FALSE,
    ALTER COLUMN address TYPE VARCHAR(500);
