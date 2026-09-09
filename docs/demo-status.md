# Demo hiện tại làm được gì (sau Stage B)

> Stage B = khung chạy được: 7 service boot, nói chuyện với nhau đủ mọi giao thức, sinh trace/log/DB-metrics.
> **Chưa có** business logic thật và 6 lỗi có chủ đích (đó là Stage C).
> Spec nghiệp vụ F1–F6 + sequence diagram + hướng dẫn chạy local: **đã xong** (Stage D) — xem [`specs/`](specs/README.md) và [`local-run.md`](local-run.md).

## ✅ Đang chạy được

### Hạ tầng (profile `infra`)
- **PostgreSQL 16** — 4 database `orderdb` / `paymentdb` / `thirdpartydb` / `notifdb`, mỗi service tự chạy Flyway `V1__init.sql` dựng bảng + seed tối thiểu.
- **Apache Kafka 3.8.1** (KRaft, không Zookeeper) — topic `ewallet.payment.events` tự tạo khi có consumer.
- **otel-collector** — nhận OTLP từ mọi service, xuất song song ra `infra/otel-collector/traces/traces.jsonl` (cho Trace Analyzer) + **Jaeger** (UI người xem, http://localhost:16686).

### 7 service Spring Boot (profile `all`) — host port dải 18xxx
| Service | Chạy gì ở Stage B |
|---|---|
| **ewallet-gateway** (18080) | Spring Cloud Gateway route `/api/**` → mobileapp, `/orders/**` → order. Không logic nghiệp vụ. |
| **ewallet-business-customer-mobileapp** (18081) | `GET /api/ping`, `GET /api/trace-test` (gọi xuống order → tạo trace 3 tầng). |
| **ewallet-payment-order** (18082) | `GET /api/orders/ping` (query `payment_orders`). Kafka consumer group `order-status-cg` đăng ký sẵn. gRPC client tới business khai báo sẵn (chưa gọi). |
| **ewallet-payment-business** (18083, gRPC 19091) | **gRPC server** `PaymentBusinessService.AuthorizePayment` — trả canned `AUTHORIZED`. `GET /admin/ping` (query `limit_config`). Kafka producer cấu hình sẵn (chưa publish). |
| **ewallet-third-party** (18084) | `GET /api/thirdparty/ping` (query `partner_config`, seed 3 đối tác VNPAY/EVN/VTELCO). WebSocket client + RestClient tới partner-sim khai báo sẵn. |
| **ewallet-notification** (18085) | Kafka **consumer group `notification-cg`** trên `ewallet.payment.events` (nhận → log). SSE `GET /api/notifications/stream` (mở kết nối dài, gửi event `hello`). `GET /api/notifications/ping`. |
| **partner-sim** (18090) | `POST /partner/{code}/execute` trả canned SUCCESS. WebSocket `/ws/partner` echo. |

### Giao thức đã chứng minh thu được trace/context
| Giao thức | Đường | Quan sát ở |
|---|---|---|
| HTTP REST | gateway → mobileapp → order (`/api/trace-test`) | Jaeger: 1 trace, 3 span service |
| gRPC | order → business (`AuthorizePayment`) — *khung, gọi thật ở Stage C* | server span `rpc.service=...PaymentBusinessService` |
| Kafka (async) | producer business → topic → consumer `notification-cg` + `order-status-cg` | messaging span, 2 consumer group / 1 topic |
| Kết nối dài | notification SSE `/stream`; third-party ↔ partner-sim WebSocket | span kết nối dài |
| JDBC | mọi service DB (query `*_ping`) | span `db.statement` + `database-quality-library` bắt SQL pattern |

### Observability (auto, không sửa business code)
- **OTel Java Agent 2.9.0** gắn qua `JAVA_TOOL_OPTIONS` — tự instrument Spring MVC, RestClient, JDBC, Kafka, gRPC.
- **Log JSON** (logback + logstash encoder), agent chèn `trace_id` / `span_id`.
- **database-quality-library** trên 4 service DB — dashboard 6 tab ở host 19082–19085, JSON `/report`, `/collected-queries`, `/schema-snapshot`... Đã thu: SQL pattern, call count, p50/95/99, `calledFrom`, snapshot schema.

### API docs
- **Swagger UI** mỗi service HTTP: `http://localhost:1808x/swagger-ui.html` (18081–18085, 18090).
- **OpenAPI JSON**: `/v3/api-docs`.

## 🔄 Stage C — đang làm

### ✅ `ewallet-payment-business` (xong, đã compile)

Service business rule + sổ cái. `mvn package` chạy sạch (60 file, kể cả protobuf sinh ra).

| Thành phần | Nội dung |
|---|---|
| Migration | `V2__business.sql` (bảng `payment_transactions`, `fx_rates`, cột `entry_type`/`status` cho `ledger_entries`) · `V900__seed_demo.sql` · `V901__seed_fx.sql` |
| Domain | `LimitPolicy`, `ReviewPolicy`, `FeePolicy`, `CurrencyConverter`, `PaymentType`, `ReasonCodes` |
| Sổ cái | `LedgerService` — authorize (PENDING) / capture (POSTED) / reverse (bút toán ngược, txn_id mới) |
| gRPC | `PaymentBusinessGrpcService` — đủ 5 method `AuthorizePayment` · `ExecutePartnerPayment` · `ConfirmPayment` · `ReversePayment` · `InquireBill` |
| Nghiệp vụ | `PaymentService` — saga phía business, áp đủ R-ACCOUNT / R-AMOUNT / R-LIMIT / R-BALANCE / R-REVIEW / R-FEE / R-LEDGER / R-USAGE |
| Ra ngoài | `ThirdPartyClient` (HTTP → third-party, quy mọi sự cố về DECLINED/TIMEOUT) |
| Kafka | `PaymentEventPublisher` — `PaymentCompleted` / `PaymentFailed` / `PaymentRefunded`, key = orderId |
| HTTP admin | `AdminController` — `/admin/ping` `/admin/limits` `/admin/accounts/{id}/balance` `/admin/transactions/{orderId}` |

**Lỗi có chủ đích đã cài (3/6 nằm ở service này, cộng #1 là 4):**

| Lỗi | Vị trí chính xác |
|---|---|
| #1 spec drift hạn mức | `domain/LimitPolicy.java` — `DAILY_TRANSFER_LIMIT = 100_000_000` |
| #2 nhánh HELD quên publish | `service/PaymentService.java` — nhánh `requiresManualReview` return không gọi `publishHeld()` |
| #5 vi phạm NFR < 500ms | `domain/ReviewPolicy.java` — `Thread.sleep(700)` |
| #6 gRPC bỏ qua `currency` | `grpc/PaymentBusinessGrpcService.java` — gán cứng `currency = "VND"` |

### ✅ `ewallet-payment-order` (xong, đã compile và chạy thật)

Orchestrator saga — service đầu tiên có API HTTP nghiệp vụ thật.

| Thành phần | Nội dung |
|---|---|
| Migration | `V2__business.sql` — cột saga cho `payment_orders`, `attempt`/`duration_ms` cho `order_steps`, unique index idempotency key. **Cố ý không index** `customer_id` và `order_steps.order_id` |
| Saga | `PaymentSagaOrchestrator` — S1 CREATE_ORDER → S2 AUTHORIZE → S3 PARTNER_EXECUTE (bỏ qua nếu không có `partnerCode`) → S4 CONFIRM, retry 3 lần; nhánh S3' COMPENSATE |
| gRPC client | `PaymentBusinessClient` — 5 RPC, có deadline riêng cho nhánh gọi đối tác |
| API | `POST /api/orders` · `GET /api/orders/{id}` · `GET /api/orders/history` · `GET /api/orders/bill-inquiry` · `POST /api/orders/{id}/refund` · `GET /api/orders/ping` |
| Kafka | `OrderStatusListener` — consumer group `order-status-cg`, idempotent theo `eventId`, không ghi đè trạng thái kết thúc |
| Dấu vết | `SagaStepRecorder` ghi `order_steps` bằng `REQUIRES_NEW` để dấu vết còn lại kể cả khi rollback |

**Lỗi có chủ đích #4 đã cài** ở `history/OrderHistoryService` — vòng lặp query + thiếu index.

### ✅ `ewallet-third-party` + `partner-sim` (xong, đã chạy thật)

Ranh giới ra hệ ngoài — mở khoá F1, F2 và lỗi #6.

| Thành phần | Nội dung |
|---|---|
| `third-party` migration | `V2__business.sql` — `service_type`, `bill_code`, `attempt`, `fail_reason` |
| Adapter | `TopupAdapter` · `BillAdapter` · `TelcoAdapter`, chọn theo `partner_config.service_type`; mỗi loại có kiểm tra riêng |
| Gọi đối tác | `PartnerSimClient` — timeout 3.000ms, thử lại 1 lần **chỉ khi quá hạn** (bị từ chối thì không gọi lại) |
| WebSocket | `PartnerWsClient` — kênh bền, gửi `WATCH` sau khi đối tác nhận lệnh, nhận `SETTLEMENT` rồi điền `settled_at` |
| API | `POST /api/thirdparty/execute` · `POST /api/thirdparty/bill-inquiry` · `GET /api/thirdparty/transactions/{orderId}` · `GET /api/thirdparty/ping` |
| `partner-sim` | Hành vi **tất định** theo số tiền (đuôi 999 từ chối, đuôi 888 treo 5s), hoá đơn EVN in-memory, WebSocket đẩy `SETTLEMENT` sau ~300ms |

**Lỗi có chủ đích #6 đã tái hiện qua HTTP thật**: 15.000 USD (= 375 triệu) được duyệt với
`amountVnd = 15000`. **#3**: `TopupAdapter` đã có, phần "không test" thuộc Stage E.

### ⬜ Còn lại của Stage C

Chỉ còn `notification` (outbox + SSE + retry/DLT) để hoàn tất F5.

## ⛔ Chưa làm (Stage C trở đi)

- Business logic thật: transfer / top-up / bill / P2P / refund, saga nhiều bước, state machine, tính phí, ghi sổ kép, check hạn mức & ngưỡng.
- **6 lỗi có chủ đích** (spec drift hạn mức, nhánh HELD quên publish event, flow top-up không test, N+1 query, vi phạm NFR < 500ms, gRPC bỏ qua `currency`).
- gRPC client của order gọi thật sang business.
- business publish event thật lên Kafka; DLT + phân loại retry.
- third-party mở WebSocket bền + adapter theo `partner_code`.
- notification ghi `notification_outbox` + đẩy SSE khi có event thật.
- 6 flow F1–F6 chạy end-to-end (spec đã có ở `specs/`, code là Stage C).
- Test suite & coverage (Stage E).
- 3 collector (Code Indexer / Doc Indexer / Trace Analyzer) + db-quality-collector — nằm ở `../collectors/`, chưa bắt đầu.
