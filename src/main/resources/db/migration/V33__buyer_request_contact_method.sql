-- How the cooperative should contact a buyer who sent a request from the public page: by a phone call (the default)
-- or on WhatsApp, on the phone number of the request or on another one. Only adds columns and constraints that
-- the existing rows already satisfy: every existing request becomes CALL and has no WhatsApp number.
ALTER TABLE buyer_request
    ADD COLUMN contact_method  VARCHAR(10) NOT NULL DEFAULT 'CALL',
    ADD COLUMN whatsapp_number VARCHAR(20);

ALTER TABLE buyer_request ADD CONSTRAINT buyer_request_contact_method_check
    CHECK (contact_method IN ('CALL', 'WHATSAPP'));

-- A WhatsApp request always has the number to use (the main phone when the buyer gave none); a call request has none.
ALTER TABLE buyer_request ADD CONSTRAINT buyer_request_whatsapp_number_check
    CHECK ((contact_method = 'WHATSAPP' AND whatsapp_number IS NOT NULL)
        OR (contact_method = 'CALL' AND whatsapp_number IS NULL));
