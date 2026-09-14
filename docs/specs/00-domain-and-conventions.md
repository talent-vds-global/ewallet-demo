# 00 — Miền nghiệp vụ, quy ước & mô hình dữ liệu

> Nền chung cho mọi flow F1–F6. Đọc file này trước khi đọc spec từng flow.
> Đây là **đầu vào của Doc Indexer** và là **đặc tả để code Stage C**. Giá trị trong file này là giá trị **ĐÚNG theo spec**;
> code có thể lệch — phần lệch là lỗi có chủ đích (xem `architecture.md` §6).

---

## 1. Bối cảnh nghiệp vụ

Ví điện tử cho khách hàng cá nhân. Khách nạp tiền vào ví qua đối tác, dùng số dư ví để thanh toán
hoá đơn / nạp điện thoại, chuyển tiền cho ví khác, và nhận thông báo cho mọi biến động số dư.

Hệ thống chia theo mô hình microservice: **BFF** hứng client, **payment-order** điều phối giao dịch (saga),
**payment-business** giữ toàn bộ business rule + sổ cái, **third-party** là ranh giới ra hệ ngoài,
**notification** phát thông báo bất đồng bộ.

## 2. Actor

| Actor | Mô tả |
|---|---|
| **Khách hàng (Customer)** | Người dùng app ví. Định danh bằng `customerId` (vd `CUST-001`). |
| **Đối tác (Partner)** | Ngân hàng / nhà cung cấp dịch vụ ngoài (VNPAY, EVN, VTELCO). Giả lập bằng `partner-sim`. |
| **Hệ thống (System)** | Consumer Kafka, job đối soát — chạy không có người bấm. |
| **Ops / Lead** | Tra cứu đơn, hạn mức qua endpoint `/admin/**` (nội bộ). |

## 3. Sáu flow

| Mã | Tên | Loại | Service đi qua | File |
|---|---|---|---|---|
| **F1** | Nạp tiền ví qua đối tác | ghi | gateway → mobileapp → order → business → third-party → partner-sim → Kafka → notification | [F1](F1-topup-partner.md) |
| **F2** | Thanh toán hoá đơn / nạp telco | ghi | như F1 + bước tra cứu hoá đơn | [F2](F2-bill-telco.md) |
| **F3** | Chuyển tiền P2P trong ví | ghi | gateway → mobileapp → order → business → Kafka → notification | [F3](F3-p2p-transfer.md) |
| **F4** | Giao dịch lỗi & hoàn tiền (compensation) | ghi | như F1 + nhánh bù trừ ở order | [F4](F4-failure-refund.md) |
| **F5** | Thông báo bất đồng bộ | đọc/ghi | business → Kafka → notification (SSE) + order | [F5](F5-async-notification.md) |
| **F6** | Tra cứu lịch sử giao dịch | đọc | gateway → mobileapp → order (DB) | [F6](F6-transaction-history.md) |

F1–F5 là 5 flow khoá trong `architecture.md` §4. **F6 được tách ra thành flow riêng** vì lỗi có chủ đích #4
(N+1 query) nằm ở đường đọc lịch sử, không thuộc F1–F5; `architecture.md` §6 đã gọi nó là "flow đọc lịch sử".

## 4. Từ vựng miền

