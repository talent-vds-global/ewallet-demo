package com.ewallet.payment.repo;

import com.ewallet.payment.entity.DailyUsage;
import com.ewallet.payment.entity.DailyUsageId;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyUsageRepository extends JpaRepository<DailyUsage, DailyUsageId> {

    @Query(value = "SELECT COALESCE(total_amount, 0) FROM daily_usage "
            + "WHERE customer_id = :customerId AND usage_date = :usageDate", nativeQuery = true)
    Long findTotalAmount(@Param("customerId") String customerId, @Param("usageDate") LocalDate usageDate);

    /**
     * R-USAGE-01 / R-USAGE-02: cộng (hoặc trừ, khi delta âm) giá trị giao dịch vào hạn mức ngày.
     * Dùng UPSERT một câu để không có race giữa hai giao dịch cùng khách.
     */
    @Modifying
    @Query(value = "INSERT INTO daily_usage (customer_id, usage_date, total_amount) "
            + "VALUES (:customerId, :usageDate, :delta) "
            + "ON CONFLICT (customer_id, usage_date) "
            + "DO UPDATE SET total_amount = daily_usage.total_amount + :delta", nativeQuery = true)
    void addUsage(@Param("customerId") String customerId,
                  @Param("usageDate") LocalDate usageDate,
                  @Param("delta") long delta);
}
