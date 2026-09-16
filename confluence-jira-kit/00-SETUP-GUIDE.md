# 00 — Hướng dẫn dựng Confluence + Jira từng bước

> Mục tiêu cuối: tài liệu nghiệp vụ của `ewallet-demo` nằm trên Confluence đúng khuôn công ty,
> nối với Jira bằng mã, và **collector của v-quality kéo về được** để đối chiếu với trace.
>
> Thời gian ước tính: ~3 giờ nếu làm thủ công, ~40 phút nếu dùng script ở `integration/scripts/`.

## Bản đồ 9 bước

| Bước | Việc | Ở đâu | Xong khi nào |
|---|---|---|---|
| 1 | Chuẩn bị tài khoản & API token | Atlassian | có `.env` điền đủ |
| 2 | Tạo Confluence space `EWL` + cây trang | Confluence | 14 trang rỗng đúng tên (12 trang nội dung + 2 trang gom nhóm) |
| 3 | Tạo 3 template trang | Confluence | template xuất hiện khi tạo trang mới |
| 4 | Đổ nội dung 12 trang | Confluence | mỗi trang có Page Properties + mermaid |
| 5 | Gắn label | Confluence | CQL `space=EWL AND label="vq-spec"` trả 14 trang |
| 6 | Tạo Jira project `EWL` + custom field + workflow | Jira | field xuất hiện trên màn hình Create |
| 7 | Import 4 file CSV | Jira | 6 epic, 36 story, N task, 6 bug |
| 8 | Nối hai chiều Confluence ↔ Jira | cả hai | mỗi epic có link trang, mỗi trang có macro Jira |
| 9 | Cấu hình collector v-quality + chạy đối chiếu | Platform | `validate_specs.py` trả 0 lỗi |

---

## Bước 1 — Chuẩn bị tài khoản và token

> **Dự án cá nhân:** đăng ký site Atlassian Cloud **Free** tại <https://www.atlassian.com/software/confluence/free>
> (đến 10 người dùng, có cả Jira Free). Bạn là admin của site nên bỏ qua mọi mục về tài khoản bot và
> xin quyền bên dưới — dùng luôn email và API token của bạn. Nên tạo một **space thường** key `EWL` thay vì
> dùng personal space; nếu vẫn dùng personal space thì điền key dạng `~xxxx` vào `CONFLUENCE_SPACE_KEY`.

1. Tạo tài khoản dịch vụ **`vquality-bot@<công-ty>.com`** (đừng dùng tài khoản cá nhân — token của bạn
   sẽ chết khi bạn đổi mật khẩu, và log truy cập sẽ lẫn với thao tác người).
2. Tạo API token: <https://id.atlassian.com/manage-profile/security/api-tokens> → **Create API token**.
   Lưu lại ngay, Atlassian chỉ hiện một lần.
3. Quyền tối thiểu cho bot:
   - Confluence: **View** space `EWL`. Nếu dùng script publish thì cần thêm **Add/Delete page**
     (có thể cấp tạm lúc publish rồi hạ xuống View).
   - Jira: **Browse Projects**, **Edit Issues** (để ghi `Quality Verdict`), **Add Comments**.
4. Sao chép file môi trường:

```bash
cp integration/scripts/.env.example integration/scripts/.env
```

Điền `ATLASSIAN_BASE_URL`, `ATLASSIAN_EMAIL`, `ATLASSIAN_API_TOKEN`, `CONFLUENCE_SPACE_KEY=EWL`, `JIRA_PROJECT_KEY=EWL`.

> **Không commit file `.env`.** Thêm vào `.gitignore` của repo chứa kit này.

---

## Bước 2 — Tạo Confluence space và cây trang

1. Confluence → **Create** → **Space** → **Documentation**.
   - Space name: `E-Wallet Business Specs`
   - Space key: `EWL` (gõ tay, đừng để Confluence tự sinh)
