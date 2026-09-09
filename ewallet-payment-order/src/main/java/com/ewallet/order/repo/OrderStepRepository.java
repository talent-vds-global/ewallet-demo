package com.ewallet.order.repo;

import com.ewallet.order.entity.OrderStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStepRepository extends JpaRepository<OrderStep, Long> {

    /**
     * Các bước của MỘT đơn. Cột {@code order_id} cố ý không có index (lỗi #4).
     * Gọi hàm này trong vòng lặp chính là dạng N+1 mà platform phải phát hiện.
     */
    List<OrderStep> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /**
     * Cách ĐÚNG theo R-HIST-06: lấy bước của nhiều đơn bằng một truy vấn.
     * Hiện chỉ dùng ở {@code GET /api/orders/{id}}; đường history cố ý không dùng.
     */
    List<OrderStep> findByOrderIdInOrderByCreatedAtAsc(List<UUID> orderIds);

    boolean existsByOrderIdAndStepNameAndDetail(UUID orderId, String stepName, String detail);
}
