# [CATALOG] Business Rule

> Danh mục **toàn bộ 62 business rule** của hệ ví điện tử, gom một chỗ để tra cứu và để v-quality
> tự kiểm tính đầy đủ. Bảng chi tiết của từng rule nằm trên trang flow tương ứng; trang này là chỉ mục
> có `Nơi cài đặt` — cột quan trọng nhất với Code Indexer.

## Page Properties

| Khoá | Giá trị |
|---|---|
| Loại tài liệu | Catalog |
| Applies To | F1, F2, F3, F4, F5, F6 |
| Tổng số rule | 62 |
| Spec Source | docs/specs/ (tổng hợp) |
| Doc Version | 1.0 |
| Status | APPROVED |

---

# 1. Thống kê

| Nhóm | Số rule | Flow áp dụng |
|---|---|---|
| `R-IDEM-*` | 3 | F1–F4 |
| `R-ACCOUNT-*` | 2 | F1–F4 |
| `R-AMOUNT-*` | 2 | F1–F3 |
| `R-LIMIT-*` | 1 | F1–F3 |
| `R-REVIEW-*` | 1 | F1–F3 |
| `R-CURRENCY-*` | 1 | F1–F3 |
| `R-BALANCE-*` | 1 | F2, F3 |
| `R-LEDGER-*` | 2 | F1–F4 |
| `R-USAGE-*` | 2 | F1–F4 |
| `R-EVENT-*` | 2 | F1–F5 |
| `R-FEE-*` | 5 | F1–F4 |
| `R-TOPUP-*` | 6 | F1 |
| `R-BILL-*` | 4 | F2 |
| `R-TELCO-*` | 3 | F2 |
| `R-FX-*` | 2 | F2 |
| `R-P2P-*` | 7 | F3 |
| `R-COMP-*` | 8 | F4 |
| `R-NOTIF-*` | 7 | F5 |
| `R-ORDST-*` | 3 | F5 |
| `R-HIST-*` | 7 | F6 |
| **Tổng** | **62** | |

---

# 2. Chỉ mục rule theo nhóm

