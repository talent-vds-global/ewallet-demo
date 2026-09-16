# Jira — Tạo project và cấu hình nền

> Làm xong file này rồi mới sang `01-custom-fields.md` và `02-workflow-and-screens.md`.
> Thứ tự quan trọng: field phải tồn tại trước khi import CSV, không thì dữ liệu rơi mất.

---

## 1. Tạo project

Jira → **Projects** → **Create project** → **Scrum** → chọn **Company-managed project**.

| Trường | Giá trị |
|---|---|
| Name | `E-Wallet Quality` |
| Key | `EWL` |
| Project type | Company-managed |
| Template | Scrum |
| Access | Private (hoặc theo chính sách công ty) |

> **Bắt buộc là company-managed.** Team-managed project không cho tạo custom field dùng chung giữa các
> project và không cho export field id — mà `Rule Codes`, `Flow Slug`, `Quality Verdict` cần được
> truy vấn bằng JQL và đọc bằng API từ platform. Nếu lỡ tạo team-managed thì phải tạo lại, không convert
> ngược được một cách sạch sẽ.

---

## 2. Issue type

Project settings → **Issue types**. Cần đúng 5 loại:

| Issue type | Dùng cho | Ghi chú |
|---|---|---|
| `Epic` | một flow F1–F6 | 6 epic, không hơn |
| `Story` | một kịch bản nghiệm thu (Gherkin) | |
| `Task` | một rule cần cài đặt, hoặc một NFR cần bảo đảm | |
| `Bug` | vấn đề chất lượng, gồm phát hiện tự động của v-quality | |
| `Sub-task` | chia nhỏ Story khi cần | |

Không thêm issue type tuỳ biến. Nền tảng ánh xạ theo 5 loại chuẩn này; thêm loại lạ thì collector
phải sửa code.

---

## 3. Component — theo service

Project settings → **Components** → **Create component**. Tạo đúng 8 component, **tên trùng tuyệt đối
với `OTEL_SERVICE_NAME`**:

| Component | Lead gợi ý | Mô tả |
|---|---|---|
| `ewallet-gateway` | | Route, không có logic nghiệp vụ |
| `ewallet-business-customer-mobileapp` | | BFF hứng client |
| `ewallet-payment-order` | | Điều phối saga, sở hữu đơn |
| `ewallet-payment-business` | | Business rule và sổ cái |
| `ewallet-third-party` | | Ranh giới ra hệ ngoài |
| `ewallet-notification` | | Thông báo bất đồng bộ |
| `partner-sim` | | Giả lập đối tác |
| `platform-vquality` | | Bản thân nền tảng chất lượng |

> Vì sao tên phải trùng: khi Agent đọc một span có `service.name = ewallet-payment-business` và muốn biết
> "service này đang có issue nào mở", nó query thẳng `component = "ewallet-payment-business"`.
> Tên lệch một ký tự là phải nuôi thêm một bảng ánh xạ, và bảng ánh xạ nào rồi cũng lạc hậu.

---

## 4. Version (Fix Version)

Project settings → **Releases** → tạo 4 version:

| Version | Nội dung |
|---|---|
| `Stage-C-business-logic` | Cài đặt nghiệp vụ |
| `Stage-D-specs` | Tài liệu và sơ đồ |
| `Stage-E-tests` | Test suite và coverage |
| `Stage-F-vds` | Triển khai môi trường VDS |

---

## 5. Label chuẩn

Jira không cho khai báo trước danh sách label, nên đây là quy ước phải giữ bằng kỷ luật (và bằng
`validate_specs.py`):

| Label | Nghĩa |
|---|---|
| `flow-f1` … `flow-f6` | Issue thuộc flow nào |
| `intentional-defect` | Lỗi cài có chủ đích để kiểm thử nền tảng |
| `spec-drift` | Code lệch tài liệu |
| `test-gap` | Đường chạy thật không có test |
| `db-antipattern` | Vấn đề truy vấn DB |
| `nfr-violation` | Vi phạm ngưỡng hiệu năng |
| `missing-event` | Nhánh quên publish event |
| `vq-baseline` | Issue dùng làm mốc so sánh cho nền tảng |

---

## 6. Quyền cho tài khoản bot

Project settings → **Permissions**. Tài khoản `vquality-bot` cần:

| Quyền | Vì sao |
|---|---|
| Browse Projects | đọc issue |
| Edit Issues | ghi `Quality Verdict` |
| Add Comments | đăng kết quả phân tích |

**Không** cấp Delete Issues, Manage Sprints, Administer Projects. Nền tảng chỉ cần đọc và ghi nhận xét;
mọi quyền hơn thế là rủi ro không cần thiết.

---

## 7. Board và workflow

- Board mặc định của Scrum template dùng được, không cần sửa.
- Workflow cần thêm một trạng thái `QUALITY REVIEW` — xem [`02-workflow-and-screens.md`](02-workflow-and-screens.md).

---

## 8. Kiểm tra trước khi import CSV

| # | Kiểm | Cách |
|---|---|---|
| 1 | Project key đúng `EWL` | URL có dạng `/projects/EWL` |
| 2 | Đủ 5 issue type | Project settings → Issue types |
| 3 | Đủ 8 component | Project settings → Components |
| 4 | Đủ 4 version | Project settings → Releases |
| 5 | Đủ 7 custom field và đã gắn vào screen | tạo thử một issue, xem field có hiện không |
| 6 | Đã ghi lại `customfield_XXXXX` id | `GET /rest/api/3/field` |

Thiếu bước 5 thì import CSV sẽ "thành công" nhưng dữ liệu rơi âm thầm — Jira không báo lỗi khi map
vào field không tồn tại trên screen.
