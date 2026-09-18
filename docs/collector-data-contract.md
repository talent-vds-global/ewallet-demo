# Hợp đồng dữ liệu: Collector → Knowledge Graph

> Collector do Người 2 phụ trách. File này định nghĩa **collector ăn vào gì** và **nhả ra gì**.
> Knowledge Graph (PostgreSQL, do Người khác phụ trách) hợp nhất các nguồn thành: Code ↔ Flow ↔ Spec ↔ Runtime.
> Định dạng trao đổi: JSON theo `{ "nodes": [...], "edges": [...], "source": {...} }`.
>
> **Cập nhật 2026-09-17** (đối chiếu với trace/log thật của demo): thêm collector #5 Coverage, node `Constant` /
> `TestCase` / `LogFact`, nguồn trace qua interface `TraceSource` (file trước, Tempo từ Stage F), sửa mô tả định dạng
> `traces.jsonl`, lọc false positive N+1. Kế hoạch triển khai nền tảng: `../../docs/platform-plan.md` (workspace v-quality).

---

## Quy ước node / edge chung

Mỗi node: `{ "id", "type", "props": {...}, "source_ref": {...} }`.
`id` ổn định giữa các lần chạy (idempotent). Quy tắc `id`:

| Loại | Công thức `id` |
|---|---|
| Service | `svc:<service.name>` |
| Endpoint (HTTP) | `ep:<service.name>:<HTTP_METHOD> <route>` |
| RpcMethod (gRPC) | `rpc:<service.name>:<grpc.service>/<grpc.method>` |
| Method (code) | `mth:<service.name>:<fqcn>#<method>(<param types>)` |
| Constant (code) | `const:<service.name>:<fqcn>.<FIELD_NAME>` |
| TestCase (code) | `test:<service.name>:<fqcn>#<method>` (lớp `@Nested` viết `Outer$Inner`) |
| Table | `tbl:<db>:<schema>.<table>` |
| Topic | `topic:<cluster>:<topic>` |
| ConsumerGroup | `cg:<topic>:<group.id>` |
| Flow (spec) | `flow:<slug>` |
| BusinessRule | `rule:<flow slug>:<rule code>` (rule dùng chung: `rule:common:<rule code>`) |
| NFRConstraint | `nfr:<flow slug>:<metric>` |
| FlowObservation (runtime) | `obs:<flow slug>:<outcome>:<window start ISO>` |
| LogFact (runtime) | `log:<trace_id>:<span_id>:<seq>` |
| CoverageStat (test) | `cov:<run_id>:<Method id>` |

Edge: `{ "from", "to", "type", "props": {...} }`.

Mọi lô JSON mang `source = { "collector", "run_id", "commit" (nếu có), "collected_at" }` để Knowledge Graph
biết bản ghi thuộc lần chạy nào và thay thế đúng phần cũ.

---

## 1. Code Indexer

Phân tích source Java tĩnh. Không chạy app.

### Ăn vào
- Cây source của mỗi repo service (`src/main/java`, **`src/test/java`**, `pom.xml`).
- File `.proto` trong `contracts/`.
- Cấu hình Flyway (`db/migration/*.sql`) để biết bảng + cột.
- Trích quan hệ method ↔ bảng **tĩnh** từ JPA repository / `@Query` / JdbcTemplate / MyBatis mapper.
  (Bổ sung runtime: **collector #4 `db-quality`** dựa trên `database-quality-library` — kế thừa Topic #80 — cấp
  `calledFrom` thật + số liệu query; xem mục 4.)