2. Tạo cây trang theo [`confluence/00-space-tree.md`](confluence/00-space-tree.md).
   Tạo trang rỗng trước, đổ nội dung sau — làm vậy để link nội bộ giữa các trang không bị gãy.

Cây cần có:

```
E-Wallet Business Specs                      (trang gốc = 00-HOME.md)
├── [COMMON] Mien nghiep vu va quy uoc
├── [API] Hop dong REST gRPC Kafka
├── Flow nghiep vu
│   ├── [F1] Nap tien vi qua doi tac
│   ├── [F2] Thanh toan hoa don va nap telco
│   ├── [F3] Chuyen tien P2P
│   ├── [F4] Giao dich loi va hoan tien
│   ├── [F5] Thong bao bat dong bo
│   └── [F6] Tra cuu lich su giao dich
└── Catalog
    ├── [CATALOG] Business Rule
    ├── [CATALOG] NFR
    └── [CATALOG] Traceability
```

---

## Bước 3 — Tạo template trang

Space settings → **Templates** → **Create new template**. Tạo 3 cái:

| Template | Nguồn | Dùng cho |
|---|---|---|
| `VQ - Flow Spec` | [`confluence/templates/TPL-01-flow-spec.md`](confluence/templates/TPL-01-flow-spec.md) | mọi feature mới |
| `VQ - Business Rule` | [`confluence/templates/TPL-02-business-rule.md`](confluence/templates/TPL-02-business-rule.md) | trang rule riêng khi rule quá dài |
| `VQ - NFR` | [`confluence/templates/TPL-03-nfr.md`](confluence/templates/TPL-03-nfr.md) | catalog NFR |

Trong template `VQ - Flow Spec`, nhớ:
- Đặt macro **Page Properties** ở đầu với đủ 14 khoá (§1.4 của `01-CONVENTIONS.md`).
- Chèn sẵn macro **Code Block** trống có chú thích `%% mermaid` để người viết không quên sơ đồ.
- Chèn sẵn 9 heading chuẩn.

> Lý do phải làm template trước khi đổ nội dung: feature **sau này** sẽ do BA viết tay, không qua script.
> Template là thứ duy nhất giữ cho tài liệu tương lai vẫn parse được.

---

## Bước 4 — Đổ nội dung 12 trang

### Cách A — bằng script (khuyến nghị)

```bash
cd integration/scripts
pip install -r requirements.txt
python publish_confluence.py --dry-run     # xem trước, không ghi gì
python publish_confluence.py               # publish thật
```

Script đọc `confluence/pages/*.md`, chuyển sang Confluence storage format, tạo hoặc cập nhật trang
theo tiêu đề, gắn label, và dựng macro Page Properties + mermaid.

### Cách B — thủ công

Mở từng file trong `confluence/pages/`, copy nội dung, dán vào trang tương ứng. Khi dán Markdown,
Confluence Cloud tự chuyển bảng và heading. Ba thứ **phải sửa tay sau khi dán**:

1. **Page Properties**: bảng metadata ở đầu trang phải được bọc trong macro `Page Properties`
   (chọn bảng → `/Page Properties`). Nếu không, `Page Properties Report` ở trang HOME sẽ trống.
