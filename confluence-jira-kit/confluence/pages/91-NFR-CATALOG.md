# [CATALOG] NFR

> Danh mục 14 ràng buộc phi chức năng. Mỗi dòng sinh ra một node `NFRConstraint` và được Trace Analyzer
> so trực tiếp với `SpanStat.p95`. Cột **Đo ở span nào** là cầu nối bắt buộc — thiếu nó thì platform
> biết ngưỡng nhưng không biết so với cái gì.

## Page Properties

| Khoá | Giá trị |
|---|---|
| Loại tài liệu | Catalog |
| Applies To | F1, F2, F3, F4, F5, F6 |
| Tổng số NFR | 14 |
| Spec Source | docs/specs/ (tổng hợp) |
| Doc Version | 1.0 |
| Status | APPROVED |

---

# 1. Bảng NFR

| Mã | metric | operator | threshold | unit |
|---|---|---|---|---|
| `NFR-LAT-01` | latency_p95 | `<` | 500 | ms |
| `NFR-LAT-02` | latency_p95 | `<` | 2000 | ms |
| `NFR-LAT-03` | latency_p95 | `<` | 800 | ms |
| `NFR-LAT-04` | latency_p95 | `<` | 300 | ms |
| `NFR-LAT-05` | latency_p95 | `<` | 5000 | ms |
| `NFR-LAT-06` | latency_p95 | `<` | 1500 | ms |
| `NFR-TIMEOUT-01` | timeout | `<=` | 3000 | ms |
| `NFR-ERR-01` | error_rate | `<` | 1 | percent |
| `NFR-DB-01` | query_count | `<=` | 2 | queries |
| `NFR-DB-02` | latency_p95 | `<` | 50 | ms |
| `NFR-COMP-01` | latency_p95 | `<` | 1000 | ms |
| `NFR-COMP-02` | error_rate | `<` | 0.1 | percent |
| `NFR-NOTIF-01` | error_rate | `<` | 1 | percent |
| `NFR-NOTIF-02` | consumer_lag | `<` | 100 | messages |

---

# 2. Cách đo

| Mã | Flow | Đo ở span nào | Thuộc tính lọc | Cửa sổ |
|---|---|---|---|---|
| `NFR-LAT-01` | F1, F2, F3 | span gRPC server `AuthorizePayment` ở `ewallet-payment-business` | `rpc.service=PaymentBusiness`, `rpc.method=AuthorizePayment` | 15 phút trượt |
| `NFR-LAT-02` | F1, F2 | span HTTP server ở `ewallet-gateway` | `http.route=/api/wallet/topup` hoặc `/api/wallet/bill/pay` | 15 phút trượt |
| `NFR-LAT-03` | F3 | span HTTP server ở `ewallet-gateway` | `http.route=/api/wallet/transfer` | 15 phút trượt |
| `NFR-LAT-04` | F6 | span HTTP server ở `ewallet-payment-order` | `http.route=/api/orders/history` | 15 phút trượt |
| `NFR-LAT-05` | F5 | từ span `publish` tới span ghi `notification_sent_log` | `messaging.destination=ewallet.payment.events` | 15 phút trượt |
| `NFR-LAT-06` | F2 | span HTTP server ở `ewallet-gateway` | `http.route=/api/wallet/bill` | 15 phút trượt |
| `NFR-TIMEOUT-01` | F1, F2, F4 | span HTTP client từ `ewallet-third-party` sang `partner-sim` | `peer.service=partner-sim` | mỗi lời gọi |
| `NFR-ERR-01` | F1–F3 | span HTTP server ở `ewallet-gateway` | `http.status_code >= 500` | 1 giờ trượt |
| `NFR-DB-01` | F6 | đếm span JDBC trong một trace của `/api/orders/history` | `db.system=postgresql` | mỗi request |
| `NFR-DB-02` | F6 | span JDBC riêng lẻ | `db.statement` đã normalize | 15 phút trượt |
| `NFR-COMP-01` | F4 | từ span partner trả lỗi tới lúc `payment_orders.status=REFUNDED` | `order_steps.step_name=COMPENSATE` | 1 giờ trượt |
| `NFR-COMP-02` | F4 | tỉ lệ đơn có `reason_code=COMPENSATION_FAILED` | | 24 giờ |
| `NFR-NOTIF-01` | F5 | tỉ lệ message vào `ewallet.payment.events.DLT` | `messaging.destination` | 24 giờ |
| `NFR-NOTIF-02` | F5 | consumer lag của group `notification-cg` | `messaging.kafka.consumer.group` | tức thời |

> Với các NFR đo qua Kafka, chỉ tính bản ghi có header `x-attempt = 1`. Lần retry và dead letter đếm
> riêng, không trộn vào p95 — nếu trộn thì một sự cố gửi lại sẽ làm sai lệch baseline của đường bình thường.

---

# 3. Hành vi khi vi phạm

| Mã | Mức | Hành động của platform |
|---|---|---|
| `NFR-LAT-01` | BLOCK | Chặn PR nếu p95 vượt ngưỡng trên nhánh thay đổi |
| `NFR-LAT-02`, `NFR-LAT-03`, `NFR-LAT-06` | WARN | Comment cảnh báo trên PR |
| `NFR-LAT-04`, `NFR-DB-01`, `NFR-DB-02` | BLOCK | Vi phạm đồng nghĩa với anti-pattern truy vấn DB |
| `NFR-LAT-05`, `NFR-NOTIF-01`, `NFR-NOTIF-02` | WARN | Alert kênh Ops |
| `NFR-TIMEOUT-01` | BLOCK | Cấu hình timeout sai là lỗi cấu hình, không phải hiệu năng |
| `NFR-ERR-01` | BLOCK | |
| `NFR-COMP-01` | WARN | |
| `NFR-COMP-02` | BLOCK | Bù trừ thất bại nghĩa là tiền khách bị treo |

---

# 4. Baseline

Bảng này do platform tự cập nhật sau mỗi lần chốt baseline. Điền tay lần đầu, sau đó để
Trace Analyzer ghi đè.

| Mã | Giá trị đo được gần nhất | Ngày đo | Kết luận |
|---|---|---|---|
| `NFR-LAT-01` | chưa đo | | |
| `NFR-LAT-02` | chưa đo | | |
| `NFR-LAT-03` | chưa đo | | |
| `NFR-LAT-04` | chưa đo | | |
| `NFR-LAT-05` | chưa đo | | |
| `NFR-LAT-06` | chưa đo | | |
| `NFR-TIMEOUT-01` | chưa đo | | |
| `NFR-ERR-01` | chưa đo | | |
| `NFR-DB-01` | chưa đo | | |
| `NFR-DB-02` | chưa đo | | |
| `NFR-COMP-01` | chưa đo | | |
| `NFR-COMP-02` | chưa đo | | |
| `NFR-NOTIF-01` | chưa đo | | |
| `NFR-NOTIF-02` | chưa đo | | |

---

# 5. Bảng ghi nhận thay đổi tài liệu

| Ngày thay đổi | Vị trí thay đổi | A/M/D | Nguồn gốc | Link CR | Mô tả thay đổi | Phiên bản confluence | Phiên bản mới |
|---|---|---|---|---|---|---|---|
| 16 Sep 2026 | Toàn bộ | A | Stage D specs | | Tạo mới catalog từ 14 NFR trong `docs/specs/` | 1 | 1.0 |
