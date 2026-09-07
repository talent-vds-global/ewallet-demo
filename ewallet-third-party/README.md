# ewallet-third-party

**Vai trò:** tích hợp đối tác ngoài (nạp tiền, bill payment, telco). **Control flow: vừa** (rẽ nhánh theo `partner_code` / loại dịch vụ).
**Giao thức:** HTTP in · HTTP out → partner-sim · **WebSocket** ↔ partner-sim (kết nối dài nhận trạng thái async).
**DB:** `thirdpartydb` (`partner_config`, `partner_transactions`).

Endpoint:
- `POST /api/thirdparty/execute` — nhận lệnh từ business, chọn adapter theo `partner_code`, gọi partner-sim.
- Giữ 1 WebSocket bền tới partner-sim; partner đẩy `settlement` status về → cập nhật `partner_transactions`.

Mục đích demo: hop ra hệ ngoài (latency biến động, retry/timeout) + chứng minh collector thu được span **kết nối dài** (WebSocket).
Chỉ thuộc F1, F2.

**database-quality-library** (kế thừa Topic #80): dashboard port 9876 (host 19084). Thu SQL pattern cho `thirdpartydb`.
Xem `docs/db-quality-integration.md`.
