# Demo App — Kiến trúc & phạm vi

> Hệ thống "được đem ra quan sát" cho platform AI Quality Control.
> Người 2 phụ trách: 6 service Spring Boot + tài liệu nghiệp vụ + OTel + test suite + 3 collector.
> Đây là **source of truth** cho topology. Cập nhật file này khi đổi thiết kế.

---

## 1. Nguyên tắc

1. **Ít sửa code để quan sát.** App được instrument **chủ yếu bằng OpenTelemetry Java Agent (auto)**.
   Chỉ thêm: agent jar, biến môi trường OTLP, `logback` JSON. Không nhét span thủ công khắp nơi.
   Mô phỏng đúng tình huống "platform gắn vào giữa chừng khi ewallet đang phát triển".
2. **HTTP là chính.** gRPC / Kafka / kết nối dài chỉ để **chứng minh** collector thu được context ngoài HTTP.
3. **Có cả service có control flow và không có control flow** để thử bộ lọc call graph.
4. **Nhiều flow chia sẻ node.** Xương sống dùng chung → demo impact analysis.
5. **Lỗi có chủ đích là hợp đồng với platform.** Cuối kỳ platform phải bắt đúng 6 lỗi ở mục 6.

---

## 2. Sáu service (khoá)

| # | Service | Inbound | Outbound | Control flow | Database | Ghi chú tracing |
|---|---|---|---|---|---|---|
| 1 | **ewallet-gateway** | HTTP | HTTP → mobileapp | **Không** (routing, propagate trace context) | — | Root span mọi trace; span "không nghĩa nghiệp vụ" → indexer phải lọc |
| 2 | **ewallet-business-customer-mobileapp** | HTTP (BFF client-facing) | HTTP → payment-order | **Nhẹ** (validate, shape request, aggregate) | — | Nơi khởi tạo giao dịch; entry point nghiệp vụ thật |
| 3 | **ewallet-payment-order** | HTTP | **gRPC** → payment-business; Kafka consumer `ewallet.payment.events` (group `order-status-cg`) | **Nặng** (saga nhiều bước, nhánh compensation) | `orderdb` | Có control flow rõ; consumer group #2 trên topic dùng chung |
| 4 | **ewallet-payment-business** | **gRPC** (từ order) + HTTP (admin nội bộ) | HTTP → third-party; Kafka producer `ewallet.payment.events` | **Nặng** (limit, threshold, fee, balance) | `paymentdb` | Nơi tập trung business rule → chứa phần lớn lỗi có chủ đích |
| 5 | **ewallet-third-party** | HTTP | HTTP → partner-sim; **WebSocket** ↔ partner-sim (kết nối dài) | **Vừa** (rẽ nhánh theo loại đối tác / dịch vụ) | `thirdpartydb` | Ranh giới hệ ngoài; demo kết nối dài + latency biến động |
| 6 | **ewallet-notification** | Kafka consumer `ewallet.payment.events` (group `notification-cg`) | **SSE** stream → app (kết nối dài) | **Nhẹ** (rẽ nhánh theo loại event → kênh gửi) | `notifdb` | Trace xuyên message queue; consumer group #1 |

**partner-sim** (không tính là service demo — là test double): Spring Boot nhỏ, giả lập đối tác ngoài,
cung cấp HTTP + WebSocket, cấu hình được latency / tỉ lệ lỗi. Cho hệ tự chạy độc lập.

> Service 3, 4, 5, 6 (có DB) gắn thêm `database-quality-library` để thu database context — xem mục 7.

### Phủ giao thức
- **HTTP REST**: 1, 2, 3, 4 (admin), 5 — *trọng tâm*
- **gRPC**: 3 → 4 (`PaymentBusinessService.AuthorizePayment`)
- **Kafka (async)**: 4 → topic `ewallet.payment.events` → 6 (`notification-cg`) + 3 (`order-status-cg`)
- **Kết nối dài**: 6 SSE `/api/notifications/stream`; 5 ↔ partner-sim WebSocket
- **JDBC**: 3, 4, 5, 6