| Mã | Flow | Severity | Nơi cài đặt dự kiến (class) | Nguồn giá trị lúc chạy |
|---|---|---|---|---|
| `R-IDEM-01` | F1–F4 | must | `order.web.OrderController` | — |
| `R-IDEM-02` | F1–F4 | must | `order.saga.PaymentSagaOrchestrator` | `payment_orders.idempotency_key` |
| `R-IDEM-03` | F1–F4 | must | `order.saga.PaymentSagaOrchestrator` | `payment_orders.idempotency_key` |
| `R-ACCOUNT-01` | F1–F4 | must | `payment.service.PaymentService` | `accounts` |
| `R-ACCOUNT-02` | F1–F4 | must | `payment.service.PaymentService` | `accounts.status` |
| `R-AMOUNT-01` | F1–F3 | must | `payment.domain.LimitPolicy` | `limit_config.MIN_TXN_AMOUNT` |
| `R-AMOUNT-02` | F1–F3 | must | `payment.domain.LimitPolicy` | `limit_config.MAX_TXN_*` |
| `R-LIMIT-01` | F1–F3 | must | `payment.domain.LimitPolicy` | `limit_config.DAILY_TRANSFER_LIMIT` |
| `R-REVIEW-01` | F1–F3 | must | `payment.domain.ReviewPolicy` | `limit_config.REVIEW_THRESHOLD` |
| `R-CURRENCY-01` | F1–F3 | must | `payment.domain.CurrencyConverter` | `fx_rates` |
| `R-BALANCE-01` | F2, F3 | must | `payment.service.PaymentService` | `account_balances` |
| `R-LEDGER-01` | F1–F4 | must | `payment.ledger.LedgerService` | — |
| `R-LEDGER-02` | F4 | must | `payment.ledger.LedgerService` | — |
| `R-USAGE-01` | F1–F3 | must | `payment.service.PaymentService` | `daily_usage` |
| `R-USAGE-02` | F4 | must | `payment.service.PaymentService` | `daily_usage` |
| `R-EVENT-01` | F1–F5 | must | `payment.kafka.PaymentEventPublisher` | topic `ewallet.payment.events` |
| `R-EVENT-02` | F1–F5 | must | `payment.kafka.PaymentEventPublisher` | — |
| `R-FEE-01` | F1 | must | `payment.domain.FeePolicy` | — |
| `R-FEE-02` | F2 | must | `payment.domain.FeePolicy` | — |
| `R-FEE-03` | F2 | must | `payment.domain.FeePolicy` | — |
| `R-FEE-04` | F3 | must | `payment.domain.FeePolicy` | — |
| `R-FEE-05` | F4 | must | `payment.domain.FeePolicy` | — |
| `R-TOPUP-01` | F1 | must | `payment.service.PaymentService` | `accounts.account_type` |
| `R-TOPUP-02` | F1 | must | `thirdparty.web.ThirdPartyController` | `partner_config` |
| `R-TOPUP-03` | F1 | must | `thirdparty.partner.TopupAdapter` | `partner_config.service_type` |
| `R-TOPUP-04` | F1 | must | `payment.domain.FeePolicy` | — |
| `R-TOPUP-05` | F1 | should | `payment.client.ThirdPartyClient` | — |
| `R-TOPUP-06` | F1 | must | `order.saga.PaymentSagaOrchestrator` | `payment_transactions.status` |
| `R-BILL-01` | F2 | must | `order.web.OrderController` | — |
| `R-BILL-02` | F2 | must | `order.saga.PaymentSagaOrchestrator` | — |
| `R-BILL-03` | F2 | must | `thirdparty.partner.BillAdapter` | — |
| `R-BILL-04` | F2 | must | `payment.ledger.LedgerService` | — |
| `R-TELCO-01` | F2 | must | `mobileapp.web.WalletController` | — |
| `R-TELCO-02` | F2 | must | `payment.domain.LimitPolicy` | — |
| `R-TELCO-03` | F2 | must | `payment.domain.FeePolicy` | — |
| `R-FX-01` | F2 | must | `payment.domain.CurrencyConverter` | `fx_rates.rate_to_vnd` |
| `R-FX-02` | F2 | must | `payment.ledger.LedgerService` | — |
| `R-P2P-01` | F3 | must | `payment.service.PaymentService` | — |
| `R-P2P-02` | F3 | must | `order.saga.PaymentSagaOrchestrator` | — |
| `R-P2P-03` | F3 | must | `payment.domain.FeePolicy` | — |
| `R-P2P-04` | F3 | must | `notification.service.NotificationRouter` | — |
| `R-P2P-05` | F3 | must | `payment.service.PaymentService` | `accounts.currency` |
| `R-P2P-06` | F3 | must | `payment.ledger.LedgerService` | — |
| `R-P2P-07` | F3 | should | `payment.ledger.LedgerService` | — |
| `R-COMP-01` | F4 | must | `order.saga.PaymentSagaOrchestrator` | — |
| `R-COMP-02` | F4 | must | `payment.ledger.LedgerService` | — |
| `R-COMP-03` | F4 | must | `payment.service.PaymentService` | `daily_usage` |
| `R-COMP-04` | F4 | must | `order.saga.PaymentSagaOrchestrator` | — |
| `R-COMP-05` | F4 | must | `payment.service.PaymentService` | `payment_transactions.reversed_txn_id` |
| `R-COMP-06` | F4 | must | `order.web.OrderController` | `payment_orders.status` |
| `R-COMP-07` | F4 | must | `order.saga.PaymentSagaOrchestrator` | `payment_orders.status` |
| `R-COMP-08` | F4 | must | `order.web.OrderController` | — |
| `R-NOTIF-01` | F5 | must | `notification.service.NotificationRouter` | — |
| `R-NOTIF-02` | F5 | must | `notification.service.NotificationRouter` | — |
| `R-NOTIF-03` | F5 | must | `notification.kafka.PaymentEventListener` | — |
| `R-NOTIF-04` | F5 | must | `notification.kafka.PaymentEventListener` | topic DLT |
| `R-NOTIF-05` | F5 | must | `notification.repo.NotificationOutboxRepository` | unique index `uq_outbox_event_channel_customer` |
| `R-NOTIF-06` | F5 | must | `notification.web.NotificationStreamController` | — |
| `R-NOTIF-07` | F5 | should | `notification.service.NotificationSender` | — |
| `R-ORDST-01` | F5 | must | `order.kafka.OrderStatusListener` | — |
| `R-ORDST-02` | F5 | must | `order.kafka.OrderStatusListener` | `payment_orders.status` |
| `R-ORDST-03` | F5 | must | `order.kafka.OrderStatusListener` | `order_steps` |
| `R-HIST-01` | F6 | must | `order.history.OrderHistoryService` | — |
| `R-HIST-02` | F6 | must | `order.repo.PaymentOrderRepository` | — |
| `R-HIST-03` | F6 | must | `order.web.OrderController` | — |
| `R-HIST-04` | F6 | must | `mobileapp.web.WalletController` | — |
| `R-HIST-05` | F6 | should | `order.history.OrderHistoryService` | — |
| `R-HIST-06` | F6 | must | `order.history.OrderHistoryService` | — |
| `R-HIST-07` | F6 | must | migration `V2__business.sql` | index DB |

