package com.ewallet.order.repo;

import com.ewallet.order.entity.PaymentOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    /**
     * Lịch sử giao dịch của một khách, mới nhất trước (R-HIST-01, R-HIST-02).
     *
     * <p>Cột {@code customer_id} cố ý KHÔNG có index — xem V2__business.sql và
     * docs/specs/F6-transaction-history.md (lỗi có chủ đích #4).</p>
     */
    @Query("SELECT o FROM PaymentOrder o WHERE o.customerId = :customerId ORDER BY o.createdAt DESC")
    List<PaymentOrder> findHistory(@Param("customerId") String customerId, Pageable pageable);
}
