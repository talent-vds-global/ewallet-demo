# Stage E — Test suite & độ phủ

> 457 test, 7 service, độ phủ dòng **86%**. Toàn bộ xanh.
> Mục đích của Stage E không chỉ là "có test", mà là **sinh ra dữ liệu coverage máy đọc được**
> để nền tảng đối chiếu với runtime trace và phát hiện *test gap*.

---

## 1. Chạy

```powershell
.\scripts\run-tests.ps1                  # chạy hết rồi in bảng độ phủ
.\scripts\run-tests.ps1 -Service order   # chỉ một service
.\scripts\run-tests.ps1 -SkipBuild       # in lại bảng từ báo cáo đã có
```

```bash
./scripts/run-tests.sh                   # chạy hết
./scripts/run-tests.sh order             # chỉ service có "order" trong tên
SKIP_BUILD=1 ./scripts/run-tests.sh      # in lại bảng
```

Một service lẻ:

```bash
cd ewallet-payment-business && mvn verify
```

Không cần Docker, không cần Postgres, không cần Kafka — toàn bộ là unit test.

**Đầu ra mỗi service:**

| Đường dẫn | Dùng để |
| --- | --- |
| `target/site/jacoco/jacoco.xml` | **đầu vào cho collector** — máy đọc được |
| `target/site/jacoco/index.html` | người xem |
| `target/surefire-reports/` | kết quả từng test |

---

## 2. Bức tranh hiện tại

| Service | Dòng | Nhánh | Test | Trọng tâm |
| --- | ---: | ---: | ---: | --- |
| ewallet-payment-business | 91% | 84% | 166 | rule nghiệp vụ, sổ cái kép, ánh xạ gRPC |
| ewallet-payment-order | 95% | 88% | 83 | saga 4 bước + nhánh bù trừ |
| ewallet-third-party | 67% | 62% | 57 | adapter đối tác, phân loại DECLINED/TIMEOUT |
| ewallet-notification | 88% | 83% | 55 | định tuyến thông báo, retry, DLT |
| partner-sim | 88% | 83% | 51 | hành vi tất định, WebSocket quyết toán |
| ewallet-business-customer-mobileapp | 65% | 86% | 37 | kiểm tra đầu vào ở BFF |
| ewallet-gateway | — | — | 8 | thứ tự route trong `application.yml` |
| **Toàn bộ** | **86%** | | **457** | |

Gateway chỉ có lớp `main()` nên độ phủ dòng là 0% — hành vi thật của nó nằm ở cấu hình,
và `GatewayRoutesTest` kiểm tra thẳng file `application.yml` (xem §5).

### Cách viết

Thuần JUnit 5 + Mockito + AssertJ. Không `@SpringBootTest`, không Testcontainers:

- **Service / domain**: mock repository và client, kiểm tra thứ tự áp rule và tác dụng phụ.
- **Controller**: `MockMvcBuilders.standaloneSetup(...)` — chỉ tầng web, không nâng context.
- **Client HTTP**: `MockRestServiceServer` bind vào `RestClient.Builder`.
- **Listener Kafka**: gọi thẳng phương thức listener với `ConsumerRecord` tự dựng.

Đổi lại: nhanh (cả bộ dưới 1 phút), chạy được ở máy không có Docker, nhưng **không** kiểm tra
migration Flyway, câu SQL thật hay việc nối dây Spring. Những thứ đó kiểm chứng bằng
kịch bản chạy thật ở [`local-run.md`](local-run.md) và `scripts/demo-flows.ps1`.

---

## 3. Lỗi có chủ đích #3 — khoảng trống test của F1

> **Không viết test cho đường nạp tiền.** Đây là hợp đồng nghiệm thu
> (architecture.md §6), giống năm lỗi kia.

Trong khi mọi flow khác đều có test, flow **F1 (nạp tiền qua đối tác)** cố ý không có test nào.
Lớp neo là `TopupAdapter` ở `ewallet-third-party` — JaCoCo báo **0% trên 11 dòng**, trong khi
`BillAdapter` và `TelcoAdapter` ngay bên cạnh đều được phủ.

