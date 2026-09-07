-- orderdb — Stage B: khung bảng. Cột nghiệp vụ bổ sung ở Stage C khi cài saga.
CREATE TABLE payment_orders (
    id            UUID PRIMARY KEY,
    customer_id   VARCHAR(64)  NOT NULL,
    payment_type  VARCHAR(16)  NOT NULL,          -- TOP_UP | BILL | P2P | REFUND
    amount        BIGINT       NOT NULL,
    currency      VARCHAR(8)   NOT NULL DEFAULT 'VND',
    partner_code  VARCHAR(32),
    status        VARCHAR(24)  NOT NULL,          -- CREATED | AUTHORIZING | COMPLETED | FAILED | HELD
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE order_steps (
    id          BIGSERIAL PRIMARY KEY,
    order_id    UUID         NOT NULL,
    step_name   VARCHAR(48)  NOT NULL,
    step_status VARCHAR(16)  NOT NULL,            -- PENDING | DONE | FAILED | COMPENSATED
    detail      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- CHỦ Ý: KHÔNG index order_steps.order_id — phục vụ lỗi #4 (N+1 + MISSING_INDEX ở GET /api/orders/history).
