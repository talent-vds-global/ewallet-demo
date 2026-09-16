# [CATALOG] Traceability

> Ma trận truy vết **rule (tài liệu) ↔ code ↔ runtime**. Đây là trang mà Lead và Ops mở khi muốn biết
> "quy định này được kiểm ở đâu, và ai chứng minh nó đang đúng".
>
> Sáu dòng ở mục 3 là **hợp đồng nghiệm thu** với platform v-quality: nếu platform không tự tìm ra
> đủ sáu vấn đề này từ Knowledge Graph, platform chưa đạt.

## Page Properties

| Khoá | Giá trị |
|---|---|
| Loại tài liệu | Catalog |
| Applies To | F1, F2, F3, F4, F5, F6 |
| Spec Source | docs/specs/README.md, docs/architecture.md |
| Doc Version | 1.0 |
| Status | APPROVED |

---

# 1. Ma trận flow ↔ service

| | gateway | mobileapp | order | business | third-party | partner-sim | notification | Kafka |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| F1 topup | x | x | x | x | x | x | x | x |
| F2 bill/telco | x | x | x | x | x | x | x | x |
| F3 P2P | x | x | x | x | — | — | x | x |
| F4 refund | x | x | x | x | x | x | x | x |
| F5 notify | — | — | x | x | — | — | x | x |
| F6 history | x | x | x | — | — | — | — | — |

**Xương sống dùng chung**: `order → business → Kafka` có ở F1–F5, nên sửa `ewallet-payment-business`
ảnh hưởng 5 trên 6 flow.

**Nhánh riêng**: `ewallet-third-party` chỉ có ở F1, F2, F4 — đây là cách kiểm chứng impact analysis:
sửa third-party **không được** báo ảnh hưởng F3, F5, F6.

# 2. Ma trận flow ↔ giao thức

| | HTTP | gRPC | Kafka | WebSocket | SSE | JDBC |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| F1 | x | x | x | x | x | x |
| F2 | x | x | x | x | x | x |
| F3 | x | x | x | — | x | x |
| F4 | x | x | x | — | x | x |
| F5 | — | — | x | — | x | x |
| F6 | x | — | — | — | — | x |

---

# 3. Ma trận truy vết rule ↔ code ↔ phát hiện

> Bảng này liệt kê các điểm **đã biết là lệch** giữa tài liệu và code trong môi trường demo.
> Chúng được giữ nguyên có chủ đích để làm bộ kiểm thử cho platform — **không sửa code** để "làm đẹp" bảng này.

| # | Rule / NFR (tài liệu) | Giá trị đúng theo tài liệu | Biểu hiện trong code | Flow chạm | Platform phải phát hiện bằng | Jira |
|---|---|---|---|---|---|---|
| 1 | `R-LIMIT-01` | Hạn mức ngày 50.000.000đ, đọc từ `limit_config` | `LimitPolicy.DAILY_TRANSFER_LIMIT` hard-code 100.000.000 | F1, F2, F3 | Doc Indexer (rule) so Code Indexer (hằng số) so DB `limit_config` | EWL-BUG-1 |
| 2 | `R-REVIEW-01` + `R-EVENT-01` | Nhánh HELD phải publish `PaymentHeld` | Nhánh `HELD` trong `PaymentService.authorize()` `return` mà không gọi publish | F1, F2, F3 | Trace: nhánh HELD không có span `publish` | EWL-BUG-2 |
| 3 | Đường `TOP_UP` của F1 | Mọi đường chạy production đều phải có test | `thirdparty.partner.TopupAdapter` không có test | F1 | JaCoCo coverage 0% trên lớp mà `FlowObservation` xác nhận có chạy | EWL-BUG-3 |
| 4 | `R-HIST-06`, `R-HIST-07`, `NFR-DB-01` | Một truy vấn cho nhiều đơn, có index `order_steps(order_id)` | `OrderHistoryService.history()` lặp `findByOrderIdOrderByCreatedAtAsc` theo từng đơn, không có index | F6 | `database-quality-library`: `N_PLUS_ONE` + `MISSING_INDEX`, hoặc `pg_stat_user_tables.seq_scan` | EWL-BUG-4 |
| 5 | `NFR-LAT-01` | p95 của `AuthorizePayment` dưới 500ms | `ReviewPolicy.runManualReviewScreening()` có `Thread.sleep(700)` | F1, F2, F3 | `SpanStat.p95` so `NFRConstraint` | EWL-BUG-5 |
| 6 | `R-CURRENCY-01`, `R-FX-01` | Quy đổi theo `fx_rates` trước khi áp hạn mức | `PaymentBusinessGrpcService.authorizePayment()` gán cứng `currency = "VND"` | F2 | Attribute span gRPC so rule so cột `amount_vnd` | EWL-BUG-6 |

