-- Member numbers are written M-0001. Numbers created as MEM-0001 keep their digits: MEM-0003 becomes M-0003.
-- M- and MEM- numbers came from the same sequence, so no renamed number can clash with an existing one.
UPDATE member
SET member_code = 'M-' || SUBSTRING(member_code FROM 5)
WHERE member_code LIKE 'MEM-%';

-- New numbers are now the highest existing number plus one, so the sequence from V6 is no longer used.
DROP SEQUENCE member_code_seq;
