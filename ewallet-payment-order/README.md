# ewallet-payment-order

**Vai trò:** tạo & orchestrate payment order (saga nhiều bước, có nhánh compensation). **Control flow: nặng.**
**Giao thức:** HTTP in · **gRPC client** → payment-business · **Kafka consumer** `ewallet.payment.events` (group `order-status-cg`).
**DB:** `orderdb` (`payment_orders`, `order_steps`).

Endpoint:
- `POST /api/orders`            — tạo order, chạy saga: persist → gRPC `AuthorizePayment` → cập nhật step → chờ event
- `POST /api/orders/{id}/refund`— nhánh compensation (F4)
- `GET  /api/orders/history`    — **LỖI #4**: N+1 query (lặp select `order_steps` theo từng order thay vì join/batch)

Kafka consumer (`order-status-cg`): nhận `PaymentCompleted` / `PaymentFailed` → chuyển `payment_orders.status`.
Vì **LỖI #2** (business không phát `PaymentHeld`), order kẹt ở `AUTHORIZING` với giao dịch lớn → quan sát được qua trace.

Saga steps lưu ở `order_steps` để Trace Analyzer đối chiếu span ↔ step.

**database-quality-library** (kế thừa Topic #80): gắn qua JitPack + `application.properties`, dashboard port 9876 (host 19082).
Bắt lỗi #4 (`N_PLUS_ONE` + có thể `MISSING_INDEX`) kèm `calledFrom`. Xem `docs/db-quality-integration.md`.