| Thuật ngữ | Nghĩa trong hệ này |
|---|---|
| **Order** (`payment_orders`) | Một yêu cầu giao dịch của khách. Do `payment-order` sở hữu. Có vòng đời saga. |
| **Transaction** (`payment_transactions`) | Bút toán nghiệp vụ tương ứng order, do `payment-business` sở hữu. Hai pha: `AUTHORIZED` → `CAPTURED`. |
| **Ledger entry** | Bút toán kép. Mỗi transaction sinh **đúng 2** dòng: 1 `DEBIT`, 1 `CREDIT`, cùng `txn_id`. |
| **Authorize** | Kiểm tra rule + giữ tiền (ledger `PENDING`). Chưa phải kết thúc. |
| **Capture / Confirm** | Chốt giao dịch: ledger `PENDING` → `POSTED`, publish event. |
| **Reverse** | Bù trừ: sinh 2 bút toán ngược chiều với `txn_id` mới, loại `REFUND`. |
| **Hold (HELD)** | Giao dịch vượt ngưỡng rà soát → treo chờ duyệt thủ công. Tiền **vẫn bị giữ**. |
| **Daily usage** | Tổng giá trị (quy đổi VND) các giao dịch của khách trong ngày, dùng để so hạn mức. |
| **Partner ref** | Mã tham chiếu do đối tác trả về, dùng đối soát. |
| **Settlement** | Đối tác xác nhận đã quyết toán — đến qua WebSocket, bất đồng bộ, sau khi HTTP đã trả. |

## 5. Quy ước tiền tệ & số

- Đơn vị: **VND, số nguyên đồng** (`BIGINT`). Không dùng số thực cho tiền.
- `currency` mặc định `VND`. Hỗ trợ `USD` cho ca cross-currency (F2).
- Quy đổi về VND trước khi so hạn mức: bảng `fx_rates`. Seed: `VND = 1`, `USD = 25_000`.
- Làm tròn phí: **làm tròn lên bội số 1.000đ**.

## 6. Quy ước API (client-facing)

| Hạng mục | Quy ước |
|---|---|
| Base URL demo | `http://localhost:18080` (gateway) |
| Prefix client | `/api/wallet/**` → mobileapp · `/api/notifications/**` → notification · `/orders/**` → order (nội bộ/Ops) |
| Content type | `application/json; charset=utf-8` |
| Idempotency | Mọi request **ghi** bắt buộc header `X-Idempotency-Key` (UUID do client sinh) |
| Correlation | `traceparent` do OTel agent tự gắn. Response luôn có `X-Trace-Id`. |
| Khách hàng | Header `X-Customer-Id` **hoặc** field `customerId` trong body (demo không có auth thật) |
| Mã lỗi | `{"code": "...", "message": "...", "traceId": "..."}` |

### HTTP status quy ước

| Status | Khi nào |
|---|---|
| `200 OK` | Giao dịch hoàn tất (`COMPLETED`) hoặc truy vấn thành công |
| `202 Accepted` | Giao dịch được nhận nhưng chưa kết thúc — trạng thái `HELD` (chờ duyệt) |
| `400 Bad Request` | Sai định dạng, thiếu field, thiếu `X-Idempotency-Key` |
| `409 Conflict` | Trùng `X-Idempotency-Key` với payload khác |
| `422 Unprocessable Entity` | Vi phạm business rule (`LIMIT_EXCEEDED`, `INSUFFICIENT_FUNDS`, ...) |
| `502 Bad Gateway` | Đối tác từ chối / lỗi (`PARTNER_DECLINED`) sau khi đã bù trừ xong |
| `504 Gateway Timeout` | Đối tác timeout, đã bù trừ xong |

### Bảng mã lý do (`reasonCode`)

| Mã | Nghĩa | Trả về ở |
|---|---|---|
| `OK` | Hợp lệ | mọi flow |
| `ACCOUNT_NOT_FOUND` | Không tìm thấy ví nguồn/đích | F1–F4 |
| `ACCOUNT_INACTIVE` | Ví bị khoá | F1–F4 |
| `AMOUNT_TOO_SMALL` | `amount` < hạn mức tối thiểu | F1–F3 |
| `AMOUNT_TOO_LARGE` | `amount` > hạn mức mỗi giao dịch | F1–F3 |
| `LIMIT_EXCEEDED` | Vượt hạn mức ngày | F1–F3 |
| `INSUFFICIENT_FUNDS` | Số dư không đủ (gồm phí) | F2, F3 |
| `CURRENCY_NOT_SUPPORTED` | Tiền tệ không có trong `fx_rates` | F2 |
| `MANUAL_REVIEW` | Vượt ngưỡng rà soát → `HELD` | F1–F3 |
| `PARTNER_DECLINED` | Đối tác từ chối | F1, F2, F4 |
| `PARTNER_TIMEOUT` | Đối tác không phản hồi trong hạn | F1, F2, F4 |
| `BILL_NOT_FOUND` | Không tra được hoá đơn | F2 |
| `BILL_ALREADY_PAID` | Hoá đơn đã thanh toán | F2 |
| `DUPLICATE_REQUEST` | Trùng idempotency key | F1–F4 |

