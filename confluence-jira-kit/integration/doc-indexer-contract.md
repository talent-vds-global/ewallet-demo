# Hợp đồng Doc Indexer — Confluence & Jira → Knowledge Graph

> Tài liệu này nói **chính xác** những gì collector đọc từ Atlassian và những node/edge nó sinh ra.
> Người viết tài liệu đọc `01-CONVENTIONS.md`; người viết collector đọc file này.

---

## 1. Nguồn dữ liệu

| Nguồn | API | Truy vấn |
|---|---|---|
| Confluence | REST API v2 `/wiki/api/v2/pages` + v1 `/wiki/rest/api/content/search` | CQL `space = EWL AND label = "vq-spec"` |
| Jira | REST API v3 `/rest/api/3/search` | JQL `project = EWL` |

Xác thực: HTTP Basic với `email` + `api_token` của tài khoản `vquality-bot`.

### 1.1 Thu thập tăng dần

Confluence trả `version.number` cho mỗi trang. Collector lưu `(page_id, version)` đã xử lý và chỉ
parse lại trang có version tăng. Điều này quan trọng không chỉ vì hiệu năng: **version tăng là tín hiệu
spec đã đổi**, và nó phải kích hoạt việc đánh dấu baseline runtime tương ứng là "cần kiểm chứng lại".

---

## 2. Confluence → node

### 2.1 `Flow`

Sinh từ mỗi trang có label `vq-flow`. Nguồn dữ liệu là macro **Page Properties**.

| Prop | Lấy từ | Bắt buộc |
|---|---|---|
| `slug` | Page Properties `Flow Slug`, đối chiếu với label `slug-*` | có |
| `flow_id` | `Flow ID` | có |
| `name` | `Flow Name` | có |
| `actor` | `Actor` | có |
| `trigger` | `Trigger` | có |
| `kind` | `Loại` | có |
| `services[]` | `Services`, tách theo dấu phẩy | có |
| `protocols[]` | `Protocols` | có |
| `entry_endpoint` | `Entry Endpoint` | có |
| `kafka_topics[]` | `Kafka Topics` | không |
| `jira_epic` | `Jira Epic` | có |
| `spec_source` | `Spec Source` | có |
| `doc_version` | `Doc Version` | có |
| `status` | `Status` | có |
| `page_id`, `page_version`, `page_url` | metadata Confluence | có |

Nếu hai label `slug-*` khác nhau cùng xuất hiện trên một trang, hoặc `Flow Slug` khác với label,
collector **báo lỗi và bỏ trang** thay vì đoán. Một flow có hai danh tính còn tệ hơn là không có flow.

### 2.2 `FlowStep`

Sinh từ bảng trong mục `2.3 Mô tả chi tiết nghiệp vụ`, nhận diện bằng tiêu đề cột
`Bước` · `Mô tả` · `Thực hiện bởi` (cột `Rule áp dụng` tuỳ chọn).

| Prop | Nguồn |
|---|---|
| `flow_slug` | trang cha |
| `order` | cột `Bước`, số hoặc mã saga `S1`–`S4` |
| `text` | cột `Mô tả` |
| `performed_by` | cột `Thực hiện bởi`, resolve sang node `Service` |
| `rule_codes[]` | cột `Rule áp dụng`, tách theo dấu phẩy |
| `mentions[]` | trích từ `text`: endpoint (`METHOD /path`), tên bảng DB, tên topic, tên class |

### 2.3 `BusinessRule`

Sinh từ mọi bảng có đúng 4 cột `Mã` · `Điều kiện` · `Hành động` · `Severity`, trên bất kỳ trang nào
có label `vq-spec`.

| Prop | Nguồn | Ghi chú |
|---|---|---|
| `code` | cột `Mã` | khoá chính, đã bỏ backtick |
| `condition` | cột `Điều kiện` | |
| `action` | cột `Hành động` | |
| `severity` | cột `Severity` | chỉ nhận `must` / `should`, khác thì báo lỗi |
| `source_page`, `source_section` | trang và heading gần nhất | |
| `numeric_values[]` | mọi số tiền/ngưỡng trích từ `condition` | dùng để so với hằng số trong code |

