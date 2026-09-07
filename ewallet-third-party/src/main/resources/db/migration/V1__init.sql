-- thirdpartydb — Stage B: khung bảng.
CREATE TABLE partner_config (
    partner_code   VARCHAR(32) PRIMARY KEY,
    partner_name   VARCHAR(128) NOT NULL,
    service_type   VARCHAR(24)  NOT NULL,          -- TOPUP | BILL | TELCO
    base_url       VARCHAR(256) NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE partner_transactions (
    id             UUID PRIMARY KEY,
    partner_code   VARCHAR(32)  NOT NULL REFERENCES partner_config(partner_code),
    order_id       UUID         NOT NULL,
    amount         BIGINT       NOT NULL,
    status         VARCHAR(16)  NOT NULL,          -- PENDING | SUCCESS | FAILED
    partner_ref    VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    settled_at     TIMESTAMPTZ
);
CREATE INDEX idx_partner_txn_order ON partner_transactions(order_id);

-- Stage B seed tối thiểu.
INSERT INTO partner_config (partner_code, partner_name, service_type, base_url) VALUES
    ('VNPAY',   'VNPAY Gateway',    'TOPUP', 'http://partner-sim:8090'),
    ('EVN',     'EVN Bill',         'BILL',  'http://partner-sim:8090'),
    ('VTELCO',  'Viettel Telco',    'TELCO', 'http://partner-sim:8090');
