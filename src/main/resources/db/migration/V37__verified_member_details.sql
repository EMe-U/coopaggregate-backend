-- Verify, then fill in: the member's name and phone come from eSignet instead of being typed and compared. This only
-- adds; the columns of the old comparison stay but are no longer used.

-- The verified name and phone (+2507XXXXXXXX), kept only until the member is saved or for at most 30 minutes.
ALTER TABLE identity_verification
    ADD COLUMN verified_name  VARCHAR(150),
    ADD COLUMN verified_phone VARCHAR(13),
    ADD CONSTRAINT identity_verification_verified_details_check
        CHECK ((verified_name IS NULL) = (verified_phone IS NULL));

-- The salted hashes and wrong tries of the old comparison are no longer used. They are emptied so nothing derived from
-- earlier claims stays; a verification started before this cannot be used and is simply done again.
UPDATE identity_verification
SET salt = NULL, name_hash = NULL, birth_hash = NULL, phone_hash = NULL, attempts = 0;
