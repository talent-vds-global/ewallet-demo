# TEMPLATE — NFR (`VQ - NFR`)

> Một NFR chỉ dùng được cho v-quality khi nó **đo được**. Câu "hệ thống phải nhanh" không sinh ra node.
> Bốn thành phần bắt buộc: `metric` · `operator` · `threshold` · `unit`.

## Page Properties

| Khoá | Giá trị |
|---|---|
| NFR Group | `<LAT / ERR / DB / NOTIF / COMP / TIMEOUT>` |
| Applies To | `<F1, F2, F3>` |
| Owner | `<Lead / Ops>` |
| Doc Version | `1.0` |
| Status | `DRAFT` |

## 1. Bảng NFR

| Mã | metric | operator | threshold | unit |
|---|---|---|---|---|
| `NFR-<NHOM>-01` | `latency_p95` | `<` | `500` | `ms` |

## 2. Cách đo

| Mã | Đo ở span nào | Thuộc tính lọc | Cửa sổ thời gian |
|---|---|---|---|
| `NFR-<NHOM>-01` | `<rpc PaymentBusiness/AuthorizePayment>` | `<rpc.service, rpc.method>` | `<15 phút trượt>` |

Cột "Đo ở span nào" là cầu nối trực tiếp sang Trace Analyzer. Không điền cột này thì platform biết
ngưỡng nhưng không biết so với cái gì.

## 3. Hành vi khi vi phạm

| Mã | Mức | Hành động của platform |
|---|---|---|
| `NFR-<NHOM>-01` | `<BLOCK / WARN>` | `<chặn PR / cảnh báo Slack kênh nào>` |

## 4. Baseline hiện tại

| Mã | Giá trị đo được gần nhất | Ngày đo | Kết luận |
|---|---|---|---|
| `NFR-<NHOM>-01` | `<p95 = 412ms>` | `<dd MMM yyyy>` | `<đạt / không đạt>` |

## 5. Bảng ghi nhận thay đổi tài liệu

| Ngày thay đổi | Vị trí thay đổi | A/M/D | Nguồn gốc | Link CR | Mô tả thay đổi | Phiên bản confluence | Phiên bản mới |
|---|---|---|---|---|---|---|---|
| `<dd MMM yyyy>` | Toàn bộ | A | `<người tạo>` | | Tạo mới tài liệu | 1 | 1.0 |
