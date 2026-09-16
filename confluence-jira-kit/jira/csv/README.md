# CSV import cho Jira

## Thứ tự import

| # | File | Issue | Phụ thuộc |
|---|---|---|---|
| 1 | `01-epics.csv` | 6 Epic | — |
| 2 | `02-stories.csv` | 36 Story | cần epic đã tồn tại |
| 3 | `03-rule-tasks.csv` | 18 Task | cần epic đã tồn tại |
| 4 | `04-defects.csv` | 6 Bug | cần epic đã tồn tại |

Tổng 66 issue.

## Cách import

Jira → **Settings** → **System** → **External System Import** → **CSV** → chọn file → **Next**.

Ở màn hình cấu hình:

| Trường | Giá trị |
|---|---|
| Import to Project | `E-Wallet Quality (EWL)` |
| Date format | `dd/MMM/yy h:mm a` (không dùng tới, nhưng Jira vẫn hỏi) |
| File encoding | **UTF-8** |
| CSV delimiter | `,` |

## Map field

| Cột CSV | Map tới |
|---|---|
| `Summary` | Summary |
| `Issue Type` | Issue Type |
| `Epic Name` | Epic Name (chỉ có ở file epic) |
| `Epic Link` | Epic Link — **điền bằng giá trị `Epic Name`**, không phải issue key |
| `Description` | Description |
| `Priority` | Priority |
| `Component` | Component/s |
| `Fix Version` | Fix Version/s |
| `Labels` | Labels |
| `Flow Slug` | custom field `Flow Slug` |
| `Rule Codes` | custom field `Rule Codes` — **không** map vào `Labels` |
| `NFR Codes` | custom field `NFR Codes` |
| `Service` | custom field `Service` |
| `Defect ID` | custom field `Defect ID` |
| `Quality Verdict` | custom field `Quality Verdict` |
| `Confluence Page` | custom field `Confluence Page` |

## Cột lặp lại

Một số cột xuất hiện **nhiều lần** với cùng tiêu đề (`Rule Codes`, `Labels`, `Service`). Đây là cách Jira
CSV importer nhận giá trị nhiều phần tử: mỗi cột là một giá trị. Ô rỗng bị bỏ qua. Đừng gộp thành
`"R-A,R-B"` trong một ô — Jira sẽ hiểu thành một label duy nhất tên `R-A,R-B`.

## Trước khi import

1. Mở `01-epics.csv`, thay `https://REPLACE.atlassian.net/wiki/spaces/EWL/pages/REPLACE/F1` bằng URL
   thật của từng trang Confluence. Lấy URL bằng:

```bash
python ../../integration/scripts/export_confluence.py --list-urls
```

2. Kiểm tra 7 custom field đã tồn tại và đã gắn vào screen (xem `../01-custom-fields.md` §3).
   Jira **không báo lỗi** khi map vào field chưa gắn screen — dữ liệu chỉ lặng lẽ biến mất.

## Sau khi import

```
project = EWL AND issuetype = Epic
project = EWL AND issuetype = Story AND "Flow Slug" IS NOT EMPTY
project = EWL AND labels = intentional-defect
project = EWL AND "Rule Codes" IS NOT EMPTY
```

Kết quả mong đợi: 6 · 36 · 6 · 60 (số issue có ít nhất một mã rule).

## Nếu import sai và muốn làm lại

Jira không có nút "undo import". Cách sạch nhất: lọc bằng JQL `project = EWL AND created >= -1h`,
chọn tất cả, **Bulk change** → **Delete**. Vì vậy nên import file nhỏ nhất (`01-epics.csv`) trước để
kiểm tra map field đã đúng chưa, rồi mới import ba file còn lại.
