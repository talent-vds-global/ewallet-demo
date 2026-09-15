# Hướng dẫn xem demo — từng bước

> Tài liệu này trả lời hai câu: **chạy thế nào** và **xem được những dashboard nào**.
> Đọc kèm: [`local-run.md`](local-run.md) (chi tiết cách chạy) · [`testing.md`](testing.md) (test suite)
> · [`specs/`](specs/README.md) (spec nghiệp vụ).

Thời gian đi hết tài liệu này: **khoảng 20 phút**.

---

## Bước 0 — Đưa Stage E lên GitHub

Bạn đang mở GitHub Desktop. Repo `ewallet-demo` đang có các thay đổi chưa commit — phần lớn
là công việc Stage E (test suite + JaCoCo):

| Nhóm | Nội dung |
|---|---|
| 7 file `pom.xml` | thêm plugin JaCoCo 0.8.12 + `spring-boot-starter-test` |
| 7 thư mục `src/test/` | 31 file test, 457 test case |
| `docs/testing.md` | tài liệu test suite |
| `scripts/coverage-report.py`, `run-tests.ps1`, `run-tests.sh` | công cụ đo độ phủ |
| `docs/walkthrough.md` | tài liệu này |
| `docker-compose.yml`, `docs/db-quality-integration.md` | sửa `calledFrom` của dashboard db-quality (xem sự cố cuối tài liệu) |
| `README.md`, `docs/demo-status.md` | cập nhật trạng thái |

Gợi ý commit message:

```
feat(test): Stage E - test suite 457 test, JaCoCo 86% do phu

- JaCoCo 0.8.12 tren ca 7 service, bao cao XML + HTML
- 457 unit test (JUnit 5 + Mockito + AssertJ), khong can Docker/DB
- scripts/coverage-report.py gom bao cao nhieu service thanh mot bang
- Loi co chu dich #3 hien thanh so do duoc: TopupAdapter 0% / 11 dong
```

> **Lưu ý:** thư mục `target/` (chứa `jacoco.xml`, `.class`) đã nằm trong `.gitignore` —
> không commit. Báo cáo độ phủ được sinh lại mỗi lần chạy `mvn verify`.

---

## Bước 1 — Khởi động hệ thống

Cần **Docker Desktop đang chạy**. Toàn bộ stack chiếm khoảng **4 GB RAM** (đã cap heap JVM).

```powershell
cd D:\ViettelDigitalTalent\v-quality\ewallet-demo
docker compose --profile all up -d --build
```

Lần đầu build mất vài phút. Chờ mọi container `healthy`:

```powershell
docker compose ps
```

Nếu RAM máy eo hẹp, chạy riêng hạ tầng trước rồi mới bật service:

```powershell
docker compose --profile infra up -d      # Postgres, Kafka, OTel, Jaeger, Adminer, Kafka console
docker compose --profile all up -d
```

**Kiểm tra nhanh mọi service còn sống:**

```powershell
.\scripts\demo-flows.ps1 -Flow smoke
```

---

## Bước 2 — Sinh dữ liệu để có gì mà xem

Lúc mới lên, hệ thống **chưa có giao dịch nào** — mọi dashboard đều trống, và
`infra/otel-collector/traces/traces.jsonl` là file rỗng. Phải chạy nghiệp vụ trước.

**Cách 1 — chạy toàn bộ 6 flow bằng script (khuyến nghị cho lần đầu):**

```powershell
.\scripts\demo-flows.ps1 -Flow all
```

Script chạy lần lượt: smoke → F1 nạp tiền → F1 treo duyệt → F2 hoá đơn → F3 chuyển tiền →
F3 vượt hạn mức → F4 lỗi & hoàn tiền → F5 thông báo → F6 lịch sử.

**Cách 2 — bấm tay trên Demo Console** (trực quan hơn khi trình bày):

Mở <http://localhost:18000>. Giao diện có 5 tab:

| Tab | Làm được gì |
|---|---|
| **Chuyển tiền** | 3 nút mẫu: `150k · miễn phí` · `3tr · phí 2.200đ` · `25tr · treo duyệt` |
| **Nạp tiền** | `500k · thành công` · `…999 · đối tác từ chối` · `…888 · đối tác timeout` |
| **Hoá đơn & Telco** | tra cứu hoá đơn EVN rồi thanh toán; nạp thẻ điện thoại |
| **Lịch sử** | xem lại giao dịch của một khách (đây là flow F6) |
| **Lỗi cài sẵn** | 4 nút chạy thẳng kịch bản lỗi — xem §4 |

