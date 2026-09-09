# 01 — Hợp đồng API (REST · gRPC · Kafka · WebSocket · SSE)

> Toàn bộ interface giữa các service, gom một chỗ để code Stage C không phải đoán.
> Quy ước chung (header, mã lỗi, status) ở [`00-domain-and-conventions.md`](00-domain-and-conventions.md) §6.

---

## 1. Bản đồ gọi giữa các service

| # | Từ | Đến | Giao thức | Dùng ở flow |
|---|---|---|---|---|
| 1 | client | `ewallet-gateway` | HTTP | F1–F6 |
| 2 | `ewallet-gateway` | `ewallet-business-customer-mobileapp` | HTTP | F1–F4, F6 |
| 3 | `ewallet-gateway` | `ewallet-payment-order` | HTTP (route `/orders/**`, nội bộ) | F6 |
| 4 | `ewallet-gateway` | `ewallet-notification` | HTTP/SSE | F5 |
| 5 | `ewallet-business-customer-mobileapp` | `ewallet-payment-order` | HTTP | F1–F4, F6 |
| 6 | `ewallet-payment-order` | `ewallet-payment-business` | **gRPC** | F1–F4 |
| 7 | `ewallet-payment-business` | `ewallet-third-party` | HTTP | F1, F2, F4 |
| 8 | `ewallet-third-party` | `partner-sim` | HTTP | F1, F2, F4 |
| 9 | `ewallet-third-party` | `partner-sim` | **WebSocket** (kết nối dài) | F1, F2 |
| 10 | `ewallet-payment-business` | Kafka `ewallet.payment.events` | **Kafka producer** | F1–F5 |
| 11 | Kafka | `ewallet-notification` (`notification-cg`) | **Kafka consumer** | F5 |
| 12 | Kafka | `ewallet-payment-order` (`order-status-cg`) | **Kafka consumer** | F5 |
| 13 | `ewallet-notification` | client | **SSE** (kết nối dài) | F5 |

Sơ đồ: [`../diagrams/00-container.md`](../diagrams/00-container.md).

---

## 2. `ewallet-gateway` (8080 / host 18080)

Không có logic nghiệp vụ. Chỉ route + propagate trace context. Thứ tự route quan trọng —
route cụ thể đặt **trước** route `/api/**`.

| Order | Route id | Predicate | Đích | Filter |
|---|---|---|---|---|
| 1 | `notifications` | `Path=/api/notifications/**` | `http://ewallet-notification:8085` | — |
| 2 | `wallet` | `Path=/api/wallet/**` | `http://ewallet-business-customer-mobileapp:8081` | — |
| 3 | `orders-internal` | `Path=/orders/**` | `http://ewallet-payment-order:8082` | `RewritePath=/orders/(?<seg>.*), /api/orders/${seg}` |
| 4 | `mobileapp` | `Path=/api/**` | `http://ewallet-business-customer-mobileapp:8081` | — (fallback, giữ từ Stage B) |

Default filter: `AddRequestHeader=X-Gateway, ewallet-gateway`.

---

## 3. `ewallet-business-customer-mobileapp` — BFF (8081 / host 18081)

Client-facing. Không chạm DB. Gọi xuống `payment-order` bằng `RestClient`.

### 3.1 `POST /api/wallet/topup` — F1

Header bắt buộc: `X-Idempotency-Key`.

```json
{
  "customerId": "CUST-001",
  "amount": 500000,
  "currency": "VND",
  "partnerCode": "VNPAY",
  "partnerAccountRef": "9704xxxxxxxx1234"
}
```

`200 / 202 / 422 / 502`:

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

### 3.2 `GET /api/wallet/bill?partnerCode=EVN&billCode=PE0123456789` — F2 (tra cứu)

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

### 3.3 `POST /api/wallet/bill/pay` — F2

```json
{
  "customerId": "CUST-001",
  "partnerCode": "EVN",
  "billCode": "PE0123456789",
  "amount": 1250000,
  "currency": "VND"
}
```

Response: như 3.1 (thêm `billCode`).

### 3.4 `POST /api/wallet/telco/topup` — F2 (biến thể TELCO)

```json
{
  "customerId": "CUST-001",
  "partnerCode": "VTELCO",
  "phoneNumber": "0987654321",
  "amount": 100000,
  "currency": "VND"
}
```

### 3.5 `POST /api/wallet/transfer` — F3

