-- notifdb — Stage B: khung bảng.
CREATE TABLE notification_outbox (
    id          UUID PRIMARY KEY,
    event_type  VARCHAR(32)  NOT NULL,           -- PaymentCompleted | PaymentFailed | PaymentRefunded
    customer_id VARCHAR(64)  NOT NULL,
    payload     TEXT         NOT NULL,
    channel     VARCHAR(16)  NOT NULL,           -- PUSH | SMS | EMAIL
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE notification_sent_log (
    id            BIGSERIAL PRIMARY KEY,
    outbox_id     UUID         NOT NULL REFERENCES notification_outbox(id),
    attempt       INT          NOT NULL DEFAULT 1,
    result        VARCHAR(16)  NOT NULL,          -- SENT | FAILED | DEAD_LETTER
    sent_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_sent_log_outbox ON notification_sent_log(outbox_id);
