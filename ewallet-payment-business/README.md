# ewallet-payment-business

**Vai trò:** xử lý nghiệp vụ thanh toán cốt lõi — limit, threshold, fee, balance. **Control flow: nặng.**
**Giao thức:** **gRPC server** (`PaymentBusinessService.AuthorizePayment`, port 9091) · HTTP admin (8083) · **Kafka producer** `ewallet.payment.events`.
**DB:** `paymentdb` (`accounts`, `account_balances`, `ledger_entries`, `daily_usage`, `limit_config`).

Luồng `AuthorizePayment`:
1. Load account + `daily_usage`.
2. `LimitPolicy.check()` — **LỖI #1**: hằng số `DAILY_TRANSFER_LIMIT = 100_000_000`, spec ghi 50.000.000đ.
   **LỖI #6**: bỏ qua `request.currency`, áp hạn mức VND cho mọi tiền tệ.
3. Nếu `amount > REVIEW_THRESHOLD` → nhánh **HELD**:
   - `Thread.sleep(700)` — **LỖI #5** (spec: thanh toán < 500ms).
   - set status `HELD`, `return` — **LỖI #2**: **không** produce `PaymentHeld` dù spec bắt buộc.
4. Ngược lại: check balance → ghi `ledger_entries` → produce `PaymentCompleted`.
5. Nhánh `TOP_UP` (F1): gọi `ewallet-third-party` qua HTTP trước khi ghi sổ. **LỖI #3**: đường này **không có test**.

HTTP admin: `GET /admin/limits`, `POST /admin/limits` (chỉnh `limit_config`) — không nằm trong flow khách hàng.

**database-quality-library** (kế thừa Topic #80): dashboard port 9876 (host 19083). Thu SQL pattern + `calledFrom`
cho các bảng `paymentdb`. Xem `docs/db-quality-integration.md`.
