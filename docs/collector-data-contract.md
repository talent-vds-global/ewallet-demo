# Hợp đồng dữ liệu: Collector → Knowledge Graph

> Ba collector do Người 2 phụ trách. File này định nghĩa **collector ăn vào gì** và **nhả ra gì**.
> Knowledge Graph (PostgreSQL, do Người khác phụ trách) hợp nhất 3 nguồn thành: Code ↔ Flow ↔ Spec ↔ Runtime.
> Định dạng trao đổi đề xuất: JSON theo `{ "nodes": [...], "edges": [...], "source": {...} }`.

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
| Table | `tbl:<db>:<schema>.<table>` |
| Topic | `topic:<cluster>:<topic>` |
| ConsumerGroup | `cg:<topic>:<group.id>` |
| Flow (spec) | `flow:<slug>` |
| BusinessRule | `rule:<flow slug>:<rule code>` |
| NFRConstraint | `nfr:<flow slug>:<metric>` |
| FlowObservation (runtime) | `obs:<flow slug>:<window start ISO>` |

Edge: `{ "from", "to", "type", "props": {...} }`.

---

## 1. Code Indexer

Phân tích source Java tĩnh. Không chạy app.

### Ăn vào
- Cây source của mỗi repo service (`src/main/java`, `pom.xml`).
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
| `Method` | `fqcn`, `name`, `signature`, `is_business` (bool — sau bộ lọc), `loc` |
| `Table` | `db`, `schema`, `name`, `columns[]` |
| `Topic` | `name` (từ hằng số / `@KafkaListener` / `KafkaTemplate.send`) |
| `ConsumerGroup` | `group_id`, `topic` |

### Nhả ra — edges
| type | from → to | props |
|---|---|---|
| `EXPOSES` | Service → Endpoint / RpcMethod | |
| `HANDLES` | Endpoint / RpcMethod → Method | |
| `CALLS` | Method → Method | `kind=internal` |
| `CALLS_HTTP` | Method → (Endpoint hoặc `svc:*` chưa resolve) | `target_url_template`, `client=WebClient\|RestTemplate\|Feign` |
| `CALLS_GRPC` | Method → RpcMethod | `stub_fqcn` |
| `READS` / `WRITES` | Method → Table | `via=jpa\|jdbc\|mybatis`, `statement_sample` |
| `PRODUCES` | Method → Topic | `event_types[]` (nếu suy được từ kiểu payload) |
| `CONSUMES` | ConsumerGroup → Topic | |
| `HANDLES` | ConsumerGroup → Method | listener method |

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
- Lỗi #1: hằng số `DAILY_TRANSFER_LIMIT` phải xuất hiện trong props `Method` hoặc node `Constant` để đối chiếu spec.

---

## 2. Doc Indexer

Đọc tài liệu nghiệp vụ, dùng AI trích thông tin có cấu trúc.

### Ăn vào
- `docs/specs/*.md` (giai đoạn demo). Về sau: Confluence export (HTML/Storage format).
- Chuẩn hoá → Markdown canonical → chia theo heading (mỗi `##`/`###` = 1 section).