2. **Mermaid**: khối ```` ```mermaid ```` phải thành macro Code Block (language `text`) hoặc macro Mermaid.
3. **Link nội bộ**: đổi link `.md` thành link trang Confluence.

---

## Bước 5 — Gắn label

Mỗi trang → nút **Labels** ở chân trang (hoặc phím tắt `L`). Theo bảng §1.3 của `01-CONVENTIONS.md`.

Kiểm tra bằng CQL — vào thanh tìm kiếm Confluence, chọn **Advanced search**:

```
space = EWL AND label = "vq-spec"
```

Phải trả về **đúng 14 trang** (12 trang nội dung + 2 trang gom nhóm `Flow nghiep vu` và `Catalog`).
Thiếu trang nào thì collector sẽ không thấy trang đó.

```
space = EWL AND label = "vq-flow"
```

Phải trả về **đúng 6 trang**.

---

## Bước 6 — Tạo Jira project, custom field, workflow

Làm theo thứ tự, chi tiết trong `jira/`:

1. [`jira/00-jira-setup.md`](jira/00-jira-setup.md) — tạo project company-managed, key `EWL`,
   khai báo component theo service và version theo stage.
2. [`jira/01-custom-fields.md`](jira/01-custom-fields.md) — tạo 7 custom field, gắn vào screen
   Create/Edit/View. **Ghi lại `customfield_XXXXX` id của từng field** — CSV import và script đều cần.
3. [`jira/02-workflow-and-screens.md`](jira/02-workflow-and-screens.md) — workflow có trạng thái
   `QUALITY REVIEW` để verdict của Agent có chỗ đậu.

> Nếu công ty đã có Jira project dùng chung và bạn không được tạo project mới: vẫn làm được, nhưng
> phải tạo **Component** riêng và dùng prefix label `ewl-`. Khi đó sửa `JIRA_PROJECT_KEY` và
> `JIRA_LABEL_PREFIX` trong `.env`.

---

## Bước 7 — Import CSV

Jira → **Settings** → **System** → **External System Import** → **CSV**.

Import **theo đúng thứ tự** (epic trước, vì story tham chiếu epic bằng `Epic Link`):

| # | File | Số dòng | Ghi chú |
|---|---|---|---|
| 1 | `jira/csv/01-epics.csv` | 6 | ánh xạ `Epic Name` → field Epic Name |
| 2 | `jira/csv/02-stories.csv` | 36 | `Epic Link` dùng **Epic Name**, không phải issue key |
| 3 | `jira/csv/03-rule-tasks.csv` | 18 | task cho các rule rủi ro drift cao |
| 4 | `jira/csv/04-defects.csv` | 6 | 6 lỗi có chủ đích |

Trong màn hình map field, nhớ map:
- `Rule Codes` → custom field Labels đã tạo (không map vào `Labels` mặc định — sẽ lẫn)
- `Flow Slug` → custom field text
- `Service` → custom field multi-select (giá trị phải đã tồn tại trong option list)

Sau import, chạy JQL kiểm tra:

```
project = EWL AND issuetype = Epic ORDER BY key
project = EWL AND "Rule Codes" IS NOT EMPTY
project = EWL AND labels = intentional-defect
```

Câu cuối phải trả về **đúng 6 bug**.

---

## Bước 8 — Nối hai chiều Confluence ↔ Jira

**Confluence → Jira**: trên mỗi trang flow, mục `8. Chức năng ảnh hưởng`, chèn macro **Jira Issues**
với JQL:

```
project = EWL AND "Flow Slug" ~ "topup-partner" ORDER BY issuetype
```

**Jira → Confluence**: mỗi Epic điền custom field `Confluence Page` bằng URL trang flow.
Lấy URL bằng script:

```bash
python integration/scripts/export_confluence.py --list-urls
```

Vì sao cần cả hai chiều: Agent điều tra từ **hai phía**. Luồng Pre-merge bắt đầu từ code diff → tìm flow
→ cần trang Confluence. Luồng Post-deploy bắt đầu từ trace lệch → tìm flow → cần issue Jira để biết
thay đổi nào gây ra. Một chiều thôi thì nửa số câu hỏi không trả lời được.

---

## Bước 9 — Cấu hình collector v-quality và chạy đối chiếu

1. Khai báo nguồn Confluence cho Doc Indexer (theo
   [`integration/doc-indexer-contract.md`](integration/doc-indexer-contract.md)):

```yaml
doc_indexer:
  source: confluence
  base_url: ${ATLASSIAN_BASE_URL}
  auth: basic          # email + api_token
  cql: 'space = EWL AND label = "vq-spec"'
  incremental_by: version.number
  parsers:
    page_properties: true
    tables:
      business_rule: ["Mã", "Điều kiện", "Hành động", "Severity"]
      nfr: ["Mã", "metric", "operator", "threshold", "unit"]
      steps: ["Bước", "Mô tả", "Thực hiện bởi"]
    mermaid: ["sequenceDiagram", "flowchart", "stateDiagram-v2"]
