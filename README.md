# ewallet demo-app

Hệ thống "được đem ra quan sát" cho platform AI Quality Control (Người 2).
6 service Spring Boot xoay quanh ví điện tử + partner-sim + hạ tầng OTel.

## Tài liệu

| Đọc gì | Ở đâu |
|---|---|
| **Spec nghiệp vụ F1–F6** (Requirement + Design + sequence diagram) | [`docs/specs/`](docs/specs/README.md) |
| **Chạy local & kịch bản demo** | [`docs/local-run.md`](docs/local-run.md) |
| Kiến trúc & phạm vi (source of truth topology) | [`docs/architecture.md`](docs/architecture.md) |
| Sơ đồ container / bản đồ flow / máy trạng thái | [`docs/diagrams/`](docs/diagrams/00-container.md) |
| Hợp đồng dữ liệu cho collector | [`docs/collector-data-contract.md`](docs/collector-data-contract.md) |
| Tích hợp `database-quality-library` (Topic #80) | [`docs/db-quality-integration.md`](docs/db-quality-integration.md) |
| Demo hiện làm được gì | [`docs/demo-status.md`](docs/demo-status.md) |

## Sáu flow nghiệp vụ

| Flow | Tên | Đường đi |
|---|---|---|
| [F1](docs/specs/F1-topup-partner.md) | Nạp tiền ví qua đối tác | gateway → mobileapp → order → business → third-party → partner-sim → Kafka → notification |
| [F2](docs/specs/F2-bill-telco.md) | Thanh toán hoá đơn / nạp telco | như F1, thêm bước tra cứu hoá đơn |
| [F3](docs/specs/F3-p2p-transfer.md) | Chuyển tiền P2P | gateway → mobileapp → order → business → Kafka → notification |
| [F4](docs/specs/F4-failure-refund.md) | Giao dịch lỗi & hoàn tiền | nhánh bù trừ do order điều phối |
| [F5](docs/specs/F5-async-notification.md) | Thông báo bất đồng bộ | business → Kafka (2 consumer group) → notification (SSE) + order |
| [F6](docs/specs/F6-transaction-history.md) | Tra cứu lịch sử giao dịch | gateway → mobileapp → order → orderdb |

## Service

| Service | Port | Giao thức | Control flow |
|---|---|---|---|
| ewallet-gateway | 8080 | HTTP | không |
| ewallet-business-customer-mobileapp | 8081 | HTTP (BFF) | nhẹ |
| ewallet-payment-order | 8082 | HTTP + gRPC client + Kafka | nặng (saga) |
| ewallet-payment-business | 8083 / 9091 | HTTP + gRPC server + Kafka | nặng (rule) |
| ewallet-third-party | 8084 | HTTP + WebSocket | vừa |
| ewallet-notification | 8085 | Kafka + SSE | nhẹ |
| partner-sim | 8090 | HTTP + WebSocket | test double |

Service 3–6 (có DB) gắn thêm `database-quality-library` — dashboard db-quality ở host port 19082–19085.

## Chạy

```bash
# chỉ hạ tầng (Postgres + Kafka + otel-collector + Jaeger)
docker compose --profile infra up -d

# toàn bộ
docker compose --profile all up --build
```

Kiểm tra hệ đã lên:

```powershell
.\scripts\demo-flows.ps1 -Flow smoke
```

```bash
./scripts/demo-flows.sh smoke
```

Chạy kịch bản một flow: `-Flow F1 | F1-held | F2 | F3 | F3-limit | F4 | F5 | F6 | all`.
Ba cách chạy local (toàn Docker / IDE + hạ tầng Docker / trộn) và bảng port đầy đủ:
[`docs/local-run.md`](docs/local-run.md).

Host port service ewallet ở dải **18xxx** (gateway 18080, mobileapp 18081, order 18082,
business 18083 + gRPC 19091, third-party 18084, notification 18085, partner-sim 18090).

Jaeger UI: <http://localhost:16686> · Trace file: `infra/otel-collector/traces/traces.jsonl`
Swagger UI: `http://localhost:1808x/swagger-ui.html` (18081–18085, 18090) · OpenAPI JSON: `/v3/api-docs`

## Lỗi có chủ đích

6 lỗi cài sẵn để platform phát hiện — bảng đầy đủ ở [`docs/architecture.md`](docs/architecture.md) mục 6,
ma trận truy vết rule ↔ code ↔ lỗi ở [`docs/specs/README.md`](docs/specs/README.md).
**Không sửa các lỗi này**; chúng là hợp đồng nghiệm thu cuối kỳ.

## Trạng thái

| Stage | Nội dung | Trạng thái |
|---|---|---|
| A | Skeleton (docs, compose, infra, proto) | ✅ |
| B | Scaffold Maven — 7 project boot + trace + db-quality dashboard | ✅ |
| **D** | **Spec F1–F6 + diagram + hướng dẫn chạy local + script demo** | ✅ |
| **C** | **Business logic + 6 lỗi có chủ đích** (code theo `docs/specs/`) | 🔄 đang làm — `payment-business` ✅ (compile OK), 5 service còn lại ⬜ |
| E | Test suite + JaCoCo (cố ý bỏ test đường F1 top-up) | ⬜ |

Kiểm chứng Stage B: [`docs/stage-b-verify.md`](docs/stage-b-verify.md) (cần bật Docker Desktop).