`numeric_values` là phần làm nên giá trị của cả hệ: từ `"vượt 50.000.000đ"` collector rút ra
`50000000`, và đó chính là con số đem so với `LimitPolicy.DAILY_TRANSFER_LIMIT`.

Rule xuất hiện ở nhiều trang (rule chung, được nhắc lại trong mục 3.2 của trang flow) được **gộp theo
`code`**; trang có bảng 4 cột đầy đủ là nguồn chính, chỗ nhắc lại chỉ sinh edge `HAS_RULE`.

### 2.4 `NFRConstraint`

Sinh từ mọi bảng có đúng 5 cột `Mã` · `metric` · `operator` · `threshold` · `unit`.

| Prop | Nguồn | Kiểm |
|---|---|---|
| `code` | `Mã` | |
| `metric` | `metric` | thuộc tập: `latency_p95`, `latency_max`, `error_rate`, `query_count`, `consumer_lag`, `timeout` |
| `operator` | `operator` | thuộc `<`, `<=`, `>`, `>=` |
| `threshold` | `threshold` | **phải parse được thành số**; không parse được thì báo lỗi, không im lặng bỏ qua |
| `unit` | `unit` | `ms`, `percent`, `queries`, `messages` |
| `measured_at` | bảng "Cách đo" ở trang `[CATALOG] NFR` | span nào, filter nào |

### 2.5 `Endpoint`, `RpcMethod`, `Topic`

Sinh từ trang `[API]`:

- `Endpoint`: từ bảng mục 2 và 3 — `method`, `path`, `service`, `flows[]`.
- `RpcMethod`: từ bảng mục 4 — `service`, `method`, `flows[]`.
- `Topic`: từ bảng mục 5 — `name`, `producer`, `consumer_groups[]`.

### 2.6 `Service`

Sinh từ hợp của: bảng service ở trang `[COMMON]`, participant trong mermaid, và cột `Thực hiện bởi`.
Khoá là `otel_service_name`.

---

## 3. Confluence → edge

| Edge | Từ → Đến | Nguồn |
|---|---|---|
| `HAS_STEP` | Flow → FlowStep | bảng mục 2.3 |
| `HAS_RULE` | Flow → BusinessRule | bảng mục 3, gồm cả mục 3.2 liệt kê rule chung |
| `HAS_NFR` | Flow → NFRConstraint | bảng mục 4 |
| `MENTIONS` | FlowStep → Endpoint / Topic / Service | `mentions[]` |
| `TOUCHES` | Flow → Service | Page Properties `Services` + participant mermaid |
| `ENTERS_AT` | Flow → Endpoint | Page Properties `Entry Endpoint` |
| `BELONGS_TO` | Flow → trang cha | cây Confluence |
| `IMPACTS` | Flow → Flow | bảng mục 8 "Chức năng ảnh hưởng" |
| `READS_TABLE` / `WRITES_TABLE` | Flow → Table | bảng mục 6, theo cột `Thao tác` |

---

## 4. Mermaid → dữ liệu

Khối mermaid `sequenceDiagram` được parse thành:

| Trích được | Cách |
|---|---|
| Danh sách service | dòng `participant <alias> as <tên>` — lấy phần sau `as` |
| Lời gọi giữa service | dòng `A->>B: nhãn` — sinh `CALLS_EXPECTED(A → B)` |
| Giao thức | từ khoá trong nhãn: `gRPC`, `POST`, `GET`, `publish`, `consume`, `SSE` |
| Nhóm bước saga | khối `rect` + `note over` — nối với `FlowStep.order` |
| Nhánh điều kiện | khối `alt` / `else` — sinh `ExpectedBranch` |

`CALLS_EXPECTED` là **kỳ vọng từ tài liệu**; Trace Analyzer sinh `CALLS_OBSERVED` từ span thật.
Chênh lệch giữa hai tập này là một phát hiện độc lập với mọi rule: tài liệu nói A gọi B mà runtime
không có, hoặc runtime có lời gọi mà tài liệu không nhắc.

Khối `flowchart` sinh `ExpectedBranch` cho nhánh ngoại lệ; khối `stateDiagram-v2` sinh
`StateTransition` để đối chiếu với trạng thái thật trong DB.

---

## 5. Jira → node

