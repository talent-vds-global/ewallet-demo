-- Sua khoa duy nhat cua notification_outbox.
--
-- V2 dat unique (event_id, channel). Khoa do sai voi R-NOTIF-02: giao dich P2P phai bao cho
-- CA nguoi gui va nguoi nhan, ma ca hai deu qua kenh PUSH cua cung mot event -> ban ghi thu hai
-- bi chan, nguoi nhan khong bao gio duoc bao.
--
-- Khoa dung phai gom ca customer_id: mot event, mot kenh, mot khach hang thi chi mot thong bao.
DROP INDEX IF EXISTS uq_outbox_event_channel;

CREATE UNIQUE INDEX uq_outbox_event_channel_customer
    ON notification_outbox(event_id, channel, customer_id);
