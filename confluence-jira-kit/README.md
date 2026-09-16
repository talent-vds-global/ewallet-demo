# Confluence + Jira Kit cho `ewallet-demo` (đầu vào của v-quality)

> Bộ tài liệu & script để dựng **Confluence space** + **Jira project** cho hệ ví điện tử demo,
> theo đúng khuôn mẫu tài liệu nghiệp vụ của công ty, **và** theo một hợp đồng cấu trúc (§ `01-CONVENTIONS.md`)
> để **Doc Indexer của v-quality** parse được thành node `Flow` / `FlowStep` / `BusinessRule` / `NFRConstraint`
> rồi đối chiếu với trace runtime.

## Nguồn gốc nội dung

Toàn bộ nghiệp vụ trong kit này được trích từ `ewallet-demo/docs/specs/` (6 flow F1–F6, 62 business rule,
14 NFR, 6 lỗi có chủ đích). Confluence là **bản chính thức cho người đọc**; `docs/specs/*.md` giữ vai trò
bản gốc kỹ thuật. Hai bên phải khớp — chính kit này ép chúng khớp bằng script đối chiếu.

## Đọc theo thứ tự

| # | File | Dùng khi nào |
|---|---|---|
| 1 | [`00-SETUP-GUIDE.md`](00-SETUP-GUIDE.md) | **Bắt đầu ở đây.** 9 bước dựng Confluence + Jira, từ tạo space tới lúc Doc Indexer chạy được |
| 2 | [`01-CONVENTIONS.md`](01-CONVENTIONS.md) | Hợp đồng cấu trúc: đặt tên trang, label, bảng bắt buộc, custom field Jira. **Vi phạm file này = Doc Indexer không parse được** |
| 3 | [`confluence/00-space-tree.md`](confluence/00-space-tree.md) | Cây trang cần tạo |
| 4 | `confluence/templates/` | 3 template Confluence (flow spec, rule, NFR) |
| 5 | `confluence/pages/` | 12 trang **nội dung sẵn**, paste hoặc publish bằng script |
| 6 | `jira/` | Cấu hình project, custom field, workflow + 4 file CSV import sẵn |
| 7 | `integration/` | Hợp đồng Doc Indexer + 4 script Python (publish / export / đối chiếu) |

## Cấu trúc thư mục

```
confluence-jira-kit/
├── README.md                       ← file này
├── 00-SETUP-GUIDE.md               ← hướng dẫn từng bước
├── 01-CONVENTIONS.md               ← hợp đồng cấu trúc (quan trọng nhất)
├── confluence/
│   ├── 00-space-tree.md
│   ├── templates/
│   │   ├── TPL-01-flow-spec.md     ← khuôn 1 feature (theo mẫu công ty + mermaid)
│   │   ├── TPL-02-business-rule.md
│   │   └── TPL-03-nfr.md
│   └── pages/
│       ├── 00-HOME.md
│       ├── 01-DOMAIN-CONVENTIONS.md
│       ├── 02-API-CONTRACTS.md
│       ├── F1-topup-partner.md     ← 6 trang flow, nội dung đầy đủ
│       ├── F2-bill-telco.md
│       ├── F3-p2p-transfer.md
│       ├── F4-failure-refund.md
│       ├── F5-async-notification.md
│       ├── F6-transaction-history.md
│       ├── 90-RULE-CATALOG.md
│       ├── 91-NFR-CATALOG.md
│       └── 92-TRACEABILITY-MATRIX.md
├── jira/
│   ├── 00-jira-setup.md
│   ├── 01-custom-fields.md
│   ├── 02-workflow-and-screens.md
│   └── csv/
│       ├── 01-epics.csv            ← 6 epic = 6 flow
│       ├── 02-stories.csv          ← 36 story = acceptance criteria
│       ├── 03-rule-tasks.csv       ← task cho rule có rủi ro drift
│       ├── 04-defects.csv          ← 6 lỗi có chủ đích (làm baseline verdict)
│       └── README.md               ← cách import và map field
└── integration/
    ├── doc-indexer-contract.md     ← Confluence/Jira → node Knowledge Graph
    └── scripts/
        ├── requirements.txt
        ├── .env.example
        ├── _common.py           ← tiện ích dùng chung (bảng ánh xạ trang, label)
        ├── publish_confluence.py   ← đẩy markdown lên Confluence
        ├── export_confluence.py    ← kéo Confluence về markdown cho Doc Indexer
        ├── export_jira.py          ← kéo issue + link rule về JSON
        └── validate_specs.py       ← đối chiếu Confluence ↔ docs/specs ↔ code
```

## Nguyên tắc nền

1. **Confluence = nguồn sự thật nghiệp vụ.** Giá trị trong Confluence là giá trị **đúng**; code lệch là drift.
2. **Mọi rule đều có mã.** Không có mã ⇒ Doc Indexer không tạo node ⇒ không đối chiếu được.
3. **Mọi flow đều có mermaid sequence diagram.** Sequence diagram là nơi Doc Indexer trích `FlowStep.mentions`
   (service, endpoint, topic) để nối sang trace.
4. **Mọi NFR đều có 4 phần**: `metric` · `operator` · `threshold` · `unit`. Không có số ⇒ không so được với p95.
5. **Jira nối Confluence bằng mã**, không bằng chữ: `Flow Slug`, `Rule Codes`, `NFR Codes`.
