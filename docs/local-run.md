# Chạy local & demo

> Ba cách chạy, tuỳ mục đích. Cách 1 cho demo, cách 2 cho code Stage C, cách 3 khi chỉ sửa một service.
> Kịch bản nghiệp vụ từng flow: [`specs/`](specs/README.md).

---

## 0. Yêu cầu

| Thứ | Bản | Bắt buộc cho |
|---|---|---|
| Docker Desktop | mới nhất, đang chạy | cả 3 cách (hạ tầng luôn ở Docker) |
| JDK | **17** | cách 2, 3 |
| Maven | 3.9+ (hoặc dùng IDE) | cách 2, 3 |
| `curl` | có sẵn trên Windows 11 (`curl.exe`) | chạy script demo |
| `jq` | tuỳ chọn | đọc JSON cho đẹp |

Kiểm tra nhanh:

```bash
docker version
java -version
```

---

## 1. Cách 1 — Toàn bộ trong Docker (khuyến nghị để demo)

```bash
docker compose --profile all up --build
```

Lần đầu build 7 service Maven nên **mất 5–15 phút**. Các lần sau nhanh hơn nhiều.

Chỉ bật hạ tầng (Postgres + Kafka + otel-collector + Jaeger):

```bash
docker compose --profile infra up -d
```

Dừng và xoá sạch dữ liệu:

```bash
docker compose --profile all down -v
```

### Kiểm tra sau khi lên

```powershell
.\scripts\demo-flows.ps1 -Flow smoke
```

```bash
./scripts/demo-flows.sh smoke
```

Kỳ vọng: 8 lời gọi đều `200`. Nếu `api/trace-test` trả `200`, nghĩa là chuỗi
gateway → mobileapp → order đã thông và trace 3 tầng đã xuất hiện trong Jaeger.

---

## 2. Cách 2 — Hạ tầng ở Docker, 7 service chạy từ IDE / Maven

Dùng khi đang code Stage C: sửa code, restart một service trong vài giây thay vì rebuild image.

**Bước 1** — bật hạ tầng:

```bash
docker compose --profile infra up -d
```

**Bước 2** — chạy từng service với profile `local` (mỗi service một terminal):

```bash
cd ewallet-payment-business && mvn spring-boot:run -Dspring-boot.run.profiles=local
cd ewallet-payment-order    && mvn spring-boot:run -Dspring-boot.run.profiles=local
cd ewallet-third-party      && mvn spring-boot:run -Dspring-boot.run.profiles=local
cd ewallet-notification     && mvn spring-boot:run -Dspring-boot.run.profiles=local
cd partner-sim              && mvn spring-boot:run
cd ewallet-business-customer-mobileapp && mvn spring-boot:run -Dspring-boot.run.profiles=local
cd ewallet-gateway          && mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Thứ tự khởi động nên đi từ dưới lên: `partner-sim` → `third-party` → `business` → `order` → `mobileapp` → `gateway` → `notification`.

> ⚠️ **Máy đặt múi giờ Việt Nam phải thêm `-Duser.timezone=Asia/Ho_Chi_Minh`.**
> Windows báo múi giờ là `Asia/Saigon`; driver PostgreSQL gửi nguyên tên đó sang server, mà
> PostgreSQL 16 chỉ biết `Asia/Ho_Chi_Minh` nên **Flyway chết ngay khi mở kết nối**:
> `FATAL: invalid value for parameter "TimeZone": "Asia/Saigon"`.
> Lỗi này chỉ xảy ra khi chạy service **trên host** — chạy trong Docker thì container dùng UTC nên không dính.
>
> ```bash
> mvn spring-boot:run -Dspring-boot.run.profiles=local \
>   -Dspring-boot.run.jvmArguments="-Duser.timezone=Asia/Ho_Chi_Minh"
> ```
>
> Chạy thẳng jar:
>
> ```bash
> java -Duser.timezone=Asia/Ho_Chi_Minh -jar target/<service>-0.1.0.jar --spring.profiles.active=local
> ```
>
> Trong IntelliJ: thêm `-Duser.timezone=Asia/Ho_Chi_Minh` vào **VM options** của run configuration.

Profile `local` (file `application-local.yml` mỗi service) chỉ đổi đúng ba thứ:
địa chỉ service khác (`localhost` thay vì tên container), địa chỉ Kafka (`localhost:29092`),
và target gRPC (`static://localhost:9091`).

**Bước 3** — chạy demo với port **không có tiền tố 18**:

```powershell
.\scripts\demo-flows.ps1 -Flow smoke `
  -Gateway http://localhost:8080 -Order http://localhost:8082 `
  -Business http://localhost:8083 -ThirdParty http://localhost:8084 `
  -Notification http://localhost:8085 -PartnerSim http://localhost:8090
