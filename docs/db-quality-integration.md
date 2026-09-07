# Tích hợp `database-quality-library` (kế thừa Topic #80)

Repo: <https://github.com/quanglam04/database-quality-library>
Vai trò: thu **database context** ở runtime qua JDBC interception — không sửa business logic.

---

## 1. Gắn vào service nào

| Service | Có DB? | Gắn thư viện |
|---|---|---|
| ewallet-gateway | không | ✗ |
| ewallet-business-customer-mobileapp | không | ✗ |
| ewallet-payment-order | `orderdb` | ✓ |
| ewallet-payment-business | `paymentdb` | ✓ |
| ewallet-third-party | `thirdpartydb` | ✓ |
| ewallet-notification | `notifdb` | ✓ |
| partner-sim | không | ✗ |

## 2. Maven — thêm vào `pom.xml` của 4 service DB

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.github.quanglam04</groupId>
    <artifactId>database-quality-library</artifactId>
    <version>master-SNAPSHOT</version>
  </dependency>
</dependencies>
```

Fallback nếu JitPack chưa build được: `git clone` repo, `mvn clean install -DskipTests`,
rồi dùng `com.dbquality:db-quality-library:1.0-SNAPSHOT`.
(Ta có thể vendor sẵn 1 bản `.jar` vào `demo-app/infra/db-quality/lib/` để CI khỏi phụ thuộc mạng.)

## 3. Cấu hình — **bắt buộc file `application.properties`**

Thư viện đọc `QualityConfig.fromClasspath()` → chỉ đọc **`src/main/resources/application.properties`**,
**không** đọc `application.yml`, **không** đọc Spring `Environment`.
→ Mỗi service DB phải có `application.properties` (dùng song song với `application.yml` của Spring).

`ewallet-payment-order/src/main/resources/application.properties`:
```properties
quality.enabled=true
quality.slow-query-threshold-ms=300
quality.n-plus-one-threshold=5
quality.analysis.scheduled=true
quality.analysis.interval=2m
quality.analysis.initial-delay=45s
quality.dashboard.enabled=true
quality.dashboard.port=9876
quality.export.json.enabled=true
quality.export.json.path=/reports/order-quality-report.json
quality.ai.enabled=false
```

Bốn service dùng chung nội dung, chỉ khác `quality.export.json.path`:
| Service | `export.json.path` |
|---|---|
| order | `/reports/order-quality-report.json` |
| business | `/reports/business-quality-report.json` |
| third-party | `/reports/third-party-quality-report.json` |
| notification | `/reports/notification-quality-report.json` |

`quality.dashboard.port` để nguyên `9876` trong container — docker map ra host khác nhau (19082–19085).

## 4. docker-compose — thêm cho 4 service DB

```yaml
  ewallet-payment-order:
    # ... phần cũ giữ nguyên ...
    ports:
      - "8082:8082"
      - "19082:9876"          # db-quality dashboard
    volumes:
      - ./infra/db-quality/reports:/reports
```

Tương tự: business `19083:9876`, third-party `19084:9876`, notification `19085:9876`,
tất cả mount `./infra/db-quality/reports:/reports`.

## 5. Lưu ý Flyway

Thư viện khuyến nghị chạy migration trên `DataSource` **gốc** rồi mới wrap, để query hệ thống của
Flyway (`SELECT ... FROM flyway_schema_history`) không lọt vào metrics.
Với Spring Boot auto-config (`@AutoConfiguration(after = DataSourceAutoConfiguration.class)` + `@Primary`),
thứ tự không đảm bảo. Xử lý ở stage C:
- Ưu tiên: đặt `quality.analysis.initial-delay=45s` để phần lớn query Flyway xảy ra trước cửa sổ thu.
- Hoặc: cấu hình thủ công (README mục "Spring Boot thủ công") — tạo bean `@Primary DataSource`
  wrap sau khi gọi `flyway.migrate()` trên nguồn gốc.
- Chấp nhận được: vài query `flyway_schema_history` trong `/collected-queries` — collector lọc theo prefix bảng.

## 6. Endpoint thư viện phát ra (mỗi service, trên port 9876)

| Endpoint | Nội dung | Dùng cho collector |
|---|---|---|
| `GET /report` | Báo cáo đầy đủ: score, ddlFindings, sqlFindings, slowQueries, metrics | Nguồn chính |
| `GET /collected-queries` | SQL pattern + callCount + avg/min/max + calledFrom | Map method ↔ bảng, phát hiện N+1 |
| `GET /schema-snapshot` | Tables / columns / indexes / FK từ `DatabaseMetaData` | Node `Table` + cột, đối chiếu thay đổi schema |
| `GET /slow-queries` | Slow query + EXPLAIN plan | Finding hiệu năng |
| `GET /findings` | Findings từ lần analysis gần nhất | Finding tổng hợp |
| `GET /project-info` | DB product/version, ORM, pool, JVM | Metadata service |
| `POST /analyze-now` | Ép chạy analysis ngay | Collector gọi trước khi scrape trong demo |

Ngoài ra: file `/reports/<service>-quality-report.json` ghi khi service shutdown (bản chụp cuối).

## 7. Collector `db-quality-collector`

Nằm ở `D:\ViettelDigitalTalent\v-quality\collectors\db-quality-collector\` (Python).
Vòng lặp (mặc định mỗi 2 phút):
1. Với mỗi service DB: `POST /analyze-now` → `GET /report` + `GET /collected-queries` + `GET /schema-snapshot`.
2. Chuẩn hoá về node/edge (xem `collector-data-contract.md` mục 4):
   - `Table` (+ cột, index, FK) từ schema-snapshot.
   - `SqlPattern` (normalized SQL, callCount, p50/p95/p99, calledFrom) từ collected-queries.
   - `DbFinding` (rule, severity, message, recommendation, calledFrom) từ report.
   - Edge `RUNTIME_READS` / `RUNTIME_WRITES`: `SqlPattern` → `Table` (parse tên bảng trong SQL).
   - Edge `OBSERVED_AT`: `SqlPattern` → `Method` (map `calledFrom` `Class:line -> method` sang node `Method` của Code Indexer).
3. Đẩy vào Knowledge Graph, `source = { collector: "db-quality", service, scraped_at }`.

`calledFrom` (vd `OrderHistoryService:85 -> toResponse()`) là cầu nối chính giữa **runtime DB behavior**
và **code entity** — Code Indexer cung cấp node `Method`, collector này gắn số liệu runtime vào.

## 8. Kịch bản demo lỗi #4 (N+1)

`GET /api/orders/history` ở order lặp `SELECT * FROM order_steps WHERE order_id = ?` theo từng order.
Sau khi chạy workload:
- `/collected-queries`: pattern đó có `callCount` cao, `calledFrom = OrderHistoryService:<line>`.
- `/report` → `sqlFindings`: rule `N_PLUS_ONE` severity HIGH + (nếu cột `order_id` chưa index) `MISSING_INDEX`.
- Platform đối chiếu: baseline trace nói endpoint này chậm ⇄ db-quality nói nguyên nhân là N+1 tại dòng code cụ thể.
