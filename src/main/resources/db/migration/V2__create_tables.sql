CREATE TABLE manager (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(150) NOT NULL UNIQUE,
    phone         VARCHAR(20),
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE member (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_code        VARCHAR(20)  NOT NULL UNIQUE,
    full_name          VARCHAR(150) NOT NULL,
    national_id        VARCHAR(20)  NOT NULL UNIQUE,
    phone              VARCHAR(20)  NOT NULL,
    address            VARCHAR(255),
    join_date          DATE         NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    preferred_language VARCHAR(5)   NOT NULL DEFAULT 'rw',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE grade (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code               VARCHAR(5)   NOT NULL UNIQUE,
    name               VARCHAR(50)  NOT NULL,
    size_description   VARCHAR(100),
    floor_price_per_kg BIGINT       NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Only one row is allowed: the identity gives the first row id 1 and any later insert fails the check.
CREATE TABLE setting (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (id = 1),
    deduction_per_kg BIGINT  NOT NULL DEFAULT 5,
    sms_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    committee_emails TEXT
);

CREATE TABLE lot (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lot_code   VARCHAR(20) NOT NULL UNIQUE,
    grade_id   BIGINT      NOT NULL REFERENCES grade (id),
    open_date  DATE        NOT NULL,
    close_date DATE,
    status     VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'SHARED'))
);

CREATE TABLE delivery (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_uuid      UUID          NOT NULL UNIQUE,
    member_id        BIGINT        NOT NULL REFERENCES member (id),
    lot_id           BIGINT        NOT NULL REFERENCES lot (id),
    delivered_at     TIMESTAMPTZ   NOT NULL,
    quantity_kg      NUMERIC(12,2) NOT NULL,
    deduction_amount BIGINT        NOT NULL DEFAULT 0,
    receipt_code     VARCHAR(30)   NOT NULL UNIQUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE loss (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lot_id      BIGINT        NOT NULL REFERENCES lot (id),
    loss_date   DATE          NOT NULL,
    quantity_kg NUMERIC(12,2) NOT NULL,
    reason      VARCHAR(10)   NOT NULL CHECK (reason IN ('ROT', 'DAMAGE', 'THEFT', 'OTHER')),
    notes       TEXT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE buyer (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    company_name VARCHAR(150),
    phone        VARCHAR(20)  NOT NULL,
    email        VARCHAR(150),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE buyer_request (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference_code VARCHAR(20)   NOT NULL UNIQUE,
    buyer_id       BIGINT        NOT NULL REFERENCES buyer (id),
    grade_id       BIGINT        NOT NULL REFERENCES grade (id),
    quantity_kg    NUMERIC(12,2) NOT NULL,
    preferred_date DATE,
    message        TEXT,
    status         VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    manager_note   TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE sale (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sale_code         VARCHAR(20)   NOT NULL UNIQUE,
    lot_id            BIGINT        NOT NULL REFERENCES lot (id),
    buyer_id          BIGINT        NOT NULL REFERENCES buyer (id),
    buyer_request_id  BIGINT        UNIQUE REFERENCES buyer_request (id),
    sale_date         DATE          NOT NULL,
    quantity_kg       NUMERIC(12,2) NOT NULL,
    price_per_kg      BIGINT        NOT NULL,
    total_amount      BIGINT        NOT NULL,
    below_floor_price BOOLEAN       NOT NULL DEFAULT FALSE,
    approved          BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE lot_share (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lot_id           BIGINT        NOT NULL REFERENCES lot (id),
    member_id        BIGINT        NOT NULL REFERENCES member (id),
    member_kg        NUMERIC(12,2) NOT NULL,
    lot_kg           NUMERIC(12,2) NOT NULL,
    share_amount     BIGINT        NOT NULL,
    deduction_amount BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (lot_id, member_id)
);

CREATE TABLE produce_payment (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id    BIGINT      NOT NULL REFERENCES member (id),
    lot_id       BIGINT      NOT NULL REFERENCES lot (id),
    payment_date DATE        NOT NULL,
    amount       BIGINT      NOT NULL,
    method       VARCHAR(15) NOT NULL CHECK (method IN ('CASH', 'MOBILE_MONEY', 'BANK')),
    reference_no VARCHAR(50),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entry (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    manager_id          BIGINT        NOT NULL REFERENCES manager (id),
    reverses_id         BIGINT        UNIQUE REFERENCES ledger_entry (id),
    entry_date          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    entry_type          VARCHAR(10)   NOT NULL CHECK (entry_type IN ('DELIVERY', 'LOSS', 'SALE', 'SHARE', 'PAYMENT', 'REVERSAL')),
    related_entity_type VARCHAR(30),
    related_entity_id   BIGINT,
    quantity_kg         NUMERIC(12,2),
    amount              BIGINT,
    reason              TEXT,
    previous_hash       VARCHAR(64),
    current_hash        VARCHAR(64)   NOT NULL UNIQUE
);

CREATE TABLE dispute (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_entry_id BIGINT      REFERENCES ledger_entry (id),
    member_id       BIGINT      NOT NULL REFERENCES member (id),
    raised_via      VARCHAR(10) NOT NULL CHECK (raised_via IN ('SMS', 'IN_PERSON')),
    description     TEXT        NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution      TEXT,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sms_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id  BIGINT      NOT NULL REFERENCES member (id),
    phone      VARCHAR(20) NOT NULL,
    message    TEXT        NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    sent_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_member_id ON delivery (member_id);
CREATE INDEX idx_delivery_lot_id ON delivery (lot_id);
CREATE INDEX idx_produce_payment_member_id ON produce_payment (member_id);
CREATE INDEX idx_ledger_entry_entry_date ON ledger_entry (entry_date);