```

```bash
GATEWAY=http://localhost:8080 ORDER=http://localhost:8082 BUSINESS=http://localhost:8083 \
THIRDPARTY=http://localhost:8084 NOTIFICATION=http://localhost:8085 PARTNERSIM=http://localhost:8090 \
./scripts/demo-flows.sh smoke
```

### Bật OTel agent khi chạy local

Trace chỉ có nếu service chạy kèm agent. Tải agent một lần:

```bash
mkdir -p otel
curl -L -o otel/opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.9.0/opentelemetry-javaagent.jar
```

Rồi chạy service với:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="-javaagent:../otel/opentelemetry-javaagent.jar \
   -Dotel.service.name=ewallet-payment-business \
   -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
   -Dotel.metrics.exporter=none"
```

Không bật agent thì app vẫn chạy bình thường, chỉ là không có trace.

---

## 3. Cách 3 — Trộn: một service ở IDE, phần còn lại ở Docker

Dùng khi chỉ sửa một service (vd `payment-business`) nhưng vẫn muốn cả hệ chạy.

**Bước 1** — bật cả hệ trừ service đang sửa:

```bash
docker compose --profile all up -d --scale ewallet-payment-business=0
```

**Bước 2** — chạy service đó ở host với profile `local`.

**Bước 3** — trỏ các service trong Docker về host. Tạo file `docker-compose.override.yml`:

```yaml
services:
  ewallet-payment-order:
    environment:
      GRPC_BUSINESS_TARGET: static://host.docker.internal:9091
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

Rồi `docker compose --profile all up -d`. Nguyên tắc chung: service **trong** Docker gọi ra host
bằng `host.docker.internal`; service **ở host** gọi vào Docker bằng `localhost:<host port>`.

---

## 4. Bảng port

| Thành phần | Trong Docker | Host (cách 1) | Host (cách 2 — chạy IDE) |
|---|---|---|---|
| ewallet-gateway | 8080 | **18080** | 8080 |
| mobileapp (BFF) | 8081 | **18081** | 8081 |
| payment-order | 8082 | **18082** | 8082 |
| payment-business HTTP | 8083 | **18083** | 8083 |
| payment-business gRPC | 9091 | **19091** | 9091 |
| third-party | 8084 | **18084** | 8084 |
| notification | 8085 | **18085** | 8085 |
| partner-sim | 8090 | **18090** | 8090 |
| PostgreSQL | 5432 | 5432 | 5432 |
| Kafka | 9092 (nội bộ) | **29092** | 29092 |
| otel-collector | 4317 / 4318 | 4317 / 4318 | 4317 / 4318 |
| Jaeger UI | 16686 | **16686** | 16686 |
| db-quality — order | 9876 | **19082** | 9876 |
| db-quality — business | 9876 | **19083** | 9876 |
| db-quality — third-party | 9876 | **19084** | 9876 |
| db-quality — notification | 9876 | **19085** | 9876 |

---

## 5. Chạy demo từng flow

```powershell
.\scripts\demo-flows.ps1 -Flow smoke      # 7 service da boot chua (chay duoc tu Stage B)
.\scripts\demo-flows.ps1 -Flow F1         # nap tien qua doi tac
.\scripts\demo-flows.ps1 -Flow F1-held    # nhanh HELD - loi #2 va #5
.\scripts\demo-flows.ps1 -Flow F2         # hoa don, telco, cross-currency - loi #6
.\scripts\demo-flows.ps1 -Flow F3         # chuyen tien P2P
.\scripts\demo-flows.ps1 -Flow F3-limit   # vuot han muc - loi #1
.\scripts\demo-flows.ps1 -Flow F4         # loi doi tac va hoan tien
.\scripts\demo-flows.ps1 -Flow F5         # thong bao async, retry, DLT
.\scripts\demo-flows.ps1 -Flow F6         # lich su giao dich - loi #4
.\scripts\demo-flows.ps1 -Flow all        # chay het theo thu tu
```

Bản bash: `./scripts/demo-flows.sh <flow>` với cùng danh sách.

> **Trạng thái hiện tại**: chỉ `smoke` chạy được. Các flow F1–F6 cần endpoint nghiệp vụ của **Stage C**.
> Script đã viết sẵn theo đúng hợp đồng API trong [`specs/01-api-contracts.md`](specs/01-api-contracts.md),
> nên khi code xong Stage C là chạy được ngay, không phải sửa script.

### Kịch bản demo 10 phút (sau Stage C)

| Phút | Làm gì | Cho ai xem |
|---|---|---|
| 0–1 | `docker compose --profile all up -d` + `-Flow smoke` | hệ đã lên |
| 1–3 | `-Flow F1` rồi mở Jaeger xem trace 6 service | trace xuyên HTTP + gRPC + Kafka |
| 3–4 | `-Flow F3` — trace chỉ 4 service | impact analysis: nhánh third-party vắng mặt |
| 4–6 | `-Flow F1-held` — so hai trace | lỗi #2 (thiếu event) + #5 (chậm) |
| 6–7 | `-Flow F3-limit` — 3 lần 18tr đều qua | lỗi #1 (spec drift hạn mức) |
| 7–8 | `-Flow F4` — số dư không đổi sau khi đối tác từ chối | saga + compensation |
| 8–10 | `-Flow F6` + dashboard db-quality | lỗi #4 (N+1 + thiếu index) |

---

## 6. Quan sát

| Thứ | Địa chỉ |
|---|---|
| **Jaeger UI** (xem trace) | <http://localhost:16686> |
| **File trace** cho Trace Analyzer | `infra/otel-collector/traces/traces.jsonl` |
| **File log** JSON | `infra/otel-collector/traces/logs.jsonl` |
| **Swagger UI** mỗi service | `http://localhost:1808x/swagger-ui.html` (18081–18085, 18090) |
| **OpenAPI JSON** | `http://localhost:1808x/v3/api-docs` |
| **db-quality dashboard** | order 19082 · business 19083 · third-party 19084 · notification 19085 |
| **Report db-quality khi shutdown** | `infra/db-quality/reports/*.json` |

