-- Identity verification when a member is registered. Only the provider's pseudonymous subject id is kept
-- (identity_token): never an ID number, a photo or the provider's raw response.
ALTER TABLE member
    ADD COLUMN identity_verified   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN verification_method VARCHAR(10) CHECK (verification_method IN ('OTP', 'FACE', 'MOCK')),
    ADD COLUMN verified_at         TIMESTAMPTZ,
    ADD COLUMN identity_token      VARCHAR(255);

-- One person, one membership.
CREATE UNIQUE INDEX uq_member_identity_token ON member (identity_token) WHERE identity_token IS NOT NULL;

-- Off for now, so members can still be registered without verification.
ALTER TABLE setting ADD COLUMN require_identity_verification BOOLEAN NOT NULL DEFAULT FALSE;

-- One verification attempt, from "start" until the member is registered with it. The state is random and
-- single-use; only its SHA-256 hash is stored. The nonce goes to the provider and comes back in its ID token.
CREATE TABLE identity_verification (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    state_hash          VARCHAR(64)  NOT NULL UNIQUE,
    nonce               VARCHAR(64)  NOT NULL,
    manager_id          BIGINT       NOT NULL REFERENCES manager (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,
    used_at             TIMESTAMPTZ,
    verified_at         TIMESTAMPTZ,
    verification_method VARCHAR(10)  CHECK (verification_method IN ('OTP', 'FACE', 'MOCK')),
    identity_token      VARCHAR(255),
    member_id           BIGINT       REFERENCES member (id)
);
