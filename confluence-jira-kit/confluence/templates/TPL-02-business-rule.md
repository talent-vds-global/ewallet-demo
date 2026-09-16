# TEMPLATE — Business Rule (`VQ - Business Rule`)

> Dùng khi một nhóm rule quá dài để nằm gọn trong trang flow, hoặc khi rule dùng chung nhiều flow.
> Trang rule **không** thay thế bảng rule trong trang flow — trang flow vẫn phải liệt kê mã rule
> mà nó áp dụng, để edge `HAS_RULE` sinh ra đúng.

## Page Properties

| Khoá | Giá trị |
|---|---|
| Rule Group | `<LIMIT / FEE / LEDGER / NOTIF ...>` |
| Applies To | `<F1, F2, F3>` |
| Owner | `<BA phụ trách>` |
| Doc Version | `1.0` |
| Status | `DRAFT` |

## 1. Bối cảnh

`<Vì sao nhóm rule này tồn tại. 2–3 câu.>`

## 2. Bảng rule

| Mã | Điều kiện | Hành động | Severity |
|---|---|---|---|
| `R-<NHOM>-01` | `<điều kiện có số cụ thể>` | `<hành động>` | `must` |

## 3. Nguồn giá trị

> Rất quan trọng cho v-quality: mỗi con số trong rule phải nói rõ **đọc từ đâu lúc runtime**.
> Đây là chỗ Code Indexer đối chiếu hằng số trong code với cấu hình thật.

| Mã | Giá trị spec | Nơi cấu hình khi chạy | Class/hằng số dự kiến |
|---|---|---|---|
| `R-<NHOM>-01` | `<50.000.000 VND>` | `<bảng limit_config trong paymentdb>` | `<com.ewallet.payment.domain.LimitPolicy>` |

## 4. Ví dụ tính toán

| Đầu vào | Kết quả đúng | Giải thích |
|---|---|---|
| `<...>` | `<...>` | `<...>` |

## 5. Bảng ghi nhận thay đổi tài liệu

| Ngày thay đổi | Vị trí thay đổi | A/M/D | Nguồn gốc | Link CR | Mô tả thay đổi | Phiên bản confluence | Phiên bản mới |
|---|---|---|---|---|---|---|---|
| `<dd MMM yyyy>` | Toàn bộ | A | `<người tạo>` | | Tạo mới tài liệu | 1 | 1.0 |
