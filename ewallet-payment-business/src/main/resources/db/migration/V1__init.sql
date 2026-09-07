-- paymentdb — Stage B: khung bảng. Cột / seed nghiệp vụ bổ sung ở Stage C.
CREATE TABLE accounts (
    id           UUID PRIMARY KEY,
    customer_id  VARCHAR(64)  NOT NULL,
    account_type VARCHAR(16)  NOT NULL,          -- WALLET | PARTNER_SETTLE
    currency     VARCHAR(8)   NOT NULL DEFAULT 'VND',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE account_balances (
    account_id   UUID PRIMARY KEY REFERENCES accounts(id),
    balance      BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id           BIGSERIAL PRIMARY KEY,
    txn_id       UUID         NOT NULL,
    account_id   UUID         NOT NULL REFERENCES accounts(id),
    direction    VARCHAR(6)   NOT NULL,          -- DEBIT | CREDIT
    amount       BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_txn ON ledger_entries(txn_id);

CREATE TABLE daily_usage (
    customer_id  VARCHAR(64)  NOT NULL,
    usage_date   DATE         NOT NULL,
    total_amount BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (customer_id, usage_date)
);

CREATE TABLE limit_config (
    id           VARCHAR(48) PRIMARY KEY,        -- vd DAILY_TRANSFER_LIMIT
    limit_value  BIGINT       NOT NULL,
    currency     VARCHAR(8)   NOT NULL DEFAULT 'VND',
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Stage B seed tối thiểu để /admin/ping có dữ liệu.
INSERT INTO limit_config (id, limit_value) VALUES ('DAILY_TRANSFER_LIMIT', 50000000);