Cột **Nơi cài đặt dự kiến** là gợi ý cho Code Indexer, không phải ràng buộc cứng. Khi code thực tế đặt
rule ở class khác, Agent vẫn tìm ra bằng call graph — nhưng cột này giúp khoanh vùng nhanh hơn nhiều.

---

# 3. Rule có rủi ro drift cao

Đây là các rule mà **giá trị nằm ở hai nơi** (tài liệu và code/DB), nên dễ lệch nhất. Mỗi rule ở đây
nên có một Task Jira theo dõi.

| Mã | Giá trị đúng | Rủi ro | Cách v-quality kiểm |
|---|---|---|---|
| `R-LIMIT-01` | 50.000.000đ/ngày | Hard-code hằng số trong `LimitPolicy` thay vì đọc `limit_config` | So hằng số trong code với `limit_config` và với rule |
| `R-REVIEW-01` | ≥ 20.000.000đ thì HELD **và publish `PaymentHeld`** | Nhánh HELD quên publish event | Trace: nhánh HELD không có span `publish` |
| `R-CURRENCY-01`, `R-FX-01` | Quy đổi trước khi áp hạn mức | Handler gRPC bỏ qua field `currency` | Attribute span gRPC so với `amount_vnd` trong DB |
| `R-AMOUNT-02` | TOP_UP/BILL 50tr, P2P 30tr | Ba giá trị dễ lệch nhau | So `limit_config.MAX_TXN_*` với code |
| `R-HIST-06` | Một truy vấn cho nhiều đơn | Vòng lặp truy vấn theo từng đơn (N+1) | `database-quality-library` rule `N_PLUS_ONE` |
| `R-HIST-07` | Có index trên `order_steps(order_id)` | Migration quên tạo index | Schema snapshot + `pg_stat_user_tables.seq_scan` |
| `R-EVENT-01` | Đúng 1 event mỗi trạng thái kết thúc | Nhánh nào đó quên publish | Đếm span `publish` theo nhánh |
| `R-COMP-01` | Luôn bù trừ sau khi authorize thất bại | Nhánh lỗi hiếm không được test | Coverage so với `FlowObservation` |

---

# 4. Bảng ghi nhận thay đổi tài liệu

| Ngày thay đổi | Vị trí thay đổi | A/M/D | Nguồn gốc | Link CR | Mô tả thay đổi | Phiên bản confluence | Phiên bản mới |
|---|---|---|---|---|---|---|---|
| 16 Sep 2026 | Toàn bộ | A | Stage D specs | | Tạo mới catalog từ 62 rule trong `docs/specs/` | 1 | 1.0 |
