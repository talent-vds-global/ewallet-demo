# ewallet demo-app

Hệ thống "được đem ra quan sát" cho platform AI Quality Control (Người 2).
6 service Spring Boot xoay quanh ví điện tử + partner-sim + hạ tầng OTel.

- Thiết kế đầy đủ: [`docs/architecture.md`](docs/architecture.md)
- Hợp đồng dữ liệu cho collector: [`docs/collector-data-contract.md`](docs/collector-data-contract.md)
- Tích hợp `database-quality-library` (Topic #80): [`docs/db-quality-integration.md`](docs/db-quality-integration.md)
- **Demo hiện làm được gì**: [`docs/demo-status.md`](docs/demo-status.md)
- Spec nghiệp vụ F1–F5: `docs/specs/` (giai đoạn D)

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

# toàn bộ (sau giai đoạn B)
docker compose --profile all up --build
```

Host port service ewallet dời sang **18xxx** (gateway 18080, mobileapp 18081, order 18082, business 18083 + gRPC 19091, third-party 18084, notification 18085, partner-sim 18090). db-quality dashboard: 19082–19085.

Jaeger UI: http://localhost:16686 · Trace file: `infra/otel-collector/traces/traces.jsonl`
Swagger UI: `http://localhost:1808x/swagger-ui.html` (18081–18085, 18090) · OpenAPI JSON: `/v3/api-docs`

## Lỗi có chủ đích

6 lỗi cài sẵn để platform phát hiện — xem bảng ở `docs/architecture.md` mục 6.
Không sửa các lỗi này; chúng là hợp đồng nghiệm thu cuối kỳ.

## Trạng thái

Giai đoạn A (skeleton) ✅ · B (scaffold Maven — 7 project boot + trace + db-quality dashboard) ✅ · C (business logic + 6 lỗi) ⬜ · D (spec + diagram) ⬜ · E (test + coverage) ⬜

Kiểm chứng Stage B: [`docs/stage-b-verify.md`](docs/stage-b-verify.md) (cần bật Docker Desktop).
