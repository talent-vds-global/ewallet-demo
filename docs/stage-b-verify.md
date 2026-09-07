# Stage B — kiểm chứng

Mục tiêu: `docker compose --profile all up --build` boot cả 7 service, sinh trace vào Jaeger,
dashboard `database-quality-library` lên ở 4 service DB. Chưa có business logic.

> Máy dev hiện chưa có Maven và Docker daemon đang tắt → chưa build được ở đây.
> Các bước dưới để chạy khi bật Docker Desktop.

## Chạy

```bash
cd demo-app
docker compose --profile infra up -d          # Postgres + Kafka + otel-collector + Jaeger
docker compose --profile all up --build        # build + chạy 7 service
```

Lần đầu build lâu (tải Maven deps + OTel agent + JitPack build database-quality-library).

## Smoke test

Host port service ewallet dời sang dải **18xxx** (tránh đụng 8080... trên máy dev). Trong mạng compose vẫn là 8080-8090.

| Kiểm tra | Lệnh / URL | Kỳ vọng |
|---|---|---|
| gateway sống | `curl localhost:18080/actuator/health` | `{"status":"UP"}` |
| BFF sống | `curl localhost:18081/api/ping` | JSON service=mobileapp |
| trace 3 tầng | `curl localhost:18080/api/trace-test` | JSON `hop: mobileapp -> order` |
| order + DB | `curl localhost:18082/api/orders/ping` | `orderCount: 0` |
| business + DB | `curl localhost:18083/admin/ping` | `limitConfigRows: 1` |
| business gRPC | port host 19091 mở (grpcurl tùy chọn) | server listening |
| third-party + DB | `curl localhost:18084/api/thirdparty/ping` | `partnerConfigRows: 3` |
| notification + DB | `curl localhost:18085/api/notifications/ping` | `outboxRows: 0` |
| notification SSE | `curl -N localhost:18085/api/notifications/stream` | event `hello` rồi giữ mở |
| partner-sim | `curl -XPOST localhost:18090/partner/VNPAY/execute` | JSON status=SUCCESS |
| Swagger UI | http://localhost:18081/swagger-ui.html (mobileapp) · 18082 · 18083 · 18084 · 18085 · 18090 | trang Swagger, list endpoint |
| OpenAPI JSON | `curl localhost:18082/v3/api-docs` | JSON OpenAPI 3 |
| Jaeger | http://localhost:16686 | thấy service: ewallet-gateway, ...-mobileapp, ...-order, ... |
| trace file | `infra/otel-collector/traces/traces.jsonl` | có dòng JSON span |
| db-quality dashboard | http://localhost:19082 (order), 19083, 19084, 19085 | trang dashboard 6 tab |
| db-quality report | `curl localhost:19082/report` | JSON score + metrics |
| Kafka consumer group | `docker exec ewallet-demo-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list` | có `notification-cg` |

## Rủi ro đã biết (chưa verify được cho tới khi build)

1. **`net.devh:grpc-*-spring-boot-starter:3.1.0.RELEASE` với Spring Boot 3.3.5** — bản net.devh mới nhất
   nhắm Boot 3.2.x. Nếu lỗi khởi động → hạ 2 service gRPC xuống Boot 3.2.x, hoặc đổi sang `spring-grpc`
   (chính chủ, cần Boot 3.4+).
2. **`spring-cloud-starter-gateway` 2023.0.3 với Boot 3.3.5** — cần đúng cặp tương thích. Nếu lỗi →
   dùng Spring Cloud `2023.0.3` chắc chắn, hoặc hạ gateway xuống Boot 3.2.x.
3. **JitPack build `com.github.quanglam04:database-quality-library:master-SNAPSHOT`** — phụ thuộc JitPack
   build thành công lần đầu (có thể mất vài phút). Fallback: `git clone` repo, `mvn clean install -DskipTests`,
   đổi dependency sang `com.dbquality:db-quality-library:1.0-SNAPSHOT`. Hoặc vendor jar vào
   `infra/db-quality/lib/` + `mvn install:install-file`.
4. **protobuf-maven-plugin tải `protoc` / `protoc-gen-grpc-java`** theo OS — cần mạng lúc build.
   Trong Docker (linux/amd64) tải bản linux; `os-maven-plugin` lo classifier.
5. **Flyway vs db-quality**: query `flyway_schema_history` có thể lọt vào metrics của db-quality.
   `quality.analysis.initial-delay=45s` giảm nhiễu. Xử lý triệt để (bean `@Primary` wrap sau migrate) ở Stage C.
6. **`application.properties` + `application.yml` cùng tồn tại** ở 4 service DB — Spring load cả hai,
   key rời nhau (`quality.*` vs `spring.*`) nên không xung đột. Thư viện chỉ đọc `.properties`.

## Không thuộc Stage B (để Stage C)

- Business logic mọi service, 6 lỗi có chủ đích.
- gRPC client call thật order → business (giờ chỉ khai báo).
- WebSocket bền third-party ↔ partner-sim (giờ chỉ khai báo bean).
- Kafka producer ở business, DLT, phân loại retry.
- SSE đẩy notification thật khi có event.
