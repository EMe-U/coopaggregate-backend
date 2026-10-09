-- National ID is optional when registering a member. It stays UNIQUE; Postgres allows many NULLs.
ALTER TABLE member ALTER COLUMN national_id DROP NOT NULL;

-- A phone number belongs to one member, because SMS notifications are sent to it.
ALTER TABLE member ADD CONSTRAINT uq_member_phone UNIQUE (phone);

-- Numbers for generated member codes (M-0001, M-0002, ...). A sequence is safe when two
-- members are created at the same time, unlike counting rows.
CREATE SEQUENCE member_code_seq;
SELECT setval('member_code_seq', (SELECT COUNT(*) FROM member) + 1, false);