## 7. Trạng thái

### 7.1 Order (`payment_orders.status`) — sở hữu bởi `payment-order`

| Trạng thái | Nghĩa |
|---|---|
| `CREATED` | Đã ghi nhận yêu cầu, chưa gọi ai |
| `AUTHORIZING` | Đang gọi gRPC `AuthorizePayment` |
| `AUTHORIZED` | Business đã duyệt + giữ tiền |
| `EXECUTING` | Đang gọi đối tác (chỉ F1/F2) |
| `CONFIRMING` | Đang gọi `ConfirmPayment` |
| `COMPLETED` | Kết thúc thành công (terminal) |
| `HELD` | Treo chờ duyệt thủ công (terminal tạm) |
| `REJECTED` | Business từ chối, chưa giữ tiền (terminal) |
| `COMPENSATING` | Đang bù trừ sau lỗi ở bước sau authorize |
| `REFUNDED` | Đã bù trừ xong, tiền đã trả lại (terminal) |
| `FAILED` | Thất bại, không cần/không thể bù trừ (terminal) |

### 7.2 Transaction (`payment_transactions.status`) — sở hữu bởi `payment-business`

`AUTHORIZED` → `CAPTURED` (confirm) · `AUTHORIZED` → `REVERSED` (reverse) · `HELD` · `REJECTED`.

### 7.3 Ledger entry (`ledger_entries.status`)

`PENDING` (khi authorize) → `POSTED` (khi capture) · `REVERSED` (khi reverse, đồng thời sinh cặp bút toán mới `POSTED`).

Sơ đồ trạng thái đầy đủ: [`../diagrams/01-state-machines.md`](../diagrams/01-state-machines.md).

## 8. Business rule dùng chung

> Quy ước mã: `R-<NHÓM>-<số>`. `severity`: `must` (bắt buộc, vi phạm = lỗi) · `should` (khuyến nghị).
> Rule riêng của từng flow nằm trong file flow tương ứng.

