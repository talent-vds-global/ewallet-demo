# ewallet-gateway

**Vai trò:** entry point mọi request, routing, propagate trace context. **Không có control flow nghiệp vụ.**
**Giao thức:** HTTP in → HTTP out. **DB:** không.

- Spring Cloud Gateway. Route `/api/**` → `ewallet-business-customer-mobileapp`.
- Chỉ filter kỹ thuật (auth header pass-through, request id). Không rule nghiệp vụ.
- Mục đích trong demo: root span; kiểm chứng Code Indexer **lọc bỏ** service không có method nghiệp vụ
  (call graph gần như rỗng sau bộ lọc).

Endpoint: tất cả proxy, không handler nghiệp vụ.
