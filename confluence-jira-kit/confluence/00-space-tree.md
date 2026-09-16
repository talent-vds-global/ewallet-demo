# Cây trang Confluence space `EWL`

## Sơ đồ

```
E-Wallet Business Specs                       ← trang gốc, nội dung: pages/00-HOME.md
│   labels: vq-spec
│
├── [COMMON] Mien nghiep vu va quy uoc        ← pages/01-DOMAIN-CONVENTIONS.md
│       labels: vq-spec, vq-domain
│
├── [API] Hop dong REST gRPC Kafka            ← pages/02-API-CONTRACTS.md
│       labels: vq-spec, vq-api
│
├── Flow nghiep vu                            ← trang gom nhóm, không có nội dung riêng
│   │   labels: vq-spec
│   ├── [F1] Nap tien vi qua doi tac          ← pages/F1-topup-partner.md
│   │       labels: vq-spec, vq-flow, flow-f1, slug-topup-partner
│   ├── [F2] Thanh toan hoa don va nap telco  ← pages/F2-bill-telco.md
│   │       labels: vq-spec, vq-flow, flow-f2, slug-bill-telco-payment
│   ├── [F3] Chuyen tien P2P                  ← pages/F3-p2p-transfer.md
│   │       labels: vq-spec, vq-flow, flow-f3, slug-p2p-transfer
│   ├── [F4] Giao dich loi va hoan tien       ← pages/F4-failure-refund.md
│   │       labels: vq-spec, vq-flow, flow-f4, slug-failure-refund
│   ├── [F5] Thong bao bat dong bo            ← pages/F5-async-notification.md
│   │       labels: vq-spec, vq-flow, flow-f5, slug-async-notification
│   └── [F6] Tra cuu lich su giao dich        ← pages/F6-transaction-history.md
│           labels: vq-spec, vq-flow, flow-f6, slug-transaction-history
│
└── Catalog                                   ← trang gom nhóm
    │   labels: vq-spec
    ├── [CATALOG] Business Rule               ← pages/90-RULE-CATALOG.md
    │       labels: vq-spec, vq-rule-catalog
    ├── [CATALOG] NFR                         ← pages/91-NFR-CATALOG.md
    │       labels: vq-spec, vq-nfr
    └── [CATALOG] Traceability                ← pages/92-TRACEABILITY-MATRIX.md
            labels: vq-spec, vq-traceability
```

## Bảng đối chiếu file ↔ trang

| File trong kit | Tiêu đề trang Confluence | Trang cha | Label |
|---|---|---|---|
| `pages/00-HOME.md` | `E-Wallet Business Specs` | — | `vq-spec` |
| `pages/01-DOMAIN-CONVENTIONS.md` | `[COMMON] Mien nghiep vu va quy uoc` | gốc | `vq-spec`, `vq-domain` |
| `pages/02-API-CONTRACTS.md` | `[API] Hop dong REST gRPC Kafka` | gốc | `vq-spec`, `vq-api` |
| — | `Flow nghiep vu` | gốc | `vq-spec` |
| `pages/F1-topup-partner.md` | `[F1] Nap tien vi qua doi tac` | Flow nghiep vu | `vq-spec`, `vq-flow`, `flow-f1`, `slug-topup-partner` |
| `pages/F2-bill-telco.md` | `[F2] Thanh toan hoa don va nap telco` | Flow nghiep vu | `vq-spec`, `vq-flow`, `flow-f2`, `slug-bill-telco-payment` |
| `pages/F3-p2p-transfer.md` | `[F3] Chuyen tien P2P` | Flow nghiep vu | `vq-spec`, `vq-flow`, `flow-f3`, `slug-p2p-transfer` |
| `pages/F4-failure-refund.md` | `[F4] Giao dich loi va hoan tien` | Flow nghiep vu | `vq-spec`, `vq-flow`, `flow-f4`, `slug-failure-refund` |
| `pages/F5-async-notification.md` | `[F5] Thong bao bat dong bo` | Flow nghiep vu | `vq-spec`, `vq-flow`, `flow-f5`, `slug-async-notification` |
| `pages/F6-transaction-history.md` | `[F6] Tra cuu lich su giao dich` | Flow nghiep vu | `vq-spec`, `vq-flow`, `flow-f6`, `slug-transaction-history` |
| — | `Catalog` | gốc | `vq-spec` |
| `pages/90-RULE-CATALOG.md` | `[CATALOG] Business Rule` | Catalog | `vq-spec`, `vq-rule-catalog` |
| `pages/91-NFR-CATALOG.md` | `[CATALOG] NFR` | Catalog | `vq-spec`, `vq-nfr` |
| `pages/92-TRACEABILITY-MATRIX.md` | `[CATALOG] Traceability` | Catalog | `vq-spec`, `vq-traceability` |

Tổng: **12 trang có nội dung** + 2 trang gom nhóm = 14 trang. CQL `label = "vq-spec"` trả 14 dòng;
trong đó `label = "vq-flow"` trả đúng 6.

## Vì sao tách "Flow nghiep vu" và "Catalog" thành trang gom nhóm

Confluence đánh chỉ mục theo cây cha–con. Khi Doc Indexer kéo về, quan hệ cha–con trở thành edge
`BELONGS_TO`, cho phép AI Agent hỏi "flow nào thuộc nhóm nào" mà không cần đọc nội dung.
Cây phẳng thì mọi trang ngang hàng và thông tin phân nhóm mất.

## Quy tắc khi thêm feature mới về sau

1. Trang mới **luôn** nằm dưới `Flow nghiep vu`.
2. Tiêu đề bắt đầu bằng `[F<n>]` với `n` là số tiếp theo chưa dùng.
3. Tạo trang bằng template `VQ - Flow Spec`, không copy trang cũ (copy sẽ kéo theo label sai slug).
4. Gắn đủ 4 label: `vq-spec`, `vq-flow`, `flow-f<n>`, `slug-<slug-mới>`.
5. Tạo Epic Jira tương ứng ngay, điền `Flow Slug` và `Confluence Page`, rồi điền ngược `Jira Epic`
   vào Page Properties.
6. Chạy `validate_specs.py` trước khi coi là xong.