### Phủ control flow
- **Không**: 1 (gateway)
- **Nhẹ**: 2 (BFF), 6 (notification)
- **Vừa**: 5 (third-party)
- **Nặng**: 3 (order saga), 4 (business rules)

---

## 3. Kafka — một topic, hai consumer group

```
ewallet-payment-business  ──produce──►  topic: ewallet.payment.events
                                              │
                       ┌──────────────────────┼───────────────────────┐
                       ▼                                              ▼
        group: notification-cg                             group: order-status-cg
        → ewallet-notification                             → ewallet-payment-order
          (fan-out thông báo)                                (cập nhật trạng thái order async)
```

Event: `PaymentCompleted`, `PaymentFailed`, `PaymentHeld` (xem lỗi #2), `PaymentRefunded`.
Đổi schema event → ảnh hưởng **cả hai** consumer group theo cách khác nhau → demo phân tích ảnh hưởng khi thay đổi event.

Xử lý retry / DLQ: producer gắn header `x-attempt`; consumer lỗi → retry 3 lần → topic `ewallet.payment.events.DLT`.
Trace Analyzer phải phân biệt `first_attempt | retry | dead_letter` để baseline không lệch.

---

## 4. Năm flow nghiệp vụ (F1–F5)

| Flow | Đường đi (span path) | Đặc điểm demo |
|---|---|---|
| **F1 — Nạp tiền ví qua đối tác** | app → gateway → mobileapp → order (HTTP) → business (gRPC) → third-party (HTTP + WS) → Kafka → notification + order-status | Hop ra hệ ngoài; baseline vs regression latency; **chưa có test** (lỗi #3) |
| **F2 — Thanh toán hoá đơn / nạp telco** | như F1 | Rule khác F1 (loại dịch vụ, phí, tiền tệ) → spec drift; cross-currency chạm lỗi #6 |
| **F3 — Chuyển tiền P2P trong ví** | app → gateway → mobileapp → order → business (gRPC) → Kafka → notification | **Không** qua third-party → contrast nhánh; chạm lỗi #1 (hạn mức) |
| **F4 — Giao dịch lỗi / hoàn tiền** | app → gateway → mobileapp → order → business → (fail) → nhánh compensation ở order → Kafka `PaymentFailed`/`PaymentRefunded` → notification | Nhánh bù trừ; đường test hay bỏ sót → test gap |
| **F5 — Thông báo bất đồng bộ** | business → Kafka → notification (+ order-status) | Trace xuyên message queue, không chỉ HTTP; đuôi chung nhiều flow |

**Impact analysis kỳ vọng:** sửa `payment-business` → cả F1–F5; sửa `third-party` → chỉ F1, F2;
sửa `notification` → mọi flow nhưng chỉ nhánh đuôi; đổi event schema → notification + order-status.

---

## 5. Database (1 Postgres container, nhiều database)

| DB | Bảng chính | Service sở hữu |
|---|---|---|
| `orderdb` | `payment_orders`, `order_steps` | ewallet-payment-order |
| `paymentdb` | `accounts`, `account_balances`, `ledger_entries`, `daily_usage`, `limit_config` | ewallet-payment-business |
| `thirdpartydb` | `partner_config`, `partner_transactions` | ewallet-third-party |
| `notifdb` | `notification_outbox`, `notification_sent_log` | ewallet-notification |

Schema quản lý bằng Flyway (`src/main/resources/db/migration`). Seed data cho demo trong migration `V900__seed_demo.sql`.

---

## 6. Sáu lỗi có chủ đích (hợp đồng với platform)

| # | Loại | Vị trí | Chi tiết | Flow chạm | Platform bắt bằng |
|---|---|---|---|---|---|
| 1 | **Spec drift** | `payment-business` `LimitPolicy` | Hằng số `DAILY_TRANSFER_LIMIT = 100_000_000`; spec ghi **50.000.000đ** | F1,F2,F3 | Doc Indexer (rule) vs Code Indexer (hằng số) |
| 2 | **Nhánh quên publish event** | `payment-business`, nhánh `HELD` | amount > ngưỡng review → set status `HELD` rồi `return`, **không** produce `PaymentHeld` dù spec bắt buộc | F1,F2,F3 (giao dịch lớn) | Trace: flow HELD thiếu span `send`; notification + order-status không nhận |
| 3 | **Flow prod không có test** | `payment-business` / `third-party` đường `TOP_UP` | Flow F1 (nạp tiền) chạy thật, có trace, **0 test** | F1 | Coverage report (JaCoCo) vs Trace Analyzer: đường runtime không test |
| 4 | **DB anti-pattern (Topic #80)** | `payment-order` `GET /api/orders/history` | Lặp query theo từng order thay vì join/batch → N+1; cột `WHERE` không index | flow đọc lịch sử | `database-quality-library` (kế thừa #80): rule `N_PLUS_ONE` + `MISSING_INDEX`, kèm `calledFrom` |
| 5 | **Vi phạm NFR** | `payment-business`, nhánh `HELD` | `Thread.sleep(700)`; spec: API thanh toán phản hồi **< 500ms** | F1,F2,F3 | Trace latency p95 vs NFRConstraint (Doc Indexer) |
| 6 | **Drift mang qua gRPC** | `payment-business` gRPC handler | Bỏ qua field `currency` trong request → áp hạn mức VND cho mọi tiền tệ | F2 (cross-currency) | Chỉ thấy nếu collector thu được attribute của gRPC span |

Lỗi #2 và #5 nằm chung nhánh `HELD` — một nhánh, hai vấn đề.

---

## 7. Observability (auto, ít sửa code)

- **OTel Java Agent** gắn qua `JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`.
- Biến môi trường mỗi service:
  - `OTEL_SERVICE_NAME=<tên service>`
  - `OTEL_RESOURCE_ATTRIBUTES=service.namespace=ewallet,deployment.environment=demo,service.version=<ver>`
  - `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`
  - `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`
- Agent tự instrument: Spring MVC, WebClient/RestTemplate, JDBC (kèm `db.statement`), Kafka (producer/consumer, `messaging.*`), gRPC (`rpc.*`).
- **Log JSON**: `logstash-logback-encoder`; agent tự chèn `trace_id` / `span_id` vào MDC.
- **otel-collector** export song song: file `traces.jsonl` (đầu vào Trace Analyzer) + Jaeger (người xem).
- gRPC attribute (cho lỗi #6): bật `OTEL_INSTRUMENTATION_GRPC_CAPTURE_METADATA` / span attribute custom tối thiểu — ghi rõ đây là "knob" để demo thu context ngoài HTTP.

### Database context — `database-quality-library` (kế thừa Topic #80)

Repo: <https://github.com/quanglam04/database-quality-library>. Thư viện Java wrap `DataSource`
(`QualityDataSource`), intercept JDBC ở runtime — **không sửa business logic**. Đúng tinh thần "gắn vào giữa chừng".

- Gắn vào **4 service có DB**: `payment-order`, `payment-business`, `third-party`, `notification`.
  `gateway`, `mobileapp`, `partner-sim` không có DB → không gắn.
- Tích hợp: thêm JitPack dependency + file `application.properties` trên classpath (thư viện đọc file này,
  **không** đọc `application.yml`). Auto-configuration tự wrap `DataSource` khi có trong classpath.
- Thu thập: SQL pattern đã normalize, call count, p50/p95/p99 mỗi pattern, `calledFrom` (vị trí code),
  N+1, slow query, snapshot schema (`DatabaseMetaData`), EXPLAIN plan.
- Xuất: HTTP JSON `/report`, `/collected-queries`, `/schema-snapshot`, `/findings`, `/slow-queries`,
  `/project-info` trên port dashboard riêng mỗi service; + file JSON khi shutdown.
- **Collector mới** `db-quality-collector` (trong `collectors/`) poll các endpoint này → đẩy vào Knowledge Graph.
  Chi tiết: [`db-quality-integration.md`](db-quality-integration.md) và `collector-data-contract.md` mục 4.
- Lưu ý Flyway: thư viện khuyến nghị chạy migration trên `DataSource` gốc trước khi wrap; với Spring Boot
  auto-config, query hệ thống của Flyway có thể bị intercept → chấp nhận nhiễu nhẹ, hoặc để
  `quality.analysis.initial-delay` đủ dài. Ghi rõ trong stage C.

---

## 8. Cổng (ports)

| Thành phần | Port in-cluster / host |
|---|---|
| ewallet-gateway | 8080 / **18080** |
| ewallet-business-customer-mobileapp | 8081 / **18081** |
| ewallet-payment-order | 8082 / **18082** |
| ewallet-payment-business | 8083 (HTTP) / **18083** · 9091 (gRPC) / **19091** |
| ewallet-third-party | 8084 / **18084** |
| ewallet-notification | 8085 / **18085** |
| partner-sim | 8090 / **18090** |
| Postgres | 5432 |
| Kafka | 9092 (in-cluster) / 29092 (host) |
| otel-collector | 4317 (OTLP gRPC) / 4318 (OTLP HTTP) |
| Jaeger UI | 16686 |
| db-quality dashboard — order | 9876 in-cluster / 19082 host |
| db-quality dashboard — business | 9876 in-cluster / 19083 host |
| db-quality dashboard — third-party | 9876 in-cluster / 19084 host |
| db-quality dashboard — notification | 9876 in-cluster / 19085 host |

---

## 9. Cấu trúc thư mục

```
demo-app/
  docs/
    architecture.md                ← file này
    collector-data-contract.md     ← dữ liệu 3 collector đổ vào Knowledge Graph
    specs/                         ← tài liệu nghiệp vụ F1–F5 (đầu vào Doc Indexer)
    diagrams/                      ← Mermaid: container + sequence mỗi flow
    db-quality-integration.md      ← cách gắn database-quality-library vào 4 service DB
  contracts/proto/payment.proto    ← hợp đồng gRPC (order ↔ business)
  infra/
    postgres/init.sql              ← tạo 4 database
    otel-collector/config.yaml
    db-quality/reports/            ← nơi 4 service xuất quality-report.json (volume)
  ewallet-gateway/
  ewallet-business-customer-mobileapp/
  ewallet-payment-order/
  ewallet-payment-business/
  ewallet-third-party/
  ewallet-notification/
  partner-sim/
  docker-compose.yml
```

Mỗi service là **Maven project độc lập** (pom riêng, Dockerfile riêng) — mô phỏng mô hình đa repo.
Không có parent pom chung; proto dùng chung qua `../contracts/proto`.

---

## 10. Giai đoạn triển khai

| Stage | Nội dung | Trạng thái |
|---|---|---|
| A | Thiết kế + skeleton (docs, compose, infra, proto, README service) | ✅ |
| B | Scaffold 7 Maven project: pom, main class, `application.yml`, Dockerfile, logback, Flyway. 4 service DB thêm `database-quality-library` + `application.properties`. Swagger (springdoc) cho service HTTP. Compile + boot + ra trace + dashboard db-quality lên, chưa có nghiệp vụ | ✅ (verify: `docs/stage-b-verify.md` · trạng thái: `docs/demo-status.md`) |
| C | Business logic + 6 lỗi có chủ đích | ← tiếp theo |
| D | Spec F1–F5 (Markdown) + diagram Mermaid | |
| E | Test suite + JaCoCo coverage report (cố ý bỏ test đường F1 top-up) | |
| F | (nếu xin được service thật VDS) cài collector lên, tinh chỉnh dữ liệu thật | |
