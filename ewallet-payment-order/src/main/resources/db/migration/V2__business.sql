-- orderdb — Stage C: cột nghiệp vụ cho saga.
-- Nguồn: docs/specs/00-domain-and-conventions.md §10.1

ALTER TABLE payment_orders
    ADD COLUMN idempotency_key   VARCHAR(80),
    ADD COLUMN source_account_id UUID,
    ADD COLUMN dest_account_id   UUID,
    ADD COLUMN dest_customer_id  VARCHAR(64),
    ADD COLUMN bill_code         VARCHAR(64),
    ADD COLUMN account_ref       VARCHAR(64),
    ADD COLUMN fee               BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN amount_vnd        BIGINT,
    ADD COLUMN txn_id            UUID,
    ADD COLUMN partner_ref       VARCHAR(64),
    ADD COLUMN reason_code       VARCHAR(32);

-- R-IDEM-02 / R-IDEM-03: một idempotency key chỉ ứng với đúng một đơn.
CREATE UNIQUE INDEX uq_orders_idem ON payment_orders(idempotency_key);

-- CHỦ Ý: KHÔNG tạo index trên payment_orders(customer_id) và order_steps(order_id).
-- Phục vụ lỗi có chủ đích #4 (N+1 + MISSING_INDEX ở GET /api/orders/history).
-- Xem docs/specs/F6-transaction-history.md — R-HIST-06, R-HIST-07, NFR-DB-01.

ALTER TABLE order_steps
    ADD COLUMN attempt     INT NOT NULL DEFAULT 1,
    ADD COLUMN duration_ms BIGINT;
