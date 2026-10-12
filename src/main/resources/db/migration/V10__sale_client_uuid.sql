-- Created by the client, so a sale that is sent twice (a retry or a double tap) is saved only once.
-- NOT NULL is safe here because no sales could be recorded before this version.
ALTER TABLE sale ADD COLUMN client_uuid UUID NOT NULL UNIQUE;
