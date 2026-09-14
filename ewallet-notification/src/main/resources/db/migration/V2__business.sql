-- notifdb — Stage C: gắn thông báo với đơn và event nguồn.
-- Nguồn: docs/specs/00-domain-and-conventions.md §10.4

ALTER TABLE notification_outbox
    ADD COLUMN order_id UUID,
    ADD COLUMN event_id UUID,
    ADD COLUMN attempt  INT NOT NULL DEFAULT 1;

-- R-NOTIF-05: một event chỉ sinh đúng một thông báo cho mỗi kênh, kể cả khi consumer
-- nhận lại message (Kafka bảo đảm at-least-once, không phải exactly-once).
CREATE UNIQUE INDEX uq_outbox_event_channel ON notification_outbox(event_id, channel);