| Mã | Điều kiện | Hành động | Severity | Áp cho |
|---|---|---|---|---|
| `R-IDEM-01` | Request ghi không có `X-Idempotency-Key` | Từ chối `400` | must | F1–F4 |
| `R-IDEM-02` | Trùng `X-Idempotency-Key` và payload giống hệt | Trả lại **kết quả cũ**, không tạo order mới | must | F1–F4 |
| `R-IDEM-03` | Trùng `X-Idempotency-Key` nhưng payload khác | Từ chối `409 DUPLICATE_REQUEST` | must | F1–F4 |
| `R-ACCOUNT-01` | Ví nguồn không tồn tại | Từ chối `ACCOUNT_NOT_FOUND` | must | F1–F4 |
| `R-ACCOUNT-02` | Ví nguồn `status != ACTIVE` | Từ chối `ACCOUNT_INACTIVE` | must | F1–F4 |
| `R-AMOUNT-01` | `amount` < **10.000đ** | Từ chối `AMOUNT_TOO_SMALL` | must | F1–F3 |
| `R-AMOUNT-02` | `amount` > hạn mức mỗi giao dịch theo loại (TOP_UP 50.000.000đ · BILL 50.000.000đ · P2P 30.000.000đ) | Từ chối `AMOUNT_TOO_LARGE` | must | F1–F3 |
| `R-LIMIT-01` | Tổng giá trị giao dịch trong ngày (quy đổi VND, gồm cả giao dịch đang giữ) **vượt 50.000.000đ** | Từ chối `LIMIT_EXCEEDED` | must | F1–F3 |
| `R-REVIEW-01` | `amount` quy đổi VND **≥ 20.000.000đ** | Đặt trạng thái `HELD`, giữ tiền, **publish event `PaymentHeld`** | must | F1–F3 |
| `R-CURRENCY-01` | `currency != VND` | Quy đổi sang VND theo `fx_rates` **trước khi** áp `R-AMOUNT-02`, `R-LIMIT-01`, `R-REVIEW-01`. Không có tỉ giá → `CURRENCY_NOT_SUPPORTED` | must | F1–F3 |
| `R-BALANCE-01` | `số dư ví nguồn < amount + fee` | Từ chối `INSUFFICIENT_FUNDS` | must | F2, F3 |
| `R-LEDGER-01` | Mọi transaction được authorize | Ghi **đúng 2** `ledger_entries` (1 DEBIT + 1 CREDIT) cùng `txn_id`, tổng bằng 0 | must | F1–F4 |
| `R-LEDGER-02` | Bù trừ một transaction | Ghi 2 bút toán **ngược chiều**, `txn_id` mới, `entry_type=REFUND`, và **không** sửa bút toán gốc | must | F4 |
| `R-USAGE-01` | Transaction được authorize hoặc HELD | Cộng `amount_vnd` vào `daily_usage` của khách trong ngày | must | F1–F3 |
| `R-USAGE-02` | Transaction bị reverse | Trừ `amount_vnd` khỏi `daily_usage` của ngày gốc | must | F4 |
| `R-EVENT-01` | Transaction vào trạng thái kết thúc (`CAPTURED`/`REJECTED`/`HELD`/`REVERSED`) | Publish **đúng 1** event lên `ewallet.payment.events` | must | F1–F5 |
| `R-EVENT-02` | Publish event | `key = orderId` để mọi event của một order về cùng partition (giữ thứ tự) | must | F1–F5 |

### Bảng phí (`R-FEE-*`)

| Mã | Loại | Công thức | Ghi chú |
|---|---|---|---|
| `R-FEE-01` | `TOP_UP` | `0` | Đối tác chịu phí |
| `R-FEE-02` | `BILL` | `min(max(round_up_1000(amount × 0.5%), 1.000), 5.000)` | Sàn 1.000đ, trần 5.000đ |
| `R-FEE-03` | `TELCO` | `0` | Khuyến mại |
| `R-FEE-04` | `P2P` | `0` nếu `amount ≤ 2.000.000`, ngược lại `2.200` | |
| `R-FEE-05` | `REFUND` | `0` | Hoàn tiền không thu phí; phí gốc **được hoàn lại** |

Phí luôn trừ vào **ví nguồn**, ghi thành bút toán riêng (`entry_type=FEE`) về tài khoản `SYSTEM_FEE`.

## 9. Cấu hình hạn mức (giá trị đúng theo spec)

| Khoá | Giá trị spec | Nơi đọc |
|---|---|---|
| `DAILY_TRANSFER_LIMIT` | **50.000.000 VND** | `limit_config` (paymentdb) |
| `REVIEW_THRESHOLD` | **20.000.000 VND** | `limit_config` |
| `MIN_TXN_AMOUNT` | 10.000 VND | `limit_config` |
| `MAX_TXN_TOP_UP` | 50.000.000 VND | `limit_config` |
| `MAX_TXN_BILL` | 50.000.000 VND | `limit_config` |
| `MAX_TXN_P2P` | 30.000.000 VND | `limit_config` |
| `PARTNER_TIMEOUT_MS` | 3.000 ms | cấu hình service |

> ⚠️ **Lỗi có chủ đích #1**: Stage C sẽ code `LimitPolicy` đọc hằng số `DAILY_TRANSFER_LIMIT = 100_000_000`
> **trong code** thay vì đọc bảng `limit_config`. Spec (file này) và DB seed đều ghi 50.000.000.
> Đây là spec drift để platform phát hiện — **không sửa** khi code Stage C.

## 10. Mô hình dữ liệu đích