Ngoài ra, các file test đều tránh nhắc tới `TOP_UP`, nên có thể kiểm chứng bằng một câu lệnh:

```bash
grep -rl "TOP_UP" */src/test/java/     # phải không ra kết quả nào
```

Cụ thể những chỗ cố ý bỏ trống:

| Nơi | Nhánh không được test |
| --- | --- |
| `ewallet-third-party` | `TopupAdapter` — toàn bộ lớp |
| `ewallet-payment-business` | nhánh `case TOP_UP` trong `PaymentService.authorize()` |
| `ewallet-payment-business` | `FeePolicy` / `LimitPolicy` với `PaymentType.TOP_UP` |
| `ewallet-business-customer-mobileapp` | `POST /api/wallet/topup` |

**Vì sao đây là lỗi thú vị:** F1 vẫn chạy thật và vẫn sinh trace đầy đủ. Nền tảng phải
giao được hai tập dữ liệu rồi trừ nhau:

```
đường thực thi quan sát được qua trace   (Trace Analyzer)
  −  đường được test chạm tới            (jacoco.xml, Code Indexer)
  =  test gap
```

Chỉ nhìn coverage thì 86% trông rất ổn; chỉ nhìn trace thì F1 trông cũng rất ổn.
Phải **đối chiếu hai nguồn** mới lộ ra.

### Bốn lớp 0% khác không phải lỗi

`scripts/coverage-report.py --gaps` còn liệt kê vài lớp 0% nữa, đó là hiện tượng bình thường
chứ không phải khoảng trống thật — collector nên lọc chúng ra:

| Lớp | Vì sao bỏ qua |
| --- | --- |
| `DailyUsage`, `DailyUsageId` | entity JPA thuần, chỉ có getter/setter |
| `PaymentBusinessClient` | lớp bọc stub gRPC, logic duy nhất là gắn deadline |
| `*Application` | lớp `main()` của Spring Boot |

---

## 4. Vì sao năm lỗi còn lại vẫn "xanh"

Đây là phần đáng chú ý nhất của Stage E đối với nền tảng: **bộ test xanh hoàn toàn, mà năm lỗi
có chủ đích vẫn nằm nguyên trong code.** Không phải vì thiếu test — các dòng đó đều *được phủ*.

| Lỗi | Dòng có được phủ? | Vì sao test không bắt được |
| --- | --- | --- |
| #1 hạn mức ngày 100tr (spec ghi 50tr) | có, 96% | Test khẳng định **hành vi của code**, không khẳng định spec. `LimitPolicyTest` kiểm tra "dưới hạn mức thì cho qua" với chính hằng số trong code, nên 60tr được duyệt và test vẫn xanh. Chỉ phát hiện được khi đối chiếu hằng số ↔ `limit_config` ↔ rule trong Confluence. |
| #2 nhánh HELD quên publish `PaymentHeld` | có, 84% nhánh | `PaymentServiceTest.AuthorizeHeld` phủ đủ nhánh HELD nhưng chỉ khẳng định trạng thái trả về là HELD. Không có test nào khẳng định "phải có event". Đây là **assertion gap**, không phải coverage gap — chỉ phát hiện được khi so trace của giao dịch HELD (không có span producer) với R-EVENT-01. |
| #4 N+1 query ở lịch sử | có, 100% | `OrderHistoryServiceTest` khẳng định *kết quả trả về đúng* — và nó đúng thật. Số câu SQL không phải thứ unit test với repository giả lập nhìn thấy được. Chỉ phát hiện được qua `database-quality-library` hoặc số span db trong trace. |
| #5 nhánh HELD ngủ 700ms (NFR < 500ms) | có | Không có test nào khẳng định độ trễ, và cũng không nên có — thời gian chạy trong unit test không đại diện cho production. Chỉ phát hiện được qua `SpanStat.p95` của trace thật. |
| #6 gRPC bỏ qua `currency` | có, 100% dòng ánh xạ | `PaymentBusinessGrpcServiceTest` dựng request toàn bằng VND — đúng như một người viết test cho ca phổ biến. Ca ngoại tệ chưa bao giờ được nghĩ tới nên không ai khẳng định `currency` có được truyền xuống hay không. |

