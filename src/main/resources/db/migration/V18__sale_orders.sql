-- One buyer order can take potatoes from several lots. Each lot still gets its own sale row, so its money
-- stays separate for sharing; the order groups those rows and holds the client UUID of the whole order.
CREATE TABLE sale_order (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_code  VARCHAR(20) NOT NULL UNIQUE,
    client_uuid UUID        NOT NULL UNIQUE,
    buyer_id    BIGINT      NOT NULL REFERENCES buyer (id),
    sale_date   DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE sale ADD COLUMN sale_order_id BIGINT REFERENCES sale_order (id);
CREATE INDEX idx_sale_sale_order_id ON sale (sale_order_id);