| Node | Sinh từ | Prop |
|---|---|---|
| `WorkItem` | mọi issue | `key`, `type`, `status`, `summary`, `flow_slug`, `rule_codes[]`, `nfr_codes[]`, `services[]`, `components[]`, `labels[]`, `verdict` |
| `Defect` | issue type `Bug` | thêm `defect_id` |

| Edge | Từ → Đến |
|---|---|
| `IMPLEMENTS` | WorkItem → BusinessRule (qua `Rule Codes`) |
| `CONSTRAINS` | WorkItem → NFRConstraint (qua `NFR Codes`) |
| `BELONGS_TO_FLOW` | WorkItem → Flow (qua `Flow Slug`) |
| `AFFECTS_SERVICE` | WorkItem → Service (qua `Service` / Component) |
| `VIOLATES` | Defect → BusinessRule / NFRConstraint |
| `DOCUMENTED_BY` | WorkItem → Flow page (qua `Confluence Page`) |

---

## 6. Schema output của bước trích bằng AI

Khi một đoạn không parse được bằng luật (ví dụ mô tả tự do không nằm trong bảng), collector gọi model
với schema ép buộc:

```json
{
  "flow": { "slug": "", "name": "", "actor": "", "trigger": "", "summary": "" },
  "steps": [{ "order": 1, "text": "", "mentions": [] }],
  "rules": [{ "code": "", "condition": "", "action": "", "severity": "must|should" }],
  "nfr":   [{ "metric": "", "operator": "<|<=|>|>=", "threshold": 0, "unit": "ms" }]
}
```

Nguyên tắc: **luật trước, AI sau**. Bảng đúng khuôn thì parse bằng luật — kết quả tất định, lặp lại được,
không tốn token. AI chỉ dùng cho phần văn xuôi. Nếu thấy AI phải làm việc nhiều, đó là dấu hiệu tài liệu
đang lệch khuôn, nên sửa tài liệu chứ đừng nâng cấp prompt.

---

## 7. Kiểm tra tính đầy đủ

Sau mỗi lần thu thập, collector tự kiểm và ghi log cảnh báo:

| Kiểm | Giá trị mong đợi (hiện tại) |
|---|---|
| Số node `Flow` | 6 |
| Số node `BusinessRule` | 62 |
| Số node `NFRConstraint` | 14 |
| Số trang có label `vq-spec` | 14 |
| Flow không có `FlowStep` | 0 |
| Flow không có sequence diagram | 0 |
| `BusinessRule` có `severity = must` mà không có `WorkItem` nào `IMPLEMENTS` | 0 |
| `NFRConstraint` có `threshold` null | 0 |
| Participant mermaid không resolve được sang `Service` | 0 |

Các con số 6 / 62 / 14 không hard-code trong collector — chúng đọc từ mục "Thống kê" của trang
`[CATALOG] Business Rule` và `[CATALOG] NFR`. Tài liệu tự khai báo nó có bao nhiêu rule, và collector
kiểm xem có đếm ra đúng ngần ấy không. Lệch nghĩa là có bảng viết sai khuôn.

---

## 8. Xử lý lỗi

| Tình huống | Hành vi |
|---|---|
| Trang thiếu Page Properties | Bỏ trang, ghi log `ERROR`, **không** đoán từ tiêu đề |
| Bảng sai tiêu đề cột | Bỏ bảng, ghi log `WARN` kèm tiêu đề thực tế đọc được |
| `threshold` không parse được | Bỏ dòng NFR, ghi log `ERROR` |
| `severity` không thuộc `must`/`should` | Bỏ dòng rule, ghi log `ERROR` |
| Mermaid lỗi cú pháp | Giữ nguyên text thô, ghi log `WARN`, vẫn sinh `Flow` |
| Confluence trả 429 | Backoff theo header `Retry-After`, tối đa 5 lần |
| Jira `Rule Codes` trỏ tới mã rule không tồn tại | Vẫn tạo edge, đánh dấu `dangling = true`, báo cáo ở bản tin chất lượng tài liệu |

Nguyên tắc chung: **thà thiếu node còn hơn có node sai**. Một `BusinessRule` với `condition` méo mó sẽ
sinh ra kết luận sai về code, và kết luận sai làm mất niềm tin nhanh hơn là không có kết luận.