### Nhả ra — nodes
| type | props chính |
|---|---|
| `Service` | `language=java`, `framework=spring-boot`, `version` |
| `Endpoint` | `http_method`, `route`, `controller_fqcn`, `handler_method`, `auth_required` |
| `RpcMethod` | `grpc_service`, `grpc_method`, `request_type`, `response_type`, `impl_fqcn` |
| `Method` | `fqcn`, `name`, `signature`, `is_business` (bool — sau bộ lọc), `file`, `line_start`, `line_end`, `loc`, `rule_codes[]` (mã `R-*`/`NFR-*` nhắc trong comment/Javadoc của method) |
| `Constant` | `fqcn`, `name`, `java_type`, `value` (giá trị literal đã tính, vd `100000000`), `file`, `line`, `rule_codes[]` (mã rule trong comment ngay trên khai báo) |
| `TestCase` | `fqcn`, `method`, `display_name`, `rule_codes[]` (trích từ `@DisplayName`), `file`, `line` |
| `Table` | `db`, `schema`, `name`, `columns[]` |
| `Topic` | `name` (từ hằng số / `@KafkaListener` / `KafkaTemplate.send`) |
| `ConsumerGroup` | `group_id`, `topic` |

### Nhả ra — edges
| type | from → to | props |
|---|---|---|
| `EXPOSES` | Service → Endpoint / RpcMethod | |
| `HANDLES` | Endpoint / RpcMethod → Method | |
| `CALLS` | Method → Method | `kind=internal` |
| `CALLS_HTTP` | Method → (Endpoint hoặc `svc:*` chưa resolve) | `target_url_template`, `client=WebClient\|RestTemplate\|RestClient\|Feign` |
| `CALLS_GRPC` | Method → RpcMethod | `stub_fqcn` |
| `READS` / `WRITES` | Method → Table | `via=jpa\|jdbc\|mybatis`, `statement_sample` |
| `PRODUCES` | Method → Topic | `event_types[]` (nếu suy được từ kiểu payload) |
| `CONSUMES` | ConsumerGroup → Topic | |
| `HANDLES` | ConsumerGroup → Method | listener method |
| `USES_CONSTANT` | Method → Constant | `line` |
| `ASSIGNS_LITERAL` | Method → (RpcMethod request field) | `field`, `literal`, `line` — method gán hằng vào biến thay vì đọc field request (dấu hiệu lỗi #6) |
| `TESTS` | TestCase → Method | suy từ lớp được test (`XxxTest` → `Xxx`) + lời gọi trong thân test |
| `VERIFIES` | TestCase → BusinessRule | theo `rule_codes[]`; KG resolve sang node rule của Doc Indexer |

### Bộ lọc call graph (điểm mấu chốt)
Giữ `is_business=true` nếu method:
- Là handler của Endpoint / RpcMethod / `@KafkaListener` / `@Scheduled`, **hoặc**
- Đọc/ghi DB, gọi HTTP/gRPC ra ngoài, produce/consume Kafka, **hoặc**
- Nằm trên đường gọi giữa hai method business ở trên.

Loại bỏ: getter/setter, constructor thuần, `equals`/`hashCode`/`toString`, builder, mapper DTO↔entity,
class `@Data`/`record` không có nhánh logic, method < 3 statement không có side effect.
Mục tiêu: graph chỉ còn node có nghĩa nghiệp vụ, không phình.

### Nguồn kiểm thử trong demo
- Service không control flow (`gateway`) → call graph gần như rỗng sau lọc: kiểm chứng bộ lọc.
- `payment-business` → nhiều nhánh, nhiều rule: kiểm chứng giữ đúng method.
- Lỗi #1: `Constant{fqcn=com.ewallet.payment.domain.LimitPolicy, name=DAILY_TRANSFER_LIMIT, value=100000000}` phải
  sinh ra để đối chiếu với `R-LIMIT-01` = 50.000.000.
- Lỗi #6: `PaymentBusinessGrpcService.authorizePayment()` gán `currency = CurrencyConverter.VND` →
  edge `ASSIGNS_LITERAL{field=currency}`. Lưu ý: field `request.getCurrency()` **vẫn được đọc** ở dòng `log.info`,
  nên heuristic "field proto không ai đọc" sẽ trượt — phải xét field có đi vào lệnh nghiệp vụ (`AuthorizeCommand`) hay không.
- `TestCase`: 428 `@DisplayName` trong 7 service, 56 cái mang mã rule (đo 2026-09-17). Không có `TestCase` nào
  nhắc `TOP_UP` (lỗi #3 — cố ý).

---

## 2. Doc Indexer

Đọc tài liệu nghiệp vụ, dùng AI trích thông tin có cấu trúc.
**Hợp đồng chi tiết cho Confluence + Jira**: `confluence-jira-kit/integration/doc-indexer-contract.md`
(file đó là nguồn chính; mục này chỉ tóm tắt phần node/edge dùng chung).

### Ăn vào
- `docs/specs/*.md` (giai đoạn demo). Về sau: Confluence (label `vq-spec`) + Jira (xem kit).
- Chuẩn hoá → Markdown canonical → chia theo heading (mỗi `##`/`###` = 1 section).
- Bảng rule/NFR có cấu trúc cố định → **parse tất định trước**, AI chỉ dùng cho phần văn xuôi.

### Nhả ra — nodes
| type | props chính |
|---|---|
| `Flow` | `slug`, `name`, `actor`, `trigger`, `summary` |
| `FlowStep` | `flow_slug`, `order`, `text`, `mentions[]` (tên endpoint / event / service nhắc trong bước) |
| `BusinessRule` | `code` (vd `R-LIMIT-01`), `condition`, `action`, `severity`, `source_section`, `expected_values{}` (vd `{"DAILY_TRANSFER_LIMIT": 50000000}`) |
| `NFRConstraint` | `code`, `flow_slug`, `metric` (`latency_p95` / `latency_max` ...), `target` (span/endpoint), `operator`, `threshold`, `unit` |

### Nhả ra — edges
| type | from → to |
|---|---|
| `HAS_STEP` | Flow → FlowStep |
| `HAS_RULE` | Flow → BusinessRule |
| `HAS_NFR` | Flow → NFRConstraint |
| `MENTIONS` | FlowStep / BusinessRule → (Endpoint / Topic / Service — nếu resolve được tên) |

### AI extraction — schema output ép buộc
```json
{
  "flow": { "slug": "", "name": "", "actor": "", "trigger": "", "summary": "" },
  "steps": [{ "order": 1, "text": "", "mentions": [] }],
  "rules": [{ "code": "", "condition": "", "action": "", "severity": "must|should", "expected_values": {} }],
  "nfr":   [{ "code": "", "metric": "", "target": "", "operator": "<|<=|>|>=", "threshold": 0, "unit": "ms" }]
}
```

### Nguồn kiểm thử trong demo
- Spec F1–F6 viết sẵn với giá trị "đúng": hạn mức 50.000.000đ, `PaymentHeld` bắt buộc, thanh toán < 500ms.
- Các giá trị này lệch với code (lỗi #1, #2, #5, #6) → platform đối chiếu rule ↔ code / rule ↔ trace.
- Số lượng để tự kiểm: 6 flow, 17 + 5 rule dùng chung, 47 rule riêng flow, 14 NFR (`docs/specs/README.md`).

---

## 3. Trace Analyzer

Đọc trace runtime (và log gắn trace) từ otel-collector.

### Ăn vào — qua interface `TraceSource`

Phần phân tích (gom flow, baseline, phân loại retry) viết **một lần**, không biết nguồn là gì.
Hai adapter:

| Adapter | Dùng khi | Đọc từ | Ghi chú |
|---|---|---|---|
| `FileTraceSource` | demo, test, chạy lại dữ liệu mẫu | `infra/otel-collector/traces/traces-*.jsonl` (bản đã xoay vòng, sắp theo tên) rồi `traces.jsonl` | Lưu `(file, byte offset)` đã đọc. File xoay vòng ở 100 MB / 3 ngày |
| `TempoTraceSource` | Stage F / VDS | `GET /api/search` (TraceQL, `start`/`end`, **luôn truyền `spss`**) → `GET /api/traces/<id>` | Host demo: `http://localhost:13200`. Tempo giữ 72h — baseline phải nằm trong KG |

**Định dạng file (đã đo)**: mỗi dòng là **một lô** `{"resourceSpans":[...]}` gồm span của nhiều service,
**không phải** một trace. 1.082 trace nằm trên 59 dòng; 41 trace bị chia qua 2–3 dòng. Adapter file phải tự gom
theo `traceId` và chỉ coi trace là đủ sau `late_span_wait_seconds` (60s) kể từ span cuối — span Kafka consumer và
retry (backoff 1s × lần thử) đến muộn.

**Tempo (đã đo)**: `spss` mặc định = 3 (trace có 26 span khớp chỉ trả 3), Tempo 2.6 không có cấu hình đổi mặc định.

Log gắn trace (cho `LogFact`): `infra/otel-collector/traces/logs*.jsonl` (demo) hoặc Loki (`http://localhost:13100`).

Thuộc tính dùng:
- Resource: `service.name`, `service.version`, `deployment.environment`.
- HTTP: `http.route`, `http.request.method`, `http.response.status_code`, `url.path`.
  **Gateway đặt `http.route` = ID route** (`wallet`, `orders-internal`) chứ không phải template → không phân loại ở gateway.
- gRPC: `rpc.system`, `rpc.service`, `rpc.method`, `rpc.grpc.status_code`.
- DB: `db.system`, `db.name`, `db.statement`, `db.sql.table`, `db.operation`.
- Kafka: `messaging.system`, `messaging.destination.name`, `messaging.kafka.consumer.group`,
  `messaging.operation` (`publish`/`process`), `messaging.kafka.message.key`, `messaging.kafka.message.offset`,
  `messaging.destination.partition.id`, **`messaging.header.x_attempt`** (kiểu `string[]`, bật bằng
  `-Dotel.instrumentation.messaging.experimental.capture-headers=x-attempt` trong `docker-compose.yml`).
  Không có `messaging.message.id` — định danh bản tin = `message.key` + partition + offset gốc.
- Span `status`, `startTimeUnixNano`/`endTimeUnixNano`, `parentSpanId`, `kind`.

### Xử lý
1. **Lọc trace nền.** Trace không có span SERVER/CONSUMER (chỉ JDBC/INTERNAL: db-quality-library phân tích định kỳ,
   poller outbox) → đếm riêng, không vào flow. Chiếm ~87% số trace trong file demo.
2. **Nhóm trace → flow** theo `infra/v-quality/flow-map.yaml`:
   - *primary*: span SERVER sớm nhất ngoài gateway → `(service, method, http.route)` → flow.
   - *overlay*: F4 (có span `ReversePayment` hoặc outcome `REJECTED`), F5 (có span CONSUMER trên `ewallet.payment.events`,
     chỉ tính cây con từ span consumer).
   - *outcome*: mã HTTP của span `POST /api/orders` ở order → `COMPLETED` / `HELD` / `REJECTED` / `FAILED_OR_REFUNDED` / `PARTNER_TIMEOUT`.
   - Kiểm map: `python scripts/flow-map-check.py` (exit 1 nếu có cửa vào chưa map).
3. **Baseline** trên cửa sổ trượt 15 phút, **nhóm theo (flow, outcome)**: `rps`, `latency p50/p95/p99` theo span và
   theo flow, `error_rate`, fan-out trung bình. Loại trace của 2 phút đầu sau khi service khởi động (JIT warm-up làm
   `AuthorizePayment` lên 731–972ms ở ca bình thường). Baseline flow gốc loại trace có overlay F4.
4. **Link span → code entity.** Map:
   - HTTP server span → `Endpoint` qua `service.name` + `http.route` + method
   - gRPC span → `RpcMethod` qua `rpc.service`/`rpc.method`
   - DB span → `Table` qua `db.sql.table` (fallback: parse `db.statement`) + `service.name`
   - messaging span → `Topic` / `ConsumerGroup`
5. **Bản đồ pub/sub.** Từ messaging span dựng `Service --PRODUCES--> Topic --DELIVERS--> ConsumerGroup --> Service`.
6. **Phân loại lần xử lý** để không lệch baseline:
   - `first_attempt`: `messaging.header.x_attempt` vắng hoặc = `["1"]`
   - `retry`: `x_attempt` > 1
   - `dead_letter`: destination khớp `*.DLT`
   Retry được republish về **chính topic gốc** nên cả `notification-cg` lẫn `order-status-cg` đều nhận lần thử 2, 3.
   Baseline latency/rps chỉ tính trên `first_attempt`; retry & DLQ đếm riêng.
7. **Trích `LogFact`** từ log cùng `trace_id`: (a) mã rule đứng đầu dòng log (`R-REVIEW-01 ...`),
   (b) cặp `key=value` trong log của handler nghiệp vụ (`amount=`, `currency=`, `amountVnd=`, `status=`).
   Không cần sửa code demo. Trên VDS nên chuyển sang log có cấu trúc (MDC / attribute) để khỏi parse chuỗi.

### Nhả ra — nodes
| type | props chính |
|---|---|
| `FlowObservation` | `flow_slug`, `outcome`, `window_start`, `window_end`, `count`, `error_count`, `overlays{}` |
| `SpanStat` | `entity_id` (Endpoint/RpcMethod/Table/Topic), `flow_slug`, `outcome`, `p50`, `p95`, `p99`, `count`, `error_rate`, `per_trace_count_p95` (số span cùng loại trong một trace) |
| `Baseline` | `flow_slug`, `outcome`, `p95_ms`, `rps`, `error_rate`, `span_path[]`, `produces[]`, `status=candidate\|approved`, `approved_by`, `commit` |
| `LogFact` | `trace_id`, `span_id`, `service`, `logger`, `rule_code`, `facts{}` (vd `{"currency":"USD","amount":15000}`), `ts` |
| `TraceSample` | `trace_id`, `flow_slug`, `outcome`, `duration_ms` — chỉ giữ vài trace tiêu biểu / trace vi phạm làm bằng chứng |

### Nhả ra — edges
| type | from → to | props |
|---|---|---|
| `OBSERVED_AS` | Endpoint / RpcMethod / Table / Topic → SpanStat | |
| `ENTERS_VIA` | Flow → Endpoint | từ `flow-map.yaml` (`entries`); gốc để trả lời "method X thuộc flow nào" |
| `HAS_BASELINE` | Flow → Baseline | |
| `HAS_OBSERVATION` | Flow → FlowObservation | |
| `RUNTIME_CALLS` | (entity) → (entity) | quan sát từ quan hệ parent/child span; `count`, `p95` |
| `PRODUCES` / `DELIVERS` | Service → Topic → ConsumerGroup | `first_attempt_count`, `retry_count`, `dlq_count`, theo `outcome` |
| `EVIDENCED_BY` | FlowObservation / SpanStat → TraceSample | |
| `LOGGED_IN` | LogFact → TraceSample / RpcMethod | |

### Nguồn kiểm thử trong demo (đo trên dữ liệu thật 2026-09-17)
- Lỗi #2: 2/2 trace outcome `HELD` có **0** span PRODUCER của `payment-business`; trace `COMPLETED` luôn có.
- Lỗi #3: `FlowObservation` F1 có 10 trace, nhưng không có `TestCase` nào chạm `TopupAdapter` (collector #5).
- Lỗi #4: mỗi trace `GET /api/orders/history` có **30** span `SELECT order_steps` (= `limit`) → `per_trace_count_p95` cao.
- Lỗi #5: `AuthorizePayment` p95 nhánh `HELD` = **776ms** > 500ms; p95 gộp mọi nhánh chỉ 432ms → phải nhóm theo outcome.
- Lỗi #6: trace `4d003ec6…` có `LogFact{currency=USD, amount=15000}` và `LogFact{amountVnd=15000}` → vi phạm `R-FX-01`
  (đúng phải là 375.000.000 với tỉ giá 25.000). Span gRPC **không** mang giá trị field request, nên đường này đi qua log.
- Retry/DLQ: giao dịch `CUST-DLQ` sinh `x_attempt` 1 → 2 → 3 rồi 1 span publish `ewallet.payment.events.DLT`.

---

## 4. DB Quality Collector (`db-quality`) — kế thừa Topic #80

Không phân tích tĩnh, không đọc trace. Poll HTTP endpoint của `database-quality-library` gắn trong
4 service DB (order, business, third-party, notification). Chi tiết vận hành: `db-quality-integration.md`.

### Ăn vào
- Mỗi service DB, cách nhau ~2 phút: `POST /analyze-now` rồi `GET /report`, `GET /collected-queries`, `GET /schema-snapshot`.
- (Tùy chọn) file `infra/db-quality/reports/<service>-quality-report.json` (bản chụp lúc shutdown).

### Nhả ra — nodes
| type | props chính |
|---|---|
| `Table` | `db`, `schema`, `name`, `columns[] {name,type,nullable}`, `indexes[]`, `foreign_keys[]` (từ `schema-snapshot`) |
| `SqlPattern` | `service`, `normalized_sql`, `operation` (SELECT/INSERT/UPDATE/DELETE), `call_count`, `avg_ms`, `min_ms`, `max_ms`, `p95_ms`, `called_from` |
| `DbFinding` | `service`, `rule` (`N_PLUS_ONE`/`SLOW_QUERY`/`MISSING_INDEX`/`SELECT_STAR`/`MISSING_PRIMARY_KEY`/...), `severity`, `message`, `recommendation`, `called_from`, `score_impact`, **`confirmation`** (`confirmed_by_trace` / `unconfirmed` / `not_applicable`) |
| `DbQualityScore` | `service`, `score` (0–100), `scraped_at`, `total_sql`, `slow_query_count`, `n_plus_one_count`, `error_rate` |

### Nhả ra — edges
| type | from → to | props |
|---|---|---|
| `RUNTIME_READS` / `RUNTIME_WRITES` | `SqlPattern` → `Table` | parse tên bảng trong `normalized_sql` |
| `OBSERVED_AT` | `SqlPattern` → `Method` | map `called_from` (`Class:line -> method`) sang node `Method` của Code Indexer |
| `FLAGGED_BY` | `SqlPattern` / `Table` → `DbFinding` | |
| `CONFIRMED_BY` | `DbFinding` → `SpanStat` / `TraceSample` | chỉ có khi xác nhận được bằng trace (xem dưới) |
| `HAS_DB_SCORE` | `Service` → `DbQualityScore` | |

### Lọc false positive N+1 (bắt buộc)
Heuristic `N_PLUS_ONE` của thư viện đếm số lần lặp **trong cả cửa sổ thu**, không theo request. Kết quả: 7 finding thì
chỉ 1 là thật (#4); lần chạy 2026-09-17 còn gắn N+1 cho `PaymentSagaOrchestrator:291 -> updateStatus()` (219–265 lần
trong cửa sổ, nhưng mỗi request chỉ vài lần). Quy tắc xác nhận:

- `confirmed_by_trace` khi tồn tại trace chứa **≥ 5 span JDBC cùng `db.statement` chuẩn hoá** của cùng service,
  và span cha nằm trong method khớp `called_from`.
- Ngược lại `unconfirmed` — vẫn lưu, nhưng checks engine và Agent chỉ dùng như gợi ý, không đưa vào verdict.

### Quan hệ với Code Indexer
- Code Indexer cho quan hệ method ↔ bảng **tĩnh** (từ mã nguồn).
- `db-quality` cho quan hệ **runtime thực tế** + `called_from` chính xác + chi phí query.
- Knowledge Graph hợp nhất: nếu tĩnh nói "method M đọc bảng T" mà runtime không thấy → dead code / nhánh chưa chạy;
  nếu runtime thấy pattern mà tĩnh không map được → truy vấn động / raw SQL bị bỏ sót.
- `called_from` chỉ đúng khi tắt `otel.instrumentation.spring-data` (đã tắt trong compose, `db-quality-integration.md` §5b).

### Nguồn kiểm thử trong demo
- Lỗi #4: `DbFinding{rule=N_PLUS_ONE, called_from="com.ewallet.order.history.OrderHistoryService:67 -> history()",
  confirmation=confirmed_by_trace}` + `SqlPattern.call_count` cao; nếu cột `order_id` chưa index → thêm `DbFinding{rule=MISSING_INDEX}`.
- `PaymentSagaOrchestrator:291` → phải ra `unconfirmed`.
- Thay đổi schema: so `Table.columns` giữa 2 lần scrape → phát hiện cột thêm/xóa, phục vụ impact analysis.
- `DbQualityScore` tụt giữa các lần scrape → tín hiệu regression hiệu năng DB (đúng mục tiêu Topic #80).

---

## 5. Coverage Collector (`coverage`) — mới 2026-09-17

Không có collector này thì đối chiếu "trace ↔ coverage" (lỗi #3) không có dữ liệu. Chạy sau `mvn verify`
(`scripts/run-tests.ps1` / `.sh`) — trong CI là một bước sau test.

### Ăn vào
- `*/target/site/jacoco/jacoco.xml` — đọc **mức method** (`<method name desc line>` + counter `LINE`/`BRANCH`).
  Không dùng `scripts/coverage-report.py --json` làm nguồn vì file đó chỉ có mức lớp.
- `*/target/surefire-reports/TEST-*.xml` — kết quả pass/fail/skip, thời gian. Lưu ý: `testcase name` là **tên method Java**,
  không chứa `@DisplayName`, nên mã rule của test lấy từ node `TestCase` của Code Indexer (nối theo `fqcn#method`).

### Nhả ra — nodes
| type | props chính |
|---|---|
| `CoverageStat` | `service`, `method_id`, `line_covered`, `line_missed`, `branch_covered`, `branch_missed`, `run_id`, `commit` |
| `TestRun` | `service`, `run_id`, `commit`, `tests`, `failures`, `errors`, `skipped`, `duration_s` |

### Nhả ra — edges
| type | from → to | props |
|---|---|---|
| `HAS_COVERAGE` | Method → CoverageStat | |
| `RAN` | TestRun → TestCase | `result=pass\|fail\|skip`, `duration_ms` |

### Bộ lọc nhiễu
Bỏ qua khi xét test gap (không phải lỗi): lớp `*Application`, `*Config`, `*Dtos`, entity JPA không logic
(`DailyUsage`, `DailyUsageId`), lớp bọc stub gRPC (`PaymentBusinessClient`), gói sinh từ proto
`com/ewallet/payment/contract/grpc/**`.

### Nguồn kiểm thử trong demo
- Lỗi #3: `TopupAdapter` 0% / 11 dòng trong khi `BillAdapter`, `TelcoAdapter` 100% (`docs/testing.md`).
- Tổng: 457 test / 31 file / 7 service, độ phủ dòng 86% (2026-09-14).

---

## Đầu ra hợp nhất (định hướng cho Knowledge Graph)

```
Code Indexer     ─┐
Doc Indexer      ─┤
Trace Analyzer   ─┼─►  Knowledge Graph  ─►  Checks engine  ─►  MCP Server  ─►  AI Agent  ─►  Báo cáo / PR comment
DB Quality (#80) ─┤     (Code ↔ Flow ↔ Spec ↔ Runtime ↔ Test)
Coverage         ─┘
```

Các loại đối chiếu platform thực hiện (không thuộc phạm vi Người 2, nhưng collector phải cấp đủ dữ liệu):

| Đối chiếu | Dữ liệu | Lỗi |
|---|---|---|
| **rule ↔ code** | `BusinessRule.expected_values` vs `Constant.value`; rule yêu cầu dùng field vs `ASSIGNS_LITERAL` | #1, #6 (tĩnh) |
| **rule ↔ trace** | `BusinessRule.action` "publish PaymentHeld" vs `PRODUCES` theo outcome `HELD` | #2 |
| **rule ↔ log** | `R-FX-01`: `LogFact.currency != VND` mà `amountVnd == amount` | #6 (runtime) |
| **trace ↔ coverage** | `FlowObservation` có trace vs Method trên đường `HANDLES`/`CALLS` không có `CoverageStat` > 0 hay `TestCase` | #3 |
| **trace ↔ DB** | `SpanStat.per_trace_count_p95` + `DbFinding` đã `confirmed_by_trace` | #4 |
| **trace ↔ baseline / NFR** | `SpanStat.p95` theo (flow, outcome) vs `Baseline` cũ và vs `NFRConstraint` | #5, regression |

Chi tiết từng check, MCP tool và cách Agent ra verdict: `platform-plan.md` ở workspace v-quality.
