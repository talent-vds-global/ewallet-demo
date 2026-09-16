# [API] Hop dong REST gRPC Kafka

> Nguồn gốc: `ewallet-demo/docs/specs/01-api-contracts.md`.
> Trang này sinh ra node `Endpoint`, `RpcMethod` và `Topic` trong Knowledge Graph. Mọi span HTTP/gRPC/Kafka
> thu được từ trace phải khớp về được một dòng trong các bảng dưới. Span không khớp dòng nào là
> **endpoint không có tài liệu** — đó cũng là một loại phát hiện của v-quality.

## Page Properties

| Khoá | Giá trị |
|---|---|
| Loại tài liệu | Hợp đồng interface |
| Applies To | F1, F2, F3, F4, F5, F6 |
| Spec Source | docs/specs/01-api-contracts.md |
| Doc Version | 1.0 |
| Status | APPROVED |

---

# 1. Bản đồ gọi giữa các service

| # | Từ | Đến | Giao thức | Dùng ở flow |
|---|---|---|---|---|
| 1 | client | `ewallet-gateway` | HTTP | F1–F6 |
| 2 | `ewallet-gateway` | `ewallet-business-customer-mobileapp` | HTTP | F1–F4, F6 |
| 3 | `ewallet-gateway` | `ewallet-payment-order` | HTTP route `/orders/**` nội bộ | F6 |
| 4 | `ewallet-gateway` | `ewallet-notification` | HTTP/SSE | F5 |
| 5 | `ewallet-business-customer-mobileapp` | `ewallet-payment-order` | HTTP | F1–F4, F6 |
| 6 | `ewallet-payment-order` | `ewallet-payment-business` | gRPC | F1–F4 |
| 7 | `ewallet-payment-business` | `ewallet-third-party` | HTTP | F1, F2, F4 |
| 8 | `ewallet-third-party` | `partner-sim` | HTTP | F1, F2, F4 |
| 9 | `ewallet-third-party` | `partner-sim` | WebSocket (kết nối dài) | F1, F2 |
| 10 | `ewallet-payment-business` | Kafka `ewallet.payment.events` | Kafka producer | F1–F5 |
| 11 | Kafka | `ewallet-notification` (`notification-cg`) | Kafka consumer | F5 |
| 12 | Kafka | `ewallet-payment-order` (`order-status-cg`) | Kafka consumer | F5 |
| 13 | `ewallet-notification` | client | SSE (kết nối dài) | F5 |

---

# 2. Endpoint client-facing

| Method | Path | Service xử lý | Flow | Header bắt buộc |
|---|---|---|---|---|
| POST | `/api/wallet/topup` | ewallet-business-customer-mobileapp | F1 | `X-Idempotency-Key` |
| GET | `/api/wallet/bill` | ewallet-business-customer-mobileapp | F2 | — |
| POST | `/api/wallet/bill/pay` | ewallet-business-customer-mobileapp | F2 | `X-Idempotency-Key` |
| POST | `/api/wallet/telco/topup` | ewallet-business-customer-mobileapp | F2 | `X-Idempotency-Key` |
| POST | `/api/wallet/transfer` | ewallet-business-customer-mobileapp | F3 | `X-Idempotency-Key` |
| GET | `/api/wallet/transactions` | ewallet-business-customer-mobileapp | F6 | — |
| GET | `/api/notifications/stream` | ewallet-notification | F5 | — |

## 2.1 `POST /api/wallet/topup` — F1

Request:

```json
{
  "customerId": "CUST-001",
  "amount": 500000,
  "currency": "VND",
  "partnerCode": "VNPAY",
  "partnerAccountRef": "9704xxxxxxxx1234"
}
```

Response `200` / `202` / `422` / `502`:

```json
{
  "orderId": "0f2a1b3c-4d5e-4f60-8a71-b2c3d4e5f601",
  "status": "COMPLETED",
  "reasonCode": "OK",
  "amount": 500000,
  "fee": 0,
  "currency": "VND",
  "balanceAfter": 5500000,
  "partnerRef": "PS-9f3c8a1b",
  "createdAt": "2026-09-09T08:31:21.902Z"
}
```

## 2.2 `GET /api/wallet/bill` — F2 tra cứu

Query: `partnerCode=EVN&billCode=PE0123456789`

```json
{
  "partnerCode": "EVN",
  "billCode": "PE0123456789",
  "customerName": "Nguyen Van A",
  "period": "2026-08",
  "amount": 1250000,
  "currency": "VND",
  "status": "UNPAID"
}
```

## 2.3 `POST /api/wallet/bill/pay` — F2

```json
{
  "customerId": "CUST-001",
  "partnerCode": "EVN",
  "billCode": "PE0123456789",
  "amount": 1250000,
  "currency": "VND"
}
```

## 2.4 `POST /api/wallet/telco/topup` — F2 biến thể TELCO

```json
{
  "customerId": "CUST-001",
  "partnerCode": "VTELCO",
  "phoneNumber": "0987654321",
  "amount": 100000,
  "currency": "VND"
}
```

## 2.5 `POST /api/wallet/transfer` — F3

```json
{
  "customerId": "CUST-001",
  "destCustomerId": "CUST-002",
  "amount": 300000,
  "currency": "VND",
  "note": "tra tien com trua"
}
```

## 2.6 `GET /api/wallet/transactions` — F6

Query: `customerId=CUST-001&limit=20`. Trả `items[]` đã ẩn `source_account_id`, `dest_account_id`,
`idempotency_key` theo `R-HIST-04`.

---

# 3. Endpoint nội bộ

