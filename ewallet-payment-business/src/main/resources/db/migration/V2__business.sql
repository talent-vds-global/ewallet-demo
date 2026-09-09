-- paymentdb — Stage C: bảng & cột phục vụ nghiệp vụ thật.
-- Nguồn: docs/specs/00-domain-and-conventions.md §10.2

-- Giao dịch nghiệp vụ (2 pha: AUTHORIZED -> CAPTURED, hoặc -> REVERSED / HELD / REJECTED).
CREATE TABLE payment_transactions (
    txn_id            UUID PRIMARY KEY,
    order_id          UUID         NOT NULL,
    customer_id       VARCHAR(64)  NOT NULL,
    payment_type      VARCHAR(16)  NOT NULL,   -- TOP_UP | BILL | TELCO | P2P | REFUND
    amount            BIGINT       NOT NULL,
    fee               BIGINT       NOT NULL DEFAULT 0,
    currency          VARCHAR(8)   NOT NULL DEFAULT 'VND',
    amount_vnd        BIGINT       NOT NULL,   -- quy đổi theo R-CURRENCY-01
    source_account_id UUID,
    dest_account_id   UUID,
    status            VARCHAR(16)  NOT NULL,   -- AUTHORIZED | CAPTURED | REVERSED | REJECTED | HELD
    reason_code       VARCHAR(32),
    partner_ref       VARCHAR(64),
    partner_code      VARCHAR(32),             -- rỗng nếu không qua đối tác
    counterparty_customer_id VARCHAR(64),      -- người nhận, chỉ P2P
    reversed_txn_id   UUID,                    -- trỏ về txn gốc nếu đây là bút toán bù trừ
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_order ON payment_transactions(order_id);
CREATE INDEX idx_txn_customer_date ON payment_transactions(customer_id, created_at);

-- Bút toán kép: thêm loại và trạng thái (PENDING khi authorize -> POSTED khi capture).
ALTER TABLE ledger_entries
    ADD COLUMN entry_type VARCHAR(16) NOT NULL DEFAULT 'PAYMENT',  -- PAYMENT | FEE | REFUND
    ADD COLUMN status     VARCHAR(12) NOT NULL DEFAULT 'PENDING';  -- PENDING | POSTED | REVERSED

-- Tỉ giá quy đổi về VND (R-CURRENCY-01, R-FX-01).
CREATE TABLE fx_rates (
    currency    VARCHAR(8) PRIMARY KEY,
    rate_to_vnd BIGINT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
