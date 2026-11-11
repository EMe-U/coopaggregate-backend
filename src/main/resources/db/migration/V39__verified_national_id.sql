-- The national ID released by the identity check (the claim MOSIP_NATIONAL_ID_CLAIM names), kept like the other verified
-- details (V37, V38): only until the member is saved or for at most 30 minutes, then cleared. NULL when the claim is
-- not asked for, not released or not allowed by the member. This only adds a column; no row is changed.
ALTER TABLE identity_verification ADD COLUMN verified_national_id VARCHAR(20);
