# ewallet-notification

**Vai trò:** fan-out thông báo sau giao dịch. **Control flow: nhẹ** (rẽ nhánh theo loại event → kênh gửi).
**Giao thức:** **Kafka consumer** `ewallet.payment.events` (group `notification-cg`) · **SSE** `/api/notifications/stream` (kết nối dài → app).
**DB:** `notifdb` (`notification_outbox`, `notification_sent_log`).

- Consumer `notification-cg`: nhận `PaymentCompleted` / `PaymentFailed` / `PaymentRefunded` → ghi `notification_outbox` → đẩy SSE.
- Không nhận `PaymentHeld` (vì **LỖI #2** không ai phát) → khách giao dịch lớn không được báo → quan sát qua trace.
- Chủ động bơm lỗi tạm (cấu hình `notif.flaky-rate`) để sinh **retry** + **dead_letter** (`ewallet.payment.events.DLT`),
  kiểm chứng Trace Analyzer phân loại `first_attempt | retry | dead_letter`.

Là consumer group **thứ nhất** trên topic dùng chung; `order-status-cg` (ở order) là thứ hai.

**database-quality-library** (kế thừa Topic #80): dashboard port 9876 (host 19085). Thu SQL pattern cho `notifdb`.
Xem `docs/db-quality-integration.md`.
