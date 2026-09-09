# Spec nghiệp vụ — ví điện tử demo (Stage D)

> Tài liệu nghiệp vụ của hệ thống được đem ra quan sát. Ba vai trò:
> 1. **Đặc tả để code Stage C** — đủ chi tiết để viết thẳng ra Java, không phải đoán.
> 2. **Đầu vào của Doc Indexer** — mỗi `Flow`, `BusinessRule`, `NFRConstraint` ở đây trở thành node trong Knowledge Graph.
> 3. **Cơ sở đối chiếu** — platform so spec ↔ code ↔ trace để tìm drift.

## Thứ tự đọc

| # | File | Nội dung |
|---|---|---|
| 0 | [`00-domain-and-conventions.md`](00-domain-and-conventions.md) | Miền nghiệp vụ, actor, quy ước API, trạng thái, rule dùng chung, mô hình dữ liệu đích, seed demo, event schema, NFR |
| 1 | [`01-api-contracts.md`](01-api-contracts.md) | Toàn bộ hợp đồng REST / gRPC / Kafka / WebSocket / SSE |
| 2 | [`F1-topup-partner.md`](F1-topup-partner.md) | Nạp tiền ví qua đối tác |
| 3 | [`F2-bill-telco.md`](F2-bill-telco.md) | Thanh toán hoá đơn & nạp điện thoại |
| 4 | [`F3-p2p-transfer.md`](F3-p2p-transfer.md) | Chuyển tiền P2P |
| 5 | [`F4-failure-refund.md`](F4-failure-refund.md) | Giao dịch lỗi & hoàn tiền (compensation) |
| 6 | [`F5-async-notification.md`](F5-async-notification.md) | Thông báo bất đồng bộ (Kafka, 2 consumer group, SSE) |
| 7 | [`F6-transaction-history.md`](F6-transaction-history.md) | Tra cứu lịch sử giao dịch (flow đọc) |

Sơ đồ tổng: [`../diagrams/00-container.md`](../diagrams/00-container.md) ·
Máy trạng thái: [`../diagrams/01-state-machines.md`](../diagrams/01-state-machines.md) ·
Chạy local & demo: [`../local-run.md`](../local-run.md)

## Cấu trúc chuẩn của một file flow

Mọi file `F*.md` theo đúng một khung, để Doc Indexer parse được bằng heading:

```
PHẦN 1 — REQUIREMENT
  1.1 Mục tiêu · 1.2 Phạm vi · 1.3 Tiền điều kiện · 1.4 Hậu điều kiện
  1.5 Luồng chính (bảng bước) · 1.6 Luồng phụ & ngoại lệ
  1.7 Business rule của flow (bảng R-*) · 1.8 NFR (bảng NFR-*)
  1.9 Acceptance criteria (Gherkin)
PHẦN 2 — DESIGN
  2.x Service tham gia · Sequence diagram (mermaid) · Hợp đồng dùng trong flow
      Dữ liệu thay đổi · Xử lý lỗi · Quan sát kỳ vọng · Lỗi có chủ đích chạm vào
PHẦN 3 — TEST & DEMO
  3.x Kịch bản demo (curl) · Kiểm chứng · Test suite Stage E
```

## Quy ước mã

| Loại | Định dạng | Ví dụ |
|---|---|---|
| Flow slug | kebab-case | `topup-partner` |
| Business rule | `R-<NHÓM>-<số>` | `R-LIMIT-01`, `R-COMP-04` |
| NFR | `NFR-<NHÓM>-<số>` | `NFR-LAT-01` |
| Bước saga | `S<n>` | `S2 AUTHORIZE` |
| Ngoại lệ | 1 chữ cái theo flow + số | `A7` (F1), `B4` (F2), `C6` (F3), `D3` (F5), `E2` (F6) |

## Ma trận flow ↔ service

| | gateway | mobileapp | order | business | third-party | partner-sim | notification | Kafka |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| **F1** topup | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **F2** bill/telco | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **F3** P2P | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | ✅ |
| **F4** refund | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **F5** notify | — | — | ✅ | ✅ | — | — | ✅ | ✅ |
| **F6** history | ✅ | ✅ | ✅ | — | — | — | — | — |

**Xương sống dùng chung**: `order → business → Kafka` có ở F1–F5 → sửa `payment-business` ảnh hưởng 5/6 flow.
**Nhánh riêng**: `third-party` chỉ ở F1, F2, F4 → đây là cách kiểm chứng impact analysis.

## Ma trận flow ↔ giao thức

| | HTTP | gRPC | Kafka | WebSocket | SSE | JDBC |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| **F1** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **F2** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **F3** | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| **F4** | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| **F5** | — | — | ✅ | — | ✅ | ✅ |
| **F6** | ✅ | — | — | — | — | ✅ |

## Ma trận truy vết: rule ↔ code ↔ lỗi có chủ đích

| Rule / NFR (spec) | Nơi cài đặt thực tế | Lỗi | Trạng thái | Platform phát hiện bằng |
|---|---|---|---|---|
| `R-LIMIT-01` = 50.000.000đ | `payment.domain.LimitPolicy.DAILY_TRANSFER_LIMIT` = **100.000.000** | **#1** | ✅ đã cài | Doc Indexer (rule) ↔ Code Indexer (hằng số) ↔ DB `limit_config` |
| `R-REVIEW-01` + `R-EVENT-01` | `payment.service.PaymentService.authorize()` — nhánh `HELD` `return` mà không gọi `publishHeld()` | **#2** | ✅ đã cài | Trace: nhánh HELD không có span `publish` |
| F1 đường `TOP_UP` | `thirdparty.partner.TopupAdapter` + nhánh `TOP_UP` | **#3** | ⬜ chờ third-party | JaCoCo coverage = 0 ↔ `FlowObservation` tồn tại |
| `R-HIST-06`, `R-HIST-07`, `NFR-DB-01` | `order.history.OrderHistoryService` — vòng lặp query | **#4** | ⬜ chờ order | `database-quality-library`: `N_PLUS_ONE` + `MISSING_INDEX` |
| `NFR-LAT-01` < 500ms | `payment.domain.ReviewPolicy.runManualReviewScreening()` — `Thread.sleep(700)` | **#5** | ✅ đã cài | `SpanStat.p95` ↔ `NFRConstraint` |
| `R-CURRENCY-01`, `R-FX-01` | `payment.grpc.PaymentBusinessGrpcService.authorizePayment()` — gán cứng `currency = "VND"` | **#6** | ✅ đã cài | Attribute span gRPC ↔ rule ↔ cột `amount_vnd` |

Sáu lỗi này là **hợp đồng nghiệm thu** với platform. Khi code Stage C: cài đúng, **không sửa**.

## Thống kê spec (để Doc Indexer tự kiểm)

| Loại node | Số lượng |
|---|---|
| `Flow` | 6 |
| `BusinessRule` dùng chung | 17 + 5 rule phí |
| `BusinessRule` riêng flow | F1: 6 · F2: 9 · F3: 7 · F4: 8 · F5: 10 · F6: 7 |
| `NFRConstraint` | 14 |
| Sequence diagram trong spec | 14 |
| Sơ đồ trong `docs/diagrams/` | 13 |
| Tổng khối Mermaid | 27 (đã kiểm bằng mermaid parser, 0 lỗi) |