```json
{
  "customerId": "CUST-001",
  "destCustomerId": "CUST-002",
  "amount": 300000,
  "currency": "VND",
  "message": "tra tien com trua"
}
```

### 3.6 `GET /api/wallet/transactions?customerId=CUST-001&limit=20` — F6

```json
{
  "customerId": "CUST-001",
  "count": 2,
  "items": [
    {
      "orderId": "0f2a...",
      "paymentType": "TOP_UP",
      "amount": 500000,
      "fee": 0,
      "currency": "VND",
      "status": "COMPLETED",
      "partnerCode": "VNPAY",
      "createdAt": "2026-09-09T08:31:21.902Z",
      "steps": [
        { "stepName": "CREATE_ORDER", "stepStatus": "DONE", "durationMs": 4 },
        { "stepName": "AUTHORIZE", "stepStatus": "DONE", "durationMs": 61 }
      ]
    }
  ]
}
```

### 3.7 `GET /api/wallet/orders/{orderId}` — tra cứu 1 đơn

Trả đúng payload 3.1 của đơn tương ứng, `404` nếu không có.

### 3.8 Giữ nguyên từ Stage B

`GET /api/ping`, `GET /api/trace-test`.

---

## 4. `ewallet-payment-order` (8082 / host 18082)

Orchestrator saga. Sở hữu `orderdb`. Không client-facing (đi qua BFF hoặc route `/orders/**`).

### 4.1 `POST /api/orders` — tạo & chạy saga (F1–F4)

Header: `X-Idempotency-Key` (bắt buộc, chuyển tiếp từ BFF).

```json
{
  "customerId": "CUST-001",
  "paymentType": "TOP_UP",
  "amount": 500000,
  "currency": "VND",
  "partnerCode": "VNPAY",
  "partnerAccountRef": "9704xxxxxxxx1234",
  "destCustomerId": null,
  "billCode": null
}
```

`paymentType` ∈ `TOP_UP` · `BILL` · `TELCO` · `P2P`.

```json
{
  "orderId": "0f2a1b3c-4d5e-4f60-8a71-b2c3d4e5f601",
  "status": "COMPLETED",
  "reasonCode": "OK",
  "txnId": "8b1c2d3e-4f50-4617-8829-a0b1c2d3e4f5",
  "amount": 500000,
  "fee": 0,
  "currency": "VND",
  "amountVnd": 500000,
  "partnerRef": "PS-9f3c8a1b",
  "steps": [
    { "stepName": "CREATE_ORDER",   "stepStatus": "DONE" },
    { "stepName": "AUTHORIZE",      "stepStatus": "DONE" },
    { "stepName": "PARTNER_EXECUTE","stepStatus": "DONE" },
    { "stepName": "CONFIRM",        "stepStatus": "DONE" }
  ]
}
```

### 4.2 `GET /api/orders/{orderId}`

### 4.3 `GET /api/orders/history?customerId=CUST-001&limit=20` — F6 (**chứa lỗi #4**)

### 4.4 `GET /api/orders/bill-inquiry?partnerCode=EVN&billCode=...` — F2 bước S0

Chuyển tiếp xuống gRPC `InquireBill`.

### 4.5 `POST /api/orders/{orderId}/refund` — F4 (hoàn tiền chủ động, Ops)

```json
{ "reason": "CUSTOMER_REQUEST" }
```

### 4.6 Giữ nguyên từ Stage B

`GET /api/orders/ping`.

---

## 5. gRPC `PaymentBusinessService` — order → business (9091 / host 19091)

File contract: [`../../contracts/proto/payment.proto`](../../contracts/proto/payment.proto).
Sau khi sửa proto phải chạy `contracts/sync-proto.ps1` (hoặc `.sh`) để đồng bộ 2 bản sao.

```proto
service PaymentBusinessService {
  rpc AuthorizePayment      (AuthorizePaymentRequest)      returns (AuthorizePaymentResponse);
  rpc ExecutePartnerPayment (ExecutePartnerPaymentRequest) returns (ExecutePartnerPaymentResponse);
  rpc ConfirmPayment        (ConfirmPaymentRequest)        returns (ConfirmPaymentResponse);
  rpc ReversePayment        (ReversePaymentRequest)        returns (ReversePaymentResponse);
  rpc InquireBill           (InquireBillRequest)           returns (InquireBillResponse);
}
```