Schema hiện có (`V1__init.sql`) là khung Stage B. Stage C bổ sung bằng migration `V2__business.sql` mỗi service.
Dưới đây là **schema đích**.

### 10.1 `orderdb` — ewallet-payment-order

```sql
-- V2__business.sql
ALTER TABLE payment_orders
    ADD COLUMN idempotency_key   VARCHAR(80),
    ADD COLUMN source_account_id UUID,
    ADD COLUMN dest_account_id   UUID,
    ADD COLUMN dest_customer_id  VARCHAR(64),
    ADD COLUMN bill_code         VARCHAR(64),
    ADD COLUMN fee               BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN amount_vnd        BIGINT,
    ADD COLUMN txn_id            UUID,
    ADD COLUMN partner_ref       VARCHAR(64),
    ADD COLUMN reason_code       VARCHAR(32);

CREATE UNIQUE INDEX uq_orders_idem ON payment_orders(idempotency_key);
-- CHỦ Ý: KHÔNG tạo index trên payment_orders(customer_id) và order_steps(order_id) — phục vụ lỗi #4.

ALTER TABLE order_steps
    ADD COLUMN attempt     INT NOT NULL DEFAULT 1,
    ADD COLUMN duration_ms BIGINT;
```

`order_steps.step_name` ∈ `CREATE_ORDER` · `AUTHORIZE` · `PARTNER_EXECUTE` · `CONFIRM` · `COMPENSATE` · `EVENT_APPLIED`.

### 10.2 `paymentdb` — ewallet-payment-business

```sql
-- V2__business.sql
CREATE TABLE payment_transactions (
    txn_id            UUID PRIMARY KEY,
    order_id          UUID         NOT NULL,
    customer_id       VARCHAR(64)  NOT NULL,
    payment_type      VARCHAR(16)  NOT NULL,   -- TOP_UP | BILL | TELCO | P2P | REFUND
    amount            BIGINT       NOT NULL,
    fee               BIGINT       NOT NULL DEFAULT 0,
    currency          VARCHAR(8)   NOT NULL DEFAULT 'VND',
    amount_vnd        BIGINT       NOT NULL,   -- quy đổi theo R-CURRENCY-01
    source_account_id UUID,
    dest_account_id   UUID,
    status            VARCHAR(16)  NOT NULL,   -- AUTHORIZED | CAPTURED | REVERSED | REJECTED | HELD
    reason_code       VARCHAR(32),
    partner_ref       VARCHAR(64),
    partner_code      VARCHAR(32),
    counterparty_customer_id VARCHAR(64),      -- người nhận, chỉ P2P
    reversed_txn_id   UUID,                    -- trỏ về txn gốc nếu đây là bút toán bù trừ
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_order ON payment_transactions(order_id);
CREATE INDEX idx_txn_customer_date ON payment_transactions(customer_id, created_at);

ALTER TABLE ledger_entries
    ADD COLUMN entry_type VARCHAR(16) NOT NULL DEFAULT 'PAYMENT',  -- PAYMENT | FEE | REFUND
    ADD COLUMN status     VARCHAR(12) NOT NULL DEFAULT 'PENDING';  -- PENDING | POSTED | REVERSED

CREATE TABLE fx_rates (
    currency    VARCHAR(8) PRIMARY KEY,
    rate_to_vnd BIGINT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 10.3 `thirdpartydb` — ewallet-third-party

```sql
-- V2__business.sql
ALTER TABLE partner_transactions
    ADD COLUMN service_type VARCHAR(24),
    ADD COLUMN bill_code    VARCHAR(64),
    ADD COLUMN attempt      INT NOT NULL DEFAULT 1,
    ADD COLUMN fail_reason  VARCHAR(64);
```

### 10.4 `notifdb` — ewallet-notification

```sql
-- V2__business.sql
ALTER TABLE notification_outbox
    ADD COLUMN order_id  UUID,
    ADD COLUMN event_id  UUID,
    ADD COLUMN attempt   INT NOT NULL DEFAULT 1;