### Nhả ra — nodes
| type | props chính |
|---|---|
| `Flow` | `slug`, `name`, `actor`, `trigger`, `summary` |
| `FlowStep` | `flow_slug`, `order`, `text`, `mentions[]` (tên endpoint / event / service nhắc trong bước) |
| `BusinessRule` | `code` (vd `R-LIMIT-01`), `condition`, `action`, `severity`, `source_section` |
| `NFRConstraint` | `flow_slug`, `metric` (`latency_p95` / `latency_max` ...), `operator`, `threshold`, `unit` |

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
  "rules": [{ "code": "", "condition": "", "action": "", "severity": "must|should" }],
  "nfr":   [{ "metric": "", "operator": "<|<=|>|>=", "threshold": 0, "unit": "ms" }]
}
```

### Nguồn kiểm thử trong demo
- Spec F1–F5 viết sẵn với giá trị "đúng": hạn mức 50.000.000đ, `PaymentHeld` bắt buộc, thanh toán < 500ms.
- Các giá trị này lệch với code (lỗi #1, #2, #5) → platform đối chiếu rule ↔ code / rule ↔ trace.

---

## 3. Trace Analyzer

Đọc trace runtime từ otel-collector.

### Ăn vào
- `infra/otel-collector` xuất `traces.jsonl` (OTLP JSON, mỗi dòng 1 ResourceSpans).
- Thuộc tính dùng: `service.name`, `service.version` (resource); `http.route`, `http.request.method`,
  `rpc.system`, `rpc.service`, `rpc.method`, `db.system`, `db.statement`,
  `messaging.system`, `messaging.destination.name`, `messaging.kafka.consumer.group`,
  `messaging.operation` (`publish`/`receive`/`process`), span `status`, `duration`.
- Header/attr retry: `x-attempt`, tên topic `*.DLT`.

### Xử lý
1. **Nhóm span → flow.** Root span (`http.route` ở gateway) → gán `flow_slug` theo bảng route→flow (config `flow-map.yaml`).
2. **Baseline** trên cửa sổ trượt (vd 15 phút): `rps`, `latency p50/p95/p99` theo span và theo flow, `error_rate`, fan-out trung bình.
3. **Link span → code entity.** Map:
   - HTTP server span → `Endpoint` qua `service.name` + `http.route` + method
   - gRPC span → `RpcMethod` qua `rpc.service`/`rpc.method`
   - DB span → `Table` qua parse `db.statement` (tên bảng) + `service.name`
   - messaging span → `Topic` / `ConsumerGroup`
4. **Bản đồ pub/sub.** Từ messaging span dựng `Service --PRODUCES--> Topic --DELIVERS--> ConsumerGroup --> Service`.
5. **Phân loại lần xử lý** để không lệch baseline:
   - `first_attempt`: `x-attempt` vắng hoặc = 1
   - `retry`: `x-attempt` > 1, cùng `messaging.message.id`
   - `dead_letter`: destination khớp `*.DLT`
   Baseline latency/rps chỉ tính trên `first_attempt`; retry & DLQ đếm riêng.

### Nhả ra — nodes
| type | props chính |
|---|---|
| `FlowObservation` | `flow_slug`, `window_start`, `window_end`, `count`, `error_count` |
| `SpanStat` | `entity_id` (Endpoint/RpcMethod/Table/Topic), `p50`, `p95`, `p99`, `count`, `error_rate` |
| `Baseline` | `flow_slug`, `p95_ms`, `rps`, `error_rate`, `span_path[]`, `status=candidate\|approved` |

### Nhả ra — edges
| type | from → to | props |
|---|---|---|
| `OBSERVED_AS` | Endpoint / RpcMethod / Table / Topic → SpanStat | |
| `HAS_BASELINE` | Flow → Baseline | |
| `RUNTIME_CALLS` | (entity) → (entity) | quan sát từ quan hệ parent/child span; `count`, `p95` |
| `PRODUCES` / `DELIVERS` | Service → Topic → ConsumerGroup | `first_attempt_count`, `retry_count`, `dlq_count` |

### Nguồn kiểm thử trong demo
- Lỗi #2: flow HELD → **không có** edge `PRODUCES` tới `ewallet.payment.events` cho nhánh đó.
- Lỗi #3: `FlowObservation` cho F1 top-up tồn tại nhưng không có test coverage tương ứng.
- Lỗi #4: `SpanStat` cho endpoint history có `count` DB span cao bất thường / N query giống nhau.
- Lỗi #5: `SpanStat.p95` của span thanh toán nhánh HELD > 500ms.
- Retry/DLQ: chủ động bơm lỗi tạm ở `notification` để sinh `retry` + `dead_letter`, kiểm chứng phân loại.

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
| `DbFinding` | `service`, `rule` (`N_PLUS_ONE`/`SLOW_QUERY`/`MISSING_INDEX`/`SELECT_STAR`/`MISSING_PRIMARY_KEY`/...), `severity`, `message`, `recommendation`, `called_from`, `score_impact` |
| `DbQualityScore` | `service`, `score` (0–100), `scraped_at`, `total_sql`, `slow_query_count`, `n_plus_one_count`, `error_rate` |

### Nhả ra — edges
| type | from → to | props |
|---|---|---|
| `RUNTIME_READS` / `RUNTIME_WRITES` | `SqlPattern` → `Table` | parse tên bảng trong `normalized_sql` |
| `OBSERVED_AT` | `SqlPattern` → `Method` | map `called_from` (`Class:line -> method`) sang node `Method` của Code Indexer |
| `FLAGGED_BY` | `SqlPattern` / `Table` → `DbFinding` | |
| `HAS_DB_SCORE` | `Service` → `DbQualityScore` | |

### Quan hệ với Code Indexer
- Code Indexer cho quan hệ method ↔ bảng **tĩnh** (từ mã nguồn).
- `db-quality` cho quan hệ **runtime thực tế** + `called_from` chính xác + chi phí query.
- Knowledge Graph hợp nhất: nếu tĩnh nói "method M đọc bảng T" mà runtime không thấy → dead code / nhánh chưa chạy;
  nếu runtime thấy pattern mà tĩnh không map được → truy vấn động / raw SQL bị bỏ sót.

### Nguồn kiểm thử trong demo
- Lỗi #4: `DbFinding{rule=N_PLUS_ONE, called_from="OrderHistoryService:<line> -> ..."}` + `SqlPattern.call_count` cao;
  nếu cột `order_id` chưa index → thêm `DbFinding{rule=MISSING_INDEX}`.
- Thay đổi schema: so `Table.columns` giữa 2 lần scrape → phát hiện cột thêm/xóa, phục vụ impact analysis.
- `DbQualityScore` tụt giữa các lần scrape → tín hiệu regression hiệu năng DB (đúng mục tiêu Topic #80).

---

## Đầu ra hợp nhất (định hướng cho Knowledge Graph)

```
Code Indexer     ─┐
Doc Indexer      ─┼─►  Knowledge Graph  ─►  MCP Server  ─►  AI Agent
Trace Analyzer   ─┤     (Code ↔ Flow ↔ Spec ↔ Runtime)
DB Quality (#80) ─┘
```

Ba loại đối chiếu platform thực hiện (không thuộc phạm vi Người 2, nhưng collector phải cấp đủ dữ liệu):
- **rule ↔ code**: `BusinessRule.condition` vs hằng số / nhánh `if` trong `Method` → spec drift (#1, #6).
- **rule ↔ trace**: `BusinessRule.action` "publish PaymentHeld" vs edge `PRODUCES` quan sát được → nhánh quên event (#2).
- **trace ↔ coverage**: `FlowObservation` vs report JaCoCo → test gap (#3).
- **trace ↔ baseline / NFR**: `SpanStat.p95` vs `Baseline` cũ và vs `NFRConstraint` → regression + vi phạm NFR (#4, #5).
