# 01 — Hợp đồng cấu trúc Confluence & Jira

> Đây là **hợp đồng giữa người viết tài liệu và Doc Indexer**. Doc Indexer không "đọc hiểu tự do";
> nó bám vào heading, label và hình dạng bảng mô tả ở file này. Viết lệch ⇒ node không sinh ra ⇒
> flow đó biến mất khỏi Knowledge Graph và AI Agent sẽ không có gì để đối chiếu với trace.

---

## 1. Confluence

### 1.1 Space

| Hạng mục | Giá trị |
|---|---|
| Space name | `E-Wallet Business Specs` |
| Space key | **`EWL`** |
| Space type | Documentation space (không phải personal space) |
| Quyền | `vquality-bot` có quyền **View** toàn space (read-only) |

> Space key đi vào cấu hình collector: `CONFLUENCE_SPACE_KEY=EWL`. Đổi key ⇒ phải đổi cấu hình.

### 1.2 Đặt tên trang

| Loại trang | Khuôn tên | Ví dụ |
|---|---|---|
| Trang gốc | `E-Wallet Business Specs` | |
| Nền chung | `[COMMON] <tên>` | `[COMMON] Mien nghiep vu va quy uoc` |
| Hợp đồng API | `[API] <tên>` | `[API] Hop dong REST gRPC Kafka` |
| **Flow spec** | `[<FlowID>] <Tên nghiệp vụ>` | `[F1] Nap tien vi qua doi tac` |
| Catalog | `[CATALOG] <tên>` | `[CATALOG] Business Rule` |

`FlowID` ∈ `F1 … F6`. **Bắt buộc nằm trong ngoặc vuông ở đầu tiêu đề** — đây là cách rẻ nhất để
Doc Indexer nhận ra trang nào là flow mà không cần đọc nội dung.

### 1.3 Label (bắt buộc)

| Label | Gắn cho | Ý nghĩa với Doc Indexer |
|---|---|---|
| `vq-spec` | **mọi** trang trong kit này | Bộ lọc gốc: chỉ trang có label này mới được thu thập |
| `vq-flow` | 6 trang flow | Trang này sinh ra node `Flow` |
| `flow-f1` … `flow-f6` | trang flow tương ứng | Khoá nối sang Jira epic |
| `slug-topup-partner`, `slug-bill-telco-payment`, `slug-p2p-transfer`, `slug-failure-refund`, `slug-async-notification`, `slug-transaction-history` | trang flow tương ứng | `Flow.slug` — **phải khớp tuyệt đối** với `slug` trong `docs/specs/F*.md` |
| `vq-domain` | trang `[COMMON]` | Rule dùng chung, seed, trạng thái |
| `vq-api` | trang `[API]` | Sinh node `Endpoint` / `Topic` |
| `vq-rule-catalog` | `[CATALOG] Business Rule` | Bảng tổng hợp để cross-check |
| `vq-nfr` | `[CATALOG] NFR` | Sinh node `NFRConstraint` |
| `vq-traceability` | `[CATALOG] Traceability` | Ma trận rule ↔ code ↔ lỗi |

Truy vấn collector dùng: `CQL: space = EWL AND label = "vq-spec"`.

### 1.4 Page Properties — khối metadata bắt buộc của trang flow

Mỗi trang flow **mở đầu** bằng macro **Page Properties** (trong editor gõ `/Page Properties`) chứa đúng
các khoá sau. Đây là phần Doc Indexer đọc **trước tiên**; thiếu khoá nào thì node `Flow` thiếu prop đó.

| Khoá | Ví dụ giá trị | Bắt buộc |
|---|---|---|
| `Flow ID` | `F1` | có |
| `Flow Slug` | `topup-partner` | có |
| `Flow Name` | `Nạp tiền ví qua đối tác` | có |
| `Actor` | `Khách hàng` | có |
| `Trigger` | `Khách bấm Nạp tiền trên app` | có |
| `Loại` | `ghi` / `đọc` / `bất đồng bộ` | có |
| `Services` | `ewallet-gateway, ewallet-business-customer-mobileapp, ewallet-payment-order, ewallet-payment-business, ewallet-third-party, partner-sim, ewallet-notification` | có |
| `Protocols` | `HTTP, gRPC, Kafka, WebSocket, SSE, JDBC` | có |
| `Entry Endpoint` | `POST /api/wallet/topup` | có |
| `Kafka Topics` | `ewallet.payment.events` | nếu có |
| `Jira Epic` | `EWL-1` | có |
| `Spec Source` | `docs/specs/F1-topup-partner.md` | có |
| `Doc Version` | `1.0` | có |
| `Status` | `APPROVED` / `DRAFT` / `DEPRECATED` | có |

`Entry Endpoint` là khoá nối quan trọng nhất: Trace Analyzer dùng `http.route` ở gateway để gán
một trace vào đúng flow. F1 và F2 đi qua gần như cùng chuỗi span — **chỉ phân biệt được bằng route này**.