-- Phai gom customer_id: P2P bao cho ca nguoi gui lan nguoi nhan qua cung kenh PUSH (R-NOTIF-02).
CREATE UNIQUE INDEX uq_outbox_event_channel_customer
    ON notification_outbox(event_id, channel, customer_id);
```

## 11. Dữ liệu seed cho demo

Nằm ở `V900__seed_demo.sql` của mỗi service (chạy sau mọi migration khác).

### 11.1 Ví khách hàng (`paymentdb.accounts` + `account_balances`)

| customerId | Tên | accountId | Số dư ban đầu | Dùng cho |
|---|---|---|---|---|
| `CUST-001` | Nguyễn Văn A | `00000000-0000-4000-8000-000000000001` | 5.000.000 | F1, F2 happy path |
| `CUST-002` | Trần Thị B | `00000000-0000-4000-8000-000000000002` | 1.000.000 | Người nhận F3 |
| `CUST-003` | Lê Văn C | `00000000-0000-4000-8000-000000000003` | 300.000.000 | Demo hạn mức (#1) & HELD (#2, #5) |
| `CUST-004` | Phạm Thị D | `00000000-0000-4000-8000-000000000004` | 50.000 | Demo `INSUFFICIENT_FUNDS` (F4) |
| `CUST-DLQ` | Vũ Văn E | `00000000-0000-4000-8000-000000000005` | 10.000.000 | Demo retry + DLT ở notification (F5) |

### 11.2 Tài khoản hệ thống

| Vai trò | accountId | account_type |
|---|---|---|
| Treo nạp tiền (nguồn của TOP_UP) | `00000000-0000-4000-8000-0000000000f1` | `SYSTEM_SUSPENSE` |
| Thu phí | `00000000-0000-4000-8000-0000000000f2` | `SYSTEM_FEE` |
| Quyết toán VNPAY | `00000000-0000-4000-8000-0000000000a1` | `PARTNER_SETTLE` |
| Quyết toán EVN | `00000000-0000-4000-8000-0000000000a2` | `PARTNER_SETTLE` |
| Quyết toán VTELCO | `00000000-0000-4000-8000-0000000000a3` | `PARTNER_SETTLE` |

### 11.3 Đối tác (`thirdpartydb.partner_config`, đã seed ở V1)

`VNPAY` (TOPUP) · `EVN` (BILL) · `VTELCO` (TELCO) — tất cả trỏ `http://partner-sim:8090`.

### 11.4 Hoá đơn giả lập (in-memory trong `partner-sim`)

| partnerCode | billCode | Số tiền | Trạng thái |
|---|---|---|---|
| `EVN` | `PE0123456789` | 1.250.000 | `UNPAID` |
| `EVN` | `PE0999999999` | 850.000 | `PAID` → trả `BILL_ALREADY_PAID` |
| `EVN` | bất kỳ mã khác | — | `BILL_NOT_FOUND` |
| `VTELCO` | số thuê bao 10 chữ số | do client nhập | luôn hợp lệ |

### 11.5 Hành vi tất định của `partner-sim` (để demo lặp lại được)

| Điều kiện | Hành vi |
|---|---|
| `amount % 1000 == 999` | Trả `DECLINED` / `PARTNER_DECLINED` → kích hoạt nhánh bù trừ F4 |
| `amount % 1000 == 888` | Ngủ 5.000ms → third-party timeout (3.000ms) → `PARTNER_TIMEOUT` |
| còn lại | `SUCCESS`, độ trễ ngẫu nhiên 50–250ms |

Ngoài ra `partner-sim.latency-ms` / `fail-rate` / `timeout-rate` trong `application.yml` vẫn dùng được để
bơm nhiễu ngẫu nhiên khi cần tạo dữ liệu baseline/regression.

## 12. Event Kafka dùng chung

Topic `ewallet.payment.events` · key = `orderId` · value = JSON (String serializer) · DLT `ewallet.payment.events.DLT`.

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