jira:
  base_url: ${ATLASSIAN_BASE_URL}
  jql: 'project = EWL'
  fields: [summary, issuetype, status, components, labels, "Flow Slug", "Rule Codes", "NFR Codes", "Defect ID", "Quality Verdict"]
```

2. Chạy đối chiếu tài liệu ↔ spec gốc ↔ code:

```bash
python integration/scripts/validate_specs.py --specs ../ewallet-demo/docs/specs --confluence --jira
```

Script kiểm 8 bất biến ở §3 của `01-CONVENTIONS.md` và in bảng lệch. Mục tiêu: **0 lỗi cấu trúc**.
Lưu ý: script **không** báo lỗi cho 6 lỗi có chủ đích — đó là drift giữa spec và **code**, thuộc việc
của platform, không phải của tài liệu.

3. Chạy sinh trace rồi đối chiếu:

```bash
cd ../ewallet-demo && docker compose up -d && ./scripts/demo-flows.sh
```

Khi đó Knowledge Graph có đủ ba nguồn (spec từ Confluence, code từ Git, runtime từ trace) và
6 lỗi có chủ đích phải nổi lên đúng như bảng ở `confluence/pages/92-TRACEABILITY-MATRIX.md`.

---

## Nghiệm thu

Kit này coi là dựng xong khi:

| # | Tiêu chí | Cách kiểm |
|---|---|---|
| 1 | 14 trang có label `vq-spec` | CQL ở bước 5 |
| 2 | 6 trang flow đều có Page Properties đủ 14 khoá | Page Properties Report ở trang HOME hiện đủ 6 dòng |
| 3 | 6 trang flow đều có ít nhất 1 mermaid `sequenceDiagram` | `validate_specs.py` bất biến #5 |
| 4 | 62 mã rule trong Confluence khớp `docs/specs/` | bất biến #2 |
| 5 | 14 NFR đều có threshold dạng số | bất biến #4 |
| 6 | Mỗi epic Jira có `Confluence Page` | bất biến #7 |
| 7 | 6 bug `intentional-defect` có `Defect ID` 1–6 | bất biến #8 |
| 8 | Doc Indexer sinh ra 6 `Flow`, 62 `BusinessRule`, 14 `NFRConstraint` | đếm node trong PostgreSQL |

---

## Sai lầm hay gặp

| Triệu chứng | Nguyên nhân | Sửa |
|---|---|---|
| Doc Indexer trả 0 flow | Thiếu label `vq-spec`, hoặc space key viết thường | CQL phân biệt hoa thường ở giá trị label |
| `BusinessRule` sinh ra nhưng `condition` rỗng | Bảng rule bị dán thành 3 cột (mất cột `Severity`) | Dán lại, giữ đủ 4 cột |
| `NFRConstraint.threshold` = null | Viết `500ms` trong cột threshold thay vì `500` + unit `ms` | Tách số và đơn vị |
| Trace không gán được vào flow | `Entry Endpoint` trong Page Properties khác `http.route` thật | Lấy route đúng từ Jaeger rồi sửa lại trang |
| F1 và F2 bị gộp làm một flow | Cả hai cùng chuỗi service, mà `Entry Endpoint` để trống | Điền `POST /api/wallet/topup` vs `POST /api/wallet/bill/pay` |
| `Rule Codes` trên Jira không query được | Map nhầm vào field `Labels` mặc định lúc import | Sửa bằng bulk edit, hoặc import lại |
| Mermaid không hiện | Confluence Cloud không render mermaid mặc định | Dùng `MERMAID_MODE=codeblock`, hoặc cài app Mermaid |
| Trang bị Confluence tự đổi tiêu đề | Trùng tên trang trong cùng space | Giữ prefix `[F1]`, `[F2]` để tên luôn duy nhất |