### 1.5 Heading bắt buộc của trang flow

Doc Indexer chia trang theo heading cấp 1 và cấp 2. Trang flow **phải** có đúng các heading sau,
đúng thứ tự, đúng chữ:

```
1. Mô tả chung
   1.1 Mục đích
   1.2 Phạm vi
   1.3 Tiền điều kiện
   1.4 Hậu điều kiện
2. Luồng nghiệp vụ
   2.1 Biểu đồ luồng
   2.2 Sequence diagram
   2.3 Mô tả chi tiết nghiệp vụ
   2.4 Luồng phụ và ngoại lệ
3. Business rule
4. NFR
5. Acceptance criteria
6. Bảng/Thực thể liên quan
7. Danh sách mã lỗi
8. Chức năng ảnh hưởng
9. Bảng ghi nhận thay đổi tài liệu
```

Mục 1 và 2 giữ nguyên khuôn tài liệu nghiệp vụ đang dùng của công ty (Mô tả chung → Màn hình → Luồng nghiệp vụ).
Mục **"Màn hình"** của mẫu cũ là **tuỳ chọn** với hệ demo (không có UI thật); nếu có, chèn thành `1.5 Màn hình`
để không phá thứ tự heading máy đọc.

### 1.6 Hình dạng bảng bắt buộc

Doc Indexer nhận bảng theo **tiêu đề cột**. Sai tiêu đề thì bảng bị bỏ qua.

**Bảng bước nghiệp vụ** (mục 2.3):

| Cột | Bắt buộc |
|---|---|
| `Bước` | có — số thứ tự, hoặc mã bước saga `S1`…`S4` |
| `Mô tả` | có |
| `Thực hiện bởi` | có — tên service, viết đúng `OTEL_SERVICE_NAME` |
| `Rule áp dụng` | tuỳ chọn — danh sách mã `R-*` cách nhau bằng dấu phẩy |

**Bảng business rule** (mục 3) — đúng 4 cột, đúng tên:

| Cột | Ghi chú |
|---|---|
| `Mã` | `R-<NHÓM>-<số>`, in trong backtick |
| `Điều kiện` | mệnh đề kiểm tra được; **có số thì viết số** (`≥ 20.000.000đ`), không viết "số tiền lớn" |
| `Hành động` | động từ + kết quả (`Từ chối LIMIT_EXCEEDED`, `Publish event PaymentHeld`) |
| `Severity` | `must` hoặc `should` — chỉ hai giá trị này |

**Bảng NFR** (mục 4) — đúng 5 cột:

| Cột | Ví dụ |
|---|---|
| `Mã` | `NFR-LAT-01` |
| `metric` | `latency_p95` · `error_rate` · `query_count` · `consumer_lag` |
| `operator` | `<` `<=` `>` `>=` |
| `threshold` | `500` (chỉ số, không đơn vị, không dấu chấm ngăn nghìn) |
| `unit` | `ms` · `percent` · `queries` · `messages` |

**Bảng ngoại lệ** (mục 2.4): `Mã` · `Điều kiện` · `Xử lý` · `Kết quả cho client`.

**Bảng mã lỗi** (mục 7): `Mã lỗi` · `HTTP status` · `Message` · `Sinh ra ở`.

### 1.7 Mermaid

Mọi flow **phải** có ít nhất một sequence diagram bằng Mermaid. Hai cách đặt trong Confluence:

| Cách | Khi nào | Doc Indexer đọc được? |
|---|---|---|
| **A.** Macro `Mermaid Diagrams for Confluence` (Marketplace) | Có ngân sách app | có — đọc `ac:plain-text-body` |
| **B.** Macro `Code Block`, language `text`, dòng đầu ghi `%% mermaid` | Không cài được app | có — đọc `ac:plain-text-body` |

Script `publish_confluence.py` hỗ trợ cả hai qua biến `MERMAID_MODE=macro|codeblock`.
**Không** dán ảnh PNG của sơ đồ thay cho mã nguồn — ảnh không parse được, `FlowStep.mentions` sẽ rỗng.

Quy ước đặt tên participant trong sequence diagram — **phải trùng `OTEL_SERVICE_NAME`**:

| Alias | `OTEL_SERVICE_NAME` |
|---|---|
| `GW` | `ewallet-gateway` |
| `BFF` | `ewallet-business-customer-mobileapp` |
| `ORD` | `ewallet-payment-order` |
| `BIZ` | `ewallet-payment-business` |
| `TP` | `ewallet-third-party` |
| `PS` | `partner-sim` |
| `NTF` | `ewallet-notification` |
| `K` | `Kafka ewallet.payment.events` |

Ghi đủ cả alias và tên đầy đủ: `participant ORD as ewallet-payment-order`. Doc Indexer lấy phần sau `as`
để resolve node `Service`; alias chỉ dùng cho người đọc.

