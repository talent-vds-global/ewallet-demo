# Jira — Custom field

> 7 field. Mỗi field có một lý do tồn tại cụ thể trong luồng của nền tảng v-quality; không field nào
> "để cho đẹp".

---

## 1. Danh sách field

Jira → **Settings** (bánh răng) → **Issues** → **Custom fields** → **Create custom field**.

| # | Tên field | Kiểu Jira | Áp cho issue type | Vì sao cần |
|---|---|---|---|---|
| 1 | `Flow Slug` | Text Field (single line) | Epic, Story, Task, Bug | Khoá nối duy nhất giữa Jira và node `Flow` trong Knowledge Graph |
| 2 | `Rule Codes` | Labels | tất cả | Cho phép hỏi "rule `R-LIMIT-01` đang được ai cài đặt, test nào phủ" |
| 3 | `NFR Codes` | Labels | tất cả | Nối issue với `NFRConstraint` khi có vi phạm hiệu năng |
| 4 | `Confluence Page` | URL Field | Epic, Story | Chiều Jira → tài liệu |
| 5 | `Service` | Select List (multiple choices) | tất cả | Nối issue với `service.name` trong span |
| 6 | `Defect ID` | Number Field | Bug | Định danh 6 lỗi có chủ đích |
| 7 | `Quality Verdict` | Select List (single choice) | tất cả | Chỗ đậu cho kết luận của AI Agent |

---

## 2. Cấu hình từng field

### 2.1 `Flow Slug`

- Kiểu: **Text Field (single line)**
- Description: `Slug của flow nghiệp vụ, kebab-case, trùng với label slug-* trên Confluence`
- Giá trị hợp lệ: `topup-partner` · `bill-telco-payment` · `p2p-transfer` · `failure-refund` ·
  `async-notification` · `transaction-history`

> Dùng Text chứ không dùng Select vì flow mới sẽ được thêm liên tục, và mỗi lần thêm option là một lần
> phải nhờ Jira admin. Đổi lại phải chấp nhận rủi ro gõ sai — `validate_specs.py` bắt lỗi này (bất biến #1).

### 2.2 `Rule Codes`

- Kiểu: **Labels**
- Description: `Danh sách mã business rule liên quan, ví dụ R-LIMIT-01`

> Kiểu Labels cho phép nhiều giá trị và tìm được bằng JQL `"Rule Codes" = "R-LIMIT-01"`.
> **Không** map vào field `Labels` mặc định của Jira — nếu trộn chung, JQL lọc theo label nghiệp vụ
> (`flow-f1`, `intentional-defect`) sẽ lẫn với mã rule.

### 2.3 `NFR Codes`

- Kiểu: **Labels**
- Description: `Danh sách mã NFR liên quan, ví dụ NFR-LAT-01`

### 2.4 `Confluence Page`

- Kiểu: **URL Field**
- Description: `Link tới trang Confluence mô tả nghiệp vụ`

### 2.5 `Service`

- Kiểu: **Select List (multiple choices)**
- Options — đúng 8, trùng `OTEL_SERVICE_NAME`:

```
ewallet-gateway
ewallet-business-customer-mobileapp
ewallet-payment-order
ewallet-payment-business
ewallet-third-party
ewallet-notification
partner-sim
platform-vquality
```

> Field này trùng thông tin với Component. Vẫn giữ cả hai vì Component dùng cho báo cáo và phân quyền
> theo quy trình Jira, còn `Service` là field platform đọc — nó không bị ràng buộc bởi cấu hình
> component của project khác nếu sau này nhân rộng sang project khác.

### 2.6 `Defect ID`

- Kiểu: **Number Field**
- Description: `Số thứ tự của lỗi có chủ đích, 1 đến 6`

### 2.7 `Quality Verdict`

- Kiểu: **Select List (single choice)**
- Options:

```
PASS
WARN
BLOCK
N/A
```

- Default: `N/A`
- Description: `Kết luận của AI Agent v-quality. Do nền tảng ghi, người không sửa tay.`

---

## 3. Gắn field vào screen

Settings → **Issues** → **Screens**. Với mỗi screen của project `EWL`
(`EWL: Scrum Default Issue Screen`, `EWL: Scrum Bug Screen` nếu có):

| Screen | Field cần có |
|---|---|
| Create Issue | `Flow Slug`, `Rule Codes`, `NFR Codes`, `Service` |
| Edit Issue | tất cả 7 |
| View Issue | tất cả 7 |

Sau khi gắn, tạo thử một issue để chắc chắn field hiện ra. Nếu field không hiện, kiểm tra
**Field Configuration Scheme** của project — field có thể đang bị ẩn ở tầng đó.

---

## 4. Lấy `customfield_XXXXX` id

CSV import và các script đều cần id thật, không dùng được tên hiển thị.

```bash
curl -s -u "$ATLASSIAN_EMAIL:$ATLASSIAN_API_TOKEN" \
  "$ATLASSIAN_BASE_URL/rest/api/3/field" \
  | python -c "import json,sys; [print(f\"{f['id']}\t{f['name']}\") for f in json.load(sys.stdin) if f.get('custom')]"
```

Ghi kết quả vào bảng dưới rồi điền tiếp vào `.env`:

| Field | customfield id | Điền vào `.env` |
|---|---|---|
| `Flow Slug` | `customfield_?????` | `JIRA_CF_FLOW_SLUG` |
| `Rule Codes` | `customfield_?????` | `JIRA_CF_RULE_CODES` |
| `NFR Codes` | `customfield_?????` | `JIRA_CF_NFR_CODES` |
| `Confluence Page` | `customfield_?????` | `JIRA_CF_CONFLUENCE_PAGE` |
| `Service` | `customfield_?????` | `JIRA_CF_SERVICE` |
| `Defect ID` | `customfield_?????` | `JIRA_CF_DEFECT_ID` |
| `Quality Verdict` | `customfield_?????` | `JIRA_CF_VERDICT` |

---

## 5. Kiểm tra bằng JQL

Sau khi import CSV, chạy các câu sau. Mỗi câu phải trả về kết quả khác rỗng:

```
project = EWL AND "Flow Slug" IS NOT EMPTY
project = EWL AND "Rule Codes" = "R-LIMIT-01"
project = EWL AND "NFR Codes" = "NFR-LAT-01"
project = EWL AND "Service" = "ewallet-payment-business"
project = EWL AND "Defect ID" IS NOT EMPTY
project = EWL AND "Quality Verdict" != "N/A"
```

Câu cuối có thể rỗng lúc đầu — nó chỉ có dữ liệu sau khi Agent chạy lần đầu.