| RPC | Vai trò | Ghi DB | Publish Kafka |
|---|---|---|---|
| `AuthorizePayment` | Áp toàn bộ rule, tính phí, giữ tiền (ledger `PENDING`), cộng `daily_usage` | `payment_transactions`, `ledger_entries`, `daily_usage` | chỉ khi `REJECTED` (`PaymentFailed`) hoặc `HELD` (`PaymentHeld` — **lỗi #2 bỏ qua**) |
| `ExecutePartnerPayment` | Gọi `third-party` qua HTTP, lưu `partner_ref` | `payment_transactions.updated_at` | không |
| `ConfirmPayment` | Ledger `PENDING` → `POSTED`, txn → `CAPTURED` | `ledger_entries`, `account_balances` | `PaymentCompleted` |
| `ReversePayment` | Sinh cặp bút toán ngược, txn → `REVERSED`, trừ `daily_usage` | `ledger_entries`, `account_balances`, `daily_usage` | `PaymentRefunded` |
| `InquireBill` | Tra hoá đơn qua `third-party` (chỉ đọc) | không | không |

**Trạng thái trả về** `AuthorizePaymentResponse.status` ∈ `AUTHORIZED` · `HELD` · `REJECTED`.

> ⚠️ **Lỗi có chủ đích #6**: handler `AuthorizePayment` sẽ **bỏ qua field `currency`** — luôn coi số tiền là VND
> khi áp `R-AMOUNT-02` / `R-LIMIT-01` / `R-REVIEW-01`, vi phạm `R-CURRENCY-01`. Không sửa.

---

## 6. `ewallet-payment-business` — HTTP admin (8083 / host 18083)

| Method | Path | Mục đích |
|---|---|---|
| `GET` | `/admin/limits` | Đọc `limit_config` — dùng để đối chiếu spec drift (#1) |
| `GET` | `/admin/accounts/{customerId}/balance` | Số dư + `daily_usage` hôm nay — dùng verify demo |
| `GET` | `/admin/transactions/{orderId}` | Transaction + ledger entries của một order |
| `GET` | `/admin/ping` | Giữ từ Stage B |

`GET /admin/accounts/CUST-001/balance`:

```json
{
  "customerId": "CUST-001",
  "accountId": "00000000-0000-4000-8000-000000000001",
  "balance": 5500000,
  "currency": "VND",
  "dailyUsage": { "date": "2026-09-09", "totalAmount": 500000 }
}
```

---

## 7. `ewallet-third-party` (8084 / host 18084)

Ranh giới hệ ngoài. Chọn adapter theo `partner_config.service_type`.

### 7.1 `POST /api/thirdparty/execute`

```json
{
  "orderId": "0f2a1b3c-4d5e-4f60-8a71-b2c3d4e5f601",
  "partnerCode": "VNPAY",
  "paymentType": "TOP_UP",
  "amount": 500000,
  "currency": "VND",
  "accountRef": "9704xxxxxxxx1234",
  "billCode": null
}
```

```json
{
  "status": "SUCCESS",
  "partnerRef": "PS-9f3c8a1b",
  "reasonCode": "OK",
  "elapsedMs": 143
}
```

`status` ∈ `SUCCESS` · `DECLINED` · `TIMEOUT`. Timeout gọi partner = **3000ms** (`NFR-TIMEOUT-01`), retry 1 lần
với `attempt=2` ghi vào `partner_transactions.attempt`.

### 7.2 `POST /api/thirdparty/bill-inquiry`

```json
{ "partnerCode": "EVN", "billCode": "PE0123456789" }
```

```json
{
  "status": "FOUND",
  "billCode": "PE0123456789",
  "customerName": "Nguyen Van A",
  "period": "2026-08",
  "amount": 1250000,
  "currency": "VND",
  "billStatus": "UNPAID"
}
```

`status` ∈ `FOUND` · `NOT_FOUND` · `ALREADY_PAID`.

### 7.3 `GET /api/thirdparty/transactions/{orderId}`

Trả bản ghi `partner_transactions` (gồm `settled_at` do WebSocket cập nhật).

### 7.4 Giữ nguyên từ Stage B

`GET /api/thirdparty/ping`.

---

## 8. `partner-sim` (8090 / host 18090) — test double

### 8.1 `POST /partner/{code}/execute`

```json
{ "orderId": "0f2a...", "amount": 500000, "currency": "VND", "accountRef": "9704...", "billCode": null }
```

```json
{ "status": "SUCCESS", "partnerCode": "VNPAY", "partnerRef": "PS-9f3c8a1b", "settledAt": null }
```

Hành vi tất định theo `amount` — xem `00-domain-and-conventions.md` §11.5.

### 8.2 `POST /partner/{code}/bill-inquiry`

```json
{ "billCode": "PE0123456789" }
```

Dữ liệu hoá đơn in-memory — xem `00-domain-and-conventions.md` §11.4.

### 8.3 WebSocket `/ws/partner` — kết nối dài

`ewallet-third-party` mở 1 kết nối khi khởi động và giữ suốt vòng đời.

| Chiều | Frame |
|---|---|
| third-party → partner-sim | `{"type":"SUBSCRIBE","node":"ewallet-third-party"}` |
| third-party → partner-sim | `{"type":"WATCH","orderId":"0f2a...","partnerRef":"PS-9f3c8a1b"}` (gửi ngay sau khi execute thành công) |
| partner-sim → third-party | `{"type":"SETTLEMENT","orderId":"0f2a...","partnerRef":"PS-9f3c8a1b","status":"SETTLED","settledAt":"2026-09-09T08:31:22.6Z"}` (đẩy sau ~300ms) |
| hai chiều | `{"type":"PING"}` / `{"type":"PONG"}` mỗi 30s giữ kết nối |

Khi nhận `SETTLEMENT`, third-party cập nhật `partner_transactions.settled_at`. Mất kết nối → reconnect
backoff 1s/2s/5s, tối đa vô hạn (kết nối dài phải luôn có để trace thấy).

---

## 9. Kafka

| Hạng mục | Giá trị |
|---|---|
| Topic | `ewallet.payment.events` (1 partition cho demo) |
| DLT | `ewallet.payment.events.DLT` |
| Key | `orderId` (String) |
| Value | JSON String — schema ở `00-domain-and-conventions.md` §12 |
| Producer | `ewallet-payment-business` · `acks=all` · `enable.idempotence=true` |
| Consumer group 1 | `notification-cg` → `ewallet-notification` |
| Consumer group 2 | `order-status-cg` → `ewallet-payment-order` |
| Retry | 3 lần, backoff 1s/2s/4s, tăng header `x-attempt`; hết lượt → DLT |

---

## 10. SSE — `ewallet-notification` (8085 / host 18085)

### 10.1 `GET /api/notifications/stream?customerId=CUST-001`

`Content-Type: text/event-stream`. Giữ kết nối, heartbeat `:keep-alive` mỗi 15s.

```
event: payment
data: {"eventType":"PaymentCompleted","orderId":"0f2a...","amount":500000,"status":"COMPLETED","message":"Nap tien thanh cong 500.000d"}
```

### 10.2 `GET /api/notifications?customerId=CUST-001&limit=20`

Đọc `notification_outbox` + `notification_sent_log`.

### 10.3 Giữ nguyên từ Stage B

`GET /api/notifications/ping`.

---

## 11. Ma trận endpoint ↔ flow

| Endpoint / RPC | F1 | F2 | F3 | F4 | F5 | F6 |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `POST /api/wallet/topup` | ✅ | | | ✅ | | |
| `GET /api/wallet/bill` | | ✅ | | | | |
| `POST /api/wallet/bill/pay` | | ✅ | | ✅ | | |
| `POST /api/wallet/telco/topup` | | ✅ | | | | |
| `POST /api/wallet/transfer` | | | ✅ | ✅ | | |
| `GET /api/wallet/transactions` | | | | | | ✅ |
| `POST /api/orders` | ✅ | ✅ | ✅ | ✅ | | |
| `GET /api/orders/history` | | | | | | ✅ |
| `POST /api/orders/{id}/refund` | | | | ✅ | | |
| gRPC `AuthorizePayment` | ✅ | ✅ | ✅ | ✅ | | |
| gRPC `ExecutePartnerPayment` | ✅ | ✅ | | ✅ | | |
| gRPC `ConfirmPayment` | ✅ | ✅ | ✅ | | | |
| gRPC `ReversePayment` | | | | ✅ | | |
| gRPC `InquireBill` | | ✅ | | | | |
| `POST /api/thirdparty/execute` | ✅ | ✅ | | ✅ | | |
| `POST /api/thirdparty/bill-inquiry` | | ✅ | | | | |
| WS `/ws/partner` | ✅ | ✅ | | | | |
| Kafka `ewallet.payment.events` | ✅ | ✅ | ✅ | ✅ | ✅ | |
| SSE `/api/notifications/stream` | | | | | ✅ | |
