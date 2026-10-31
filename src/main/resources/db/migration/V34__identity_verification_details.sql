-- eSignet identity verification (the tables and columns come from V27). This only adds; nothing that exists is
-- changed except the list of allowed verification methods, which gains PASSWORD (eSignet's password login).

-- The code verifier of PKCE. It is cleared as soon as the state is used.
ALTER TABLE identity_verification
    ADD COLUMN code_verifier VARCHAR(128),
    -- Salted hashes of the provider's name, birth date and phone, never the values. Cleared once the member is saved,
    -- the verification is spent or it has expired.
    ADD COLUMN salt          VARCHAR(64),
    ADD COLUMN name_hash     VARCHAR(64),
    ADD COLUMN birth_hash    VARCHAR(64),
    ADD COLUMN phone_hash    VARCHAR(64),
    -- Registrations refused because the details did not match; the verification is spent after three.
    ADD COLUMN attempts      INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0);

ALTER TABLE member DROP CONSTRAINT member_verification_method_check;
ALTER TABLE member ADD CONSTRAINT member_verification_method_check
    CHECK (verification_method IN ('OTP', 'FACE', 'PASSWORD', 'MOCK'));

ALTER TABLE identity_verification DROP CONSTRAINT identity_verification_verification_method_check;
ALTER TABLE identity_verification ADD CONSTRAINT identity_verification_verification_method_check
    CHECK (verification_method IN ('OTP', 'FACE', 'PASSWORD', 'MOCK'));
