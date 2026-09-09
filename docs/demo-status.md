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

### ⬜ Còn lại của Stage C

`payment-order` (saga + lỗi #4) · `third-party` (adapter + WebSocket + lỗi #3) ·
`notification` (outbox + SSE + retry/DLT) · `mobileapp` (BFF) · `gateway` (route) · `partner-sim` (hành vi tất định).

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