### 1.8 Bảng ghi nhận thay đổi tài liệu (mục 9)

Giữ nguyên khuôn công ty: `Ngày thay đổi` · `Vị trí thay đổi` · `A/M/D` · `Nguồn gốc` · `Link CR` ·
`Mô tả thay đổi` · `Phiên bản confluence` · `Phiên bản mới`.

Doc Indexer dùng cột `Phiên bản mới` làm `Flow.doc_version`, và dùng `version.number` của Confluence
làm mốc phát hiện **spec drift theo thời gian**: trang đổi version mà baseline runtime không đổi
thì cảnh báo "spec đã sửa, chưa kiểm chứng lại".

---

## 2. Jira

### 2.1 Project

| Hạng mục | Giá trị |
|---|---|
| Project name | `E-Wallet Quality` |
| Project key | **`EWL`** |
| Template | **Company-managed** Scrum (bắt buộc — team-managed không cho tạo custom field dùng chung) |
| Issue type | `Epic` (= Flow) · `Story` (= acceptance scenario) · `Task` · `Bug` · `Sub-task` |

### 2.2 Ánh xạ khái niệm

| Nghiệp vụ | Jira | Khoá nối |
|---|---|---|
| Flow (F1–F6) | **Epic** | `Flow Slug` + label `flow-f1` |
| Acceptance scenario (Gherkin) | **Story** dưới epic | `Rule Codes` |
| Business rule cần cài đặt | **Task** | `Rule Codes` |
| Lỗi có chủ đích / phát hiện của v-quality | **Bug** | `Defect ID` + `Rule Codes` |
| Ràng buộc hiệu năng | **Task** gắn `NFR Codes` | `NFR Codes` |

### 2.3 Custom field bắt buộc

Cách tạo: [`jira/01-custom-fields.md`](jira/01-custom-fields.md).

| Field | Kiểu | Áp cho | Ví dụ |
|---|---|---|---|
| `Flow Slug` | Text (single line) | Epic, Story, Task, Bug | `topup-partner` |
| `Rule Codes` | Labels | tất cả | `R-LIMIT-01` `R-REVIEW-01` |
| `NFR Codes` | Labels | tất cả | `NFR-LAT-01` |
| `Confluence Page` | URL | Epic, Story | link trang `[F1] …` |
| `Service` | Multi-select checkbox | tất cả | `ewallet-payment-business` |
| `Defect ID` | Number | Bug | `1` … `6` |
| `Quality Verdict` | Select: `PASS` / `WARN` / `BLOCK` / `N/A` | tất cả | do Agent ghi |

`Quality Verdict` là field **AI Agent ghi vào**, không phải người. Nó là đầu ra luồng Pre-merge
(CLAUDE.md §2 luồng 1) đọng lại trên Jira thay vì chỉ nằm ở comment PR.

### 2.4 Component (theo service)

`ewallet-gateway` · `ewallet-business-customer-mobileapp` · `ewallet-payment-order` ·
`ewallet-payment-business` · `ewallet-third-party` · `ewallet-notification` · `partner-sim` · `platform-vquality`

Tên component **phải trùng `OTEL_SERVICE_NAME`** — để Agent nối issue ↔ span mà không cần bảng ánh xạ.

### 2.5 Label chuẩn

`flow-f1` … `flow-f6` · `intentional-defect` · `spec-drift` · `test-gap` · `db-antipattern` ·
`nfr-violation` · `missing-event` · `vq-baseline`

### 2.6 Version (Fix Version)

`Stage-C-business-logic` · `Stage-D-specs` · `Stage-E-tests` · `Stage-F-vds`

---

## 3. Bất biến phải luôn đúng (script `validate_specs.py` kiểm)

| # | Bất biến |
|---|---|
| 1 | Mỗi label `slug-*` trong Confluence có đúng 1 trang, và slug đó tồn tại trong `docs/specs/F*.md` |
| 2 | Tập mã `R-*` trong Confluence bằng tập mã `R-*` trong `docs/specs/` (không thừa, không thiếu) |
| 3 | Mỗi rule `severity = must` có ít nhất 1 Story hoặc Task trong Jira mang mã đó ở `Rule Codes` |
| 4 | Mỗi `NFR-*` có đủ 4 thành phần số và có đúng 1 dòng trong `[CATALOG] NFR` |
| 5 | Mỗi trang flow có ít nhất 1 khối mermaid `sequenceDiagram` |
| 6 | Mỗi participant trong mermaid resolve được về một `OTEL_SERVICE_NAME` hợp lệ |
| 7 | Mỗi Epic Jira có `Confluence Page` trỏ tới trang flow tồn tại |
| 8 | 6 `Defect ID` (1–6) đều có đúng 1 Bug, và Bug đó nối tới rule mà nó vi phạm |