| Method | Path | Service | Flow | Ghi chú |
|---|---|---|---|---|
| POST | `/api/orders` | ewallet-payment-order | F1–F4 | tạo đơn, nhận `paymentType` |
| GET | `/api/orders/bill-inquiry` | ewallet-payment-order | F2 | bước S0 |
| GET | `/api/orders/history` | ewallet-payment-order | F6 | route Ops trả đủ trường |
| POST | `/api/orders/{orderId}/refund` | ewallet-payment-order | F4 | hoàn tiền chủ động |
| POST | `/api/thirdparty/execute` | ewallet-third-party | F1, F2, F4 | |
| POST | `/api/thirdparty/bill-inquiry` | ewallet-third-party | F2 | |
| GET | `/admin/limits` | ewallet-payment-business | — | đọc `limit_config`, dùng đối chiếu hạn mức |
| GET | `/admin/accounts/{customerId}/balance` | ewallet-payment-business | — | số dư + `daily_usage` hôm nay |
| GET | `/admin/transactions/{orderId}` | ewallet-payment-business | — | transaction + ledger của một đơn |

---

# 4. gRPC — `ewallet-payment-order` gọi `ewallet-payment-business`

| rpc | Request | Response | Flow |
|---|---|---|---|
| `AuthorizePayment` | `AuthorizePaymentRequest` | `AuthorizePaymentResponse` | F1–F4 |
| `ExecutePartnerPayment` | `ExecutePartnerPaymentRequest` | `ExecutePartnerPaymentResponse` | F1, F2, F4 |
| `ConfirmPayment` | `ConfirmPaymentRequest` | `ConfirmPaymentResponse` | F1–F3 |
| `ReversePayment` | `ReversePaymentRequest` | `ReversePaymentResponse` | F4 |
| `InquireBill` | `InquireBillRequest` | `InquireBillResponse` | F2 |

Trường quan trọng của `AuthorizePaymentRequest`: `orderId`, `customerId`, `paymentType`, `amount`,
**`currency`**, `partnerCode`, `destCustomerId`, `billCode`.

> Trường `currency` bắt buộc phải được handler đọc và dùng theo `R-CURRENCY-01`. Muốn kiểm chứng điều này
> từ trace thì collector phải thu được **attribute của span gRPC** — bật knob
> `OTEL_INSTRUMENTATION_GRPC_CAPTURE_METADATA` hoặc thêm span attribute custom.

---

# 5. Kafka

| Topic | Producer | Consumer group | Key | Value |
|---|---|---|---|---|
| `ewallet.payment.events` | ewallet-payment-business | `notification-cg`, `order-status-cg` | `orderId` | JSON |
| `ewallet.payment.events.DLT` | ewallet-notification | — | `orderId` | JSON |

Event value:

```json
{
  "eventId": "6c1f6d1e-0f5b-4a52-9e5c-2b1f0a9d7c31",
  "eventType": "PaymentCompleted",
  "occurredAt": "2026-09-09T08:31:22.145Z",
  "orderId": "0f2a1b3c-4d5e-4f60-8a71-b2c3d4e5f601",
  "txnId": "8b1c2d3e-4f50-4617-8829-a0b1c2d3e4f5",
  "customerId": "CUST-001",
  "counterpartyCustomerId": null,
  "paymentType": "TOP_UP",
  "amount": 500000,
  "fee": 0,
  "currency": "VND",
  "amountVnd": 500000,
  "status": "COMPLETED",
  "reasonCode": "OK",
  "partnerCode": "VNPAY",
  "partnerRef": "PS-9f3c8a1b",
  "schemaVersion": 1
}
```

Header:

| Header | Ý nghĩa |
|---|---|
| `eventType` | Lặp lại `eventType` trong body để consumer lọc không cần deserialize |
| `x-attempt` | Số lần thử (1 là lần đầu). Trace Analyzer dùng để phân loại `first_attempt` / `retry` / `dead_letter` |
| `traceparent` | OTel agent tự gắn, giữ trace xuyên message queue |

| eventType | Publish khi | Consumer quan tâm |
|---|---|---|
| `PaymentCompleted` | Transaction `CAPTURED` | notification, order |
| `PaymentFailed` | Transaction `REJECTED` hoặc order `FAILED` | notification, order |
| `PaymentHeld` | Transaction `HELD` theo R-REVIEW-01 | notification, order |
| `PaymentRefunded` | Transaction `REVERSED` | notification, order |

---

# 6. WebSocket và SSE

| Kênh | Từ | Đến | Nội dung |
|---|---|---|---|
| WebSocket | partner-sim | ewallet-third-party | frame `WATCH` và `SETTLEMENT`, mang `traceparent` trong frame |
| SSE | ewallet-notification | client | event `payment`, lọc theo `customerId` theo `R-NOTIF-06` |

---

# 7. Cổng dịch vụ

| Service | Cổng container | Cổng host |
|---|---|---|
| ewallet-gateway | 8080 | 18080 |
| ewallet-business-customer-mobileapp | 8081 | 18081 |
| ewallet-payment-order | 8082 | 18082 |
| ewallet-payment-business | 8083 (HTTP) + gRPC | 18083 |
| ewallet-third-party | 8084 | 18084 |
| ewallet-notification | 8085 | 18085 |
| partner-sim | 8090 | 18090 |

---

# 8. Bảng ghi nhận thay đổi tài liệu

| Ngày thay đổi | Vị trí thay đổi | A/M/D | Nguồn gốc | Link CR | Mô tả thay đổi | Phiên bản confluence | Phiên bản mới |
|---|---|---|---|---|---|---|---|
| 16 Sep 2026 | Toàn bộ | A | Stage D specs | | Tạo mới tài liệu từ `docs/specs/01-api-contracts.md` | 1 | 1.0 |
