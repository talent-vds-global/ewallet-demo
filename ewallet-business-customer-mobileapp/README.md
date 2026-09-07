# ewallet-business-customer-mobileapp

**Vai trò:** BFF cho app khách hàng — nơi khởi tạo giao dịch. **Control flow: nhẹ** (validate, shape, aggregate).
**Giao thức:** HTTP REST. **DB:** không.

Endpoint (client-facing):
- `POST /api/payments/topup`   → F1 nạp tiền qua đối tác
- `POST /api/payments/bill`    → F2 thanh toán hoá đơn / telco
- `POST /api/payments/p2p`     → F3 chuyển tiền P2P
- `POST /api/payments/{id}/refund` → F4 hoàn tiền
- `GET  /api/orders/history`   → proxy sang order (đường đọc, chạm lỗi #4 N+1 ở order)

Chỉ validate cơ bản + gọi `ewallet-payment-order` qua HTTP. Không giữ business rule.
