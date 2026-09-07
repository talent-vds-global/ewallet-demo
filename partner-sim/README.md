# partner-sim

**Không phải service demo — là test double.** Giả lập đối tác ngoài để hệ tự chạy độc lập.
**Giao thức:** HTTP + WebSocket. **DB:** không (in-memory).

- `POST /partner/{code}/execute` — trả kết quả nạp tiền / bill; cấu hình `latency-ms`, `fail-rate`, `timeout-rate`.
- `GET /ws/partner` — WebSocket; định kỳ đẩy `settlement` status cho các giao dịch đang chờ.

Có gắn OTel agent để trace nối liền qua ranh giới "hệ ngoài".