## 3.1 Vì sao lỗi #6 nguy hiểm nhất

Lỗi #6 gây ra **hai kiểu sai ngược nhau**, tuỳ số tiền:

| Ca | `amount` | Đúng theo tài liệu | Code thực tế | Mức nguy hiểm |
|---|---|---|---|---|
| Từ chối nhầm | 1.000 USD = 25.000.000đ | `HELD` vì vượt ngưỡng rà soát | `REJECTED AMOUNT_TOO_SMALL` (coi là 1.000đ) | phiền khách |
| **Chấp nhận nhầm** | 15.000 USD = 375.000.000đ | `REJECTED AMOUNT_TOO_LARGE` | `AUTHORIZED` (coi là 15.000đ) | **mất tiền thật** |

Ca thứ hai là ca đáng sợ: giao dịch 375 triệu lọt qua mọi hạn mức vì bị tính như 15 nghìn.
Test đơn vị không bắt được vì test được viết theo hành vi của code, không theo tài liệu.
Chỉ đối chiếu tài liệu ↔ trace mới lộ ra.

---

# 4. Ba loại đối chiếu platform thực hiện

| Loại | So cái gì với cái gì | Bắt được lỗi nào |
|---|---|---|
| rule ↔ code | `BusinessRule.condition` so hằng số và nhánh `if` trong `Method` | #1, #6 |
| rule ↔ trace | `BusinessRule.action` ("publish PaymentHeld") so edge `PRODUCES` quan sát được | #2 |
| trace ↔ coverage | `FlowObservation` so report JaCoCo | #3 |
| trace ↔ baseline / NFR | `SpanStat.p95` so `Baseline` cũ và so `NFRConstraint` | #4, #5 |

---

# 5. Nguồn dữ liệu của Knowledge Graph

| Nguồn | Công cụ thu | Nội dung | Node sinh ra |
|---|---|---|---|
| Tài liệu nghiệp vụ | Doc Indexer đọc Confluence space `EWL` | Flow, rule, NFR, bước nghiệp vụ | `Flow`, `FlowStep`, `BusinessRule`, `NFRConstraint` |
| Quản lý công việc | Jira collector đọc project `EWL` | Epic, Story, Task, Bug, verdict | `WorkItem`, `Defect` |
| Source code | Code Indexer đọc GitLab | Class, method, hằng số, call graph | `Service`, `Class`, `Method`, `Constant` |
| Runtime | Trace Analyzer đọc OTel collector | Span, latency, quan hệ gọi | `Span`, `SpanStat`, `FlowObservation`, `Baseline` |
| Database | `database-quality-library` | SQL pattern, N+1, index, EXPLAIN | `QueryPattern`, `DbFinding` |

---

# 6. Bảng ghi nhận thay đổi tài liệu

| Ngày thay đổi | Vị trí thay đổi | A/M/D | Nguồn gốc | Link CR | Mô tả thay đổi | Phiên bản confluence | Phiên bản mới |
|---|---|---|---|---|---|---|---|
| 16 Sep 2026 | Toàn bộ | A | Stage D specs | | Tạo mới ma trận truy vết từ `docs/specs/README.md` và `docs/architecture.md` | 1 | 1.0 |