| Header | Ý nghĩa |
|---|---|
| `eventType` | Lặp lại `eventType` trong body để consumer lọc không cần deserialize |
| `x-attempt` | Số lần thử (1 = lần đầu). Trace Analyzer dùng để phân loại `first_attempt / retry / dead_letter` |
| `traceparent` | OTel agent tự gắn — giữ trace xuyên message queue |

| eventType | Publish khi | Consumer quan tâm |
|---|---|---|
| `PaymentCompleted` | Transaction `CAPTURED` | notification (báo thành công), order (chốt `COMPLETED`) |
| `PaymentFailed` | Transaction `REJECTED` hoặc order `FAILED` | notification, order |
| `PaymentHeld` | Transaction `HELD` (**R-REVIEW-01**) | notification (báo chờ duyệt), order (chốt `HELD`) |
| `PaymentRefunded` | Transaction `REVERSED` | notification, order |

> ⚠️ **Lỗi có chủ đích #2**: nhánh `HELD` trong Stage C sẽ `return` mà **không** publish `PaymentHeld`,
> dù `R-EVENT-01` + `R-REVIEW-01` bắt buộc. Không sửa.

## 13. NFR dùng chung

| Mã | Flow | metric | operator | threshold | unit |
|---|---|---|---|---|---|
| `NFR-LAT-01` | F1, F2, F3 | `latency_p95` của gRPC `AuthorizePayment` | `<` | 500 | ms |
| `NFR-LAT-02` | F1, F2 | `latency_p95` end-to-end tại gateway | `<` | 2000 | ms |
| `NFR-LAT-03` | F3 | `latency_p95` end-to-end tại gateway | `<` | 800 | ms |
| `NFR-LAT-04` | F6 | `latency_p95` của `GET /api/orders/history` | `<` | 300 | ms |
| `NFR-LAT-05` | F5 | `latency_p95` từ lúc publish event tới lúc ghi `notification_sent_log` | `<` | 5000 | ms |
| `NFR-TIMEOUT-01` | F1, F2 | timeout gọi đối tác | `<=` | 3000 | ms |
| `NFR-ERR-01` | F1–F3 | `error_rate` (5xx) | `<` | 1 | percent |

> ⚠️ **Lỗi có chủ đích #5**: nhánh `HELD` sẽ có `Thread.sleep(700)` → vi phạm `NFR-LAT-01`.
> ⚠️ **Lỗi có chủ đích #4**: `GET /api/orders/history` N+1 → vi phạm `NFR-LAT-04` khi dữ liệu nhiều.

## 14. Quy ước đặt tên code (Stage C)

| Service | Package gốc | Class chính dự kiến |
|---|---|---|
| mobileapp | `com.ewallet.mobileapp` | `web.WalletController`, `client.OrderClient`, `dto.*` |
| order | `com.ewallet.order` | `web.OrderController`, `saga.PaymentSagaOrchestrator`, `saga.SagaStepRecorder`, `grpc.PaymentBusinessClient`, `kafka.OrderStatusListener`, `history.OrderHistoryService`, `repo.*` |
| business | `com.ewallet.payment` | `grpc.PaymentBusinessGrpcService`, `domain.LimitPolicy`, `domain.FeePolicy`, `domain.ReviewPolicy`, `domain.CurrencyConverter`, `ledger.LedgerService`, `client.ThirdPartyClient`, `kafka.PaymentEventPublisher`, `web.AdminController`, `repo.*` |
| third-party | `com.ewallet.thirdparty` | `web.ThirdPartyController`, `partner.PartnerAdapter` (+`TopupAdapter`,`BillAdapter`,`TelcoAdapter`), `ws.PartnerWsClient`, `repo.*` |
| notification | `com.ewallet.notification` | `kafka.PaymentEventListener`, `service.NotificationRouter`, `service.NotificationSender`, `web.NotificationStreamController`, `repo.*` |

Đặt hằng số nghiệp vụ ở class `*Policy` để Code Indexer trích được và đối chiếu với `BusinessRule` (lỗi #1, #6).