Đó chính là luận điểm của đề tài, đo được bằng số:

> **Coverage 86%, 457 test xanh, và vẫn còn 6 lỗi thật.**
> "Những gì test kiểm tra" và "hành vi runtime thực tế" là hai tập khác nhau —
> không công cụ đơn lẻ nào nhìn thấy cả hai.

---

## 5. Vài test đáng chú ý

**`GatewayRoutesTest`** — gateway không có mã nghiệp vụ, nhưng thứ tự route trong
`application.yml` là thứ dễ hỏng: Spring Cloud Gateway khớp theo thứ tự khai báo, nên nếu
`/api/**` bị đẩy lên trước `/api/notifications/**` thì mọi request đều rơi về mobileapp
**mà không báo lỗi gì**. Test đọc thẳng file YAML để giữ ràng buộc đó.

**Phân loại DECLINED vs TIMEOUT** (`ThirdPartyClientTest`, `PartnerSimClientTest`) — hai
trạng thái này dẫn tới hai quyết định khác nhau ở saga, và timeout thường nằm sau vài lớp bọc
exception nên rất dễ bị báo nhầm thành "từ chối". Có test riêng cho ca timeout bọc nhiều lớp.

**`LedgerServiceTest.soCaiCan`** — khẳng định tổng có dấu của mọi bút toán bằng 0.
Đây là bất biến của sổ kép; hỏng cái này là tiền sai.

**`PaymentEventPublisherTest.kafkaChetKhongLamHongGiaoDich`** — Kafka chết không được
làm hỏng giao dịch: tiền đã ghi đúng, chỉ thông báo bị thiếu.

---

## 6. Dữ liệu Stage E giao cho nền tảng

| Dữ liệu | Nguồn | Ai dùng |
| --- | --- | --- |
| Độ phủ theo lớp / dòng / nhánh | `*/target/site/jacoco/jacoco.xml` | Code Indexer → Knowledge Graph |
| Bảng tổng hợp nhiều service | `python scripts/coverage-report.py --json out.json` | thử nhanh, dựng baseline |
| Danh sách lớp 0% | `scripts/coverage-report.py --gaps` | phát hiện test gap sơ bộ |
| Kết quả từng test | `*/target/surefire-reports/*.xml` | gắn test ↔ rule qua `@DisplayName` |

`@DisplayName` của mọi test đều ghi mã rule (`R-LIMIT-01`, `R-COMP-05`, ...) đúng như trong
[`specs/`](specs/README.md). Nhờ đó Doc Indexer nối được **rule → test → dòng code** mà không
cần đoán, và trả lời được câu hỏi kiểu *"rule R-023 được test ở đâu, có chạy thật không?"*.

---

## 7. Chưa làm

- **Integration test có DB thật** (Testcontainers): migration Flyway, câu SQL thật, index.
  Cần Docker nên để riêng, không nằm trong `mvn verify` mặc định.
- **Test hợp đồng gRPC** giữa order và business — hiện mỗi bên tự mock bên kia, proto lệch
  nhau vẫn qua được test.
- **Kiểm chứng N+1 tự động** — hiện làm tay bằng `pg_stat_user_tables.seq_scan`
  (xem `local-run.md`); sẽ do `db-quality-collector` đảm nhận.
- **Ngưỡng coverage tối thiểu** (`jacoco:check`) — cố ý chưa bật, vì bật lên thì lỗi #3
  sẽ làm build đỏ, mà #3 là hợp đồng nghiệm thu chứ không phải lỗi cần sửa.