Xem log một service:

```bash
docker compose logs -f ewallet-payment-business
```

Truy vấn DB trực tiếp:

```bash
docker compose exec postgres psql -U ewallet -d paymentdb -c "SELECT * FROM account_balances;"
docker compose exec postgres psql -U ewallet -d orderdb   -c "SELECT id, payment_type, amount, status FROM payment_orders ORDER BY created_at DESC LIMIT 10;"
```

Xem Kafka:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic ewallet.payment.events --from-beginning --max-messages 10
```

---

## 7. Reset dữ liệu demo

Số dư và `daily_usage` thay đổi sau mỗi lần chạy demo. Cách nhanh nhất để về trạng thái đầu:

```bash
docker compose --profile all down -v      # xoa volume pgdata
docker compose --profile all up -d        # Flyway chay lai V1 + V900 seed
```

Chỉ reset số dư mà không xoá lịch sử:

```bash
docker compose exec postgres psql -U ewallet -d paymentdb -c "
  UPDATE account_balances SET balance = 5000000   WHERE account_id = '00000000-0000-4000-8000-000000000001';
  UPDATE account_balances SET balance = 1000000   WHERE account_id = '00000000-0000-4000-8000-000000000002';
  UPDATE account_balances SET balance = 300000000 WHERE account_id = '00000000-0000-4000-8000-000000000003';
  UPDATE account_balances SET balance = 50000     WHERE account_id = '00000000-0000-4000-8000-000000000004';
  DELETE FROM daily_usage;"
```

---

## 8. Sự cố hay gặp

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `docker compose up` treo ở `kafka` | Kafka KRaft chưa healthy | Chờ ~30s; healthcheck retry 30 lần. Nếu vẫn lỗi: `docker compose down -v` rồi lên lại |
| Service báo `Connection refused` tới `postgres` | Lên trước khi Postgres healthy | Đã có `depends_on: service_healthy`; nếu vẫn lỗi thì restart service đó |
| Cổng 5432 / 16686 bị chiếm | Có Postgres / Jaeger khác trên máy | Đổi port ở `docker-compose.yml` hoặc tắt tiến trình đang chiếm |
| Trace không lên Jaeger | Thiếu `-javaagent` (cách 2) hoặc sai OTLP endpoint | Xem §2 "Bật OTel agent khi chạy local" |
| `FATAL: invalid value for parameter "TimeZone": "Asia/Saigon"` khi service khởi động | JVM trên Windows báo múi giờ `Asia/Saigon`, PostgreSQL 16 không biết tên này | Thêm `-Duser.timezone=Asia/Ho_Chi_Minh` vào VM options (xem §2). Chỉ xảy ra khi chạy trên host, không xảy ra trong Docker |
| Flyway lỗi `checksum mismatch` | Đã sửa file migration sau khi chạy | `docker compose down -v` rồi lên lại (demo không có dữ liệu cần giữ) |
| `application.properties` của db-quality không có tác dụng | Thư viện chỉ đọc `application.properties` trên classpath, **không** đọc `application.yml` | Xem [`db-quality-integration.md`](db-quality-integration.md) |
| Build Maven lỗi tải `database-quality-library` | JitPack build lần đầu chậm/lỗi | Thử lại sau vài phút; hoặc tạm bỏ dependency để build phần còn lại |
| gRPC `UNAVAILABLE` khi chạy cách 2 | `payment-business` chưa lên, hoặc target còn trỏ tên container | Bảo đảm chạy `order` với `-Dspring-boot.run.profiles=local` |

---

## 9. Sau khi sửa hợp đồng

| Sửa gì | Phải làm gì thêm |
|---|---|
| `contracts/proto/payment.proto` | Chạy `contracts/sync-proto.ps1` (hoặc `.sh`) để đồng bộ 2 bản sao, rồi rebuild `order` + `business` |
| Schema DB | Thêm migration mới (`V3__...`), **không** sửa file cũ |
| Route gateway | Route cụ thể phải đặt **trước** route `/api/**` fallback |
| Endpoint mới | Cập nhật [`specs/01-api-contracts.md`](specs/01-api-contracts.md) trước, code sau — spec là đầu vào của Doc Indexer |