Đối tác giả lập hành xử **tất định theo số tiền**, nên kịch bản lặp lại được:
đuôi `999` → bị từ chối · đuôi `888` → treo 5 giây rồi timeout · còn lại → thành công.

---

## Bước 3 — Các dashboard xem được ngay

Tất cả đã chạy sẵn trong `docker compose`, không phải cài thêm gì.

### Nhóm A — Quan sát hệ thống

| Dashboard | Địa chỉ | Xem gì | Vì sao quan trọng |
|---|---|---|---|
| **Jaeger** | <http://localhost:16686> | trace đầy đủ mọi giao dịch | **Đây là dashboard quan trọng nhất.** Chọn service `ewallet-gateway` → Find Traces. Mỗi trace là một giao dịch đi xuyên 5–6 service, thấy rõ HTTP → gRPC → JDBC → Kafka |
| **Kafka console** | <http://localhost:18086> | topic `ewallet.payment.events`, 2 consumer group, topic `.DLT` | Xem event thật, độ trễ consumer, message nào rơi vào dead letter |
| **Adminer** | <http://localhost:18087> | 4 database: `orderdb`, `paymentdb`, `thirdpartydb`, `notifdb` | Đăng nhập: server `postgres`, user `ewallet`, pass `ewallet`. Soi sổ cái, trạng thái đơn |
| **Demo Console** | <http://localhost:18000> | giao diện tạo giao dịch | Vừa là công cụ demo, vừa là nơi bấm ra kịch bản lỗi |

**Cách đọc một trace trong Jaeger** (nên làm thử ít nhất một lần):

1. Service: `ewallet-gateway` → Operation: `POST wallet` → Find Traces.
   (Gateway đặt tên span theo **id route** trong `application.yml` — `wallet`, `mobileapp`,
   `orders-internal` — chứ không theo đường dẫn. Muốn lọc theo đường dẫn thì chọn service
   `ewallet-business-customer-mobileapp`, operation `POST /api/wallet/transfer`.)
2. Bung cây span ra, sẽ thấy đúng đường đi trong spec F3:
   `gateway → mobileapp → payment-order → (gRPC) payment-business → JDBC Postgres + Kafka producer`
3. Bấm vào span JDBC → tab **Tags** → xem `db.statement` là câu SQL thật
4. Bấm vào span gRPC → xem `rpc.service` / `rpc.method`

### Nhóm B — Chất lượng database (kế thừa Topic #80)

Mỗi service có DB đều gắn `database-quality-library`, mỗi cái một dashboard riêng:

| Service | Dashboard | Database |
|---|---|---|
| payment-order | <http://localhost:19082> | `orderdb` |
| payment-business | <http://localhost:19083> | `paymentdb` |
| third-party | <http://localhost:19084> | `thirdpartydb` |
| notification | <http://localhost:19085> | `notifdb` |

Mỗi dashboard có 6 tab, đáng xem nhất là:

- **Collected Queries** — mọi câu SQL đã chạy, đã chuẩn hoá, kèm số lần gọi và p50/p95/p99
- **Findings** — cảnh báo tự động: `N_PLUS_ONE`, `SLOW_QUERY`, `MISSING_INDEX`, `SELECT_STAR`
- **Schema Snapshot** — cấu trúc bảng thật lúc chạy

Cũng có API JSON để xem dữ liệu thô:

```
GET  http://localhost:19082/report
GET  http://localhost:19082/collected-queries
GET  http://localhost:19082/findings
GET  http://localhost:19082/slow-queries
GET  http://localhost:19082/schema-snapshot
POST http://localhost:19082/analyze-now
```

### Nhóm C — Hợp đồng API

Swagger UI trên từng service HTTP: `http://localhost:1808x/swagger-ui.html`

| Service | Swagger |
|---|---|
| mobileapp (BFF) | <http://localhost:18081/swagger-ui.html> |
| payment-order | <http://localhost:18082/swagger-ui.html> |
| payment-business (admin) | <http://localhost:18083/swagger-ui.html> |
| third-party | <http://localhost:18084/swagger-ui.html> |
| notification | <http://localhost:18085/swagger-ui.html> |
| partner-sim | <http://localhost:18090/swagger-ui.html> |

### Nhóm D — Độ phủ test (không cần Docker)

```powershell
.\scripts\run-tests.ps1
```

In ra bảng độ phủ 7 service, và mở được báo cáo HTML từng service tại
`<service>/target/site/jacoco/index.html`.

---

## Bước 4 — Xem sáu lỗi có chủ đích

Đây là phần thuyết phục nhất khi trình bày: **hệ thống chạy đúng, test xanh, mà vẫn có 6 lỗi thật.**

Mở Demo Console tab **"Lỗi cài sẵn"**, có 4 nút chạy thẳng kịch bản:

| Nút | Lỗi | Quan sát ở đâu |
|---|---|---|
| **Chạy 3 lệnh 18 triệu** | **#1** spec drift hạn mức | Cả 3 lệnh đều được duyệt → tổng 54tr. Spec R-LIMIT-01 ghi hạn mức ngày **50tr**, code ghi **100tr**. Đối chiếu: <http://localhost:18083/admin/limits> (DB ghi 50tr) vs `LimitPolicy.java` (code ghi 100tr) |
| **Chuyển 25 triệu (treo duyệt)** | **#2** nhánh HELD quên publish event<br>**#5** vi phạm NFR < 500ms | Kafka console: giao dịch HELD **không sinh event `PaymentHeld`** nào. Jaeger: span nhánh HELD mất **> 1.000ms**, spec NFR-LAT-01 yêu cầu p95 < 500ms |
| **Tạo 15 đơn rồi đo** | **#4** N+1 query | Dashboard db-quality order (<http://localhost:19082>) tab **Findings** → `N_PLUS_ONE` với `calledFrom = OrderHistoryService:67 -> history()`. Jaeger: 1 request `GET /api/orders/history` sinh ~16 span JDBC thay vì 2 |
| **Thanh toán 15.000 USD** | **#6** gRPC bỏ qua `currency` | 15.000 USD (= 375 triệu) được duyệt như **15.000đ**. Xem `amountVnd` trong response và trong Adminer bảng `payment_transactions` |

**Lỗi #3 — khoảng trống test của F1** không xem bằng dashboard mà bằng báo cáo độ phủ:

```powershell
.\scripts\run-tests.ps1
```

Nhìn phần "LOP CHUA CO TEST CHAM TOI": `TopupAdapter` **0% / 11 dòng**, trong khi `BillAdapter`
và `TelcoAdapter` ngay bên cạnh đều **100%**. Kiểm chứng thêm:

```bash
grep -rl "TOP_UP" */src/test/java/     # không ra kết quả nào
```

Nhưng flow F1 **vẫn chạy thật và vẫn sinh trace** — đó chính là điểm mấu chốt:
có runtime, không có test. Chỉ lộ ra khi đối chiếu trace với coverage.

---

## Bước 5 — Dọn dẹp

```powershell
docker compose --profile all down          # dừng, giữ dữ liệu
docker compose --profile all down -v       # dừng và xoá sạch database
```

---

## Sự cố hay gặp

| Triệu chứng | Nguyên nhân | Xử lý |
|---|---|---|
| Cổng 8080 bị chiếm | `AgentService` của máy chiếm sẵn | Gateway đã đổi sang **18080**, dùng cổng này |
| `traces.jsonl` rỗng | chưa chạy giao dịch nào | Chạy `.\scripts\demo-flows.ps1 -Flow all` |
| Service chết khi chạy trên host (không Docker) | JVM báo timezone `Asia/Saigon`, Postgres không hiểu | Thêm `-Duser.timezone=Asia/Ho_Chi_Minh` |
| db-quality trả `HikariDataSource (null) has been closed` | chạy nhiều service trên host, tranh cổng 9876 | Chạy bằng Docker |
| Kafka CLI trong container trả rỗng | Git Bash đổi đường dẫn | Đặt `MSYS_NO_PATHCONV=1` trước lệnh |
| Máy chậm / hết RAM | 7 service + Kafka ~4 GB | Chạy `--profile infra` trước, rồi bật dần |
| `calledFrom` của db-quality toàn trỏ vào `io.opentelemetry.javaagent...` | OTel agent chèn interceptor vào call stack | Đã xử lý: `-Dotel.instrumentation.spring-data.enabled=false` trong compose — xem [`db-quality-integration.md`](db-quality-integration.md) §5b |
| Dashboard db-quality có nhiều `N_PLUS_ONE` | heuristic đếm theo cửa sổ thu, không theo từng request | Chỉ finding ở `OrderHistoryService:67` là lỗi #4 thật; xem §5b |
