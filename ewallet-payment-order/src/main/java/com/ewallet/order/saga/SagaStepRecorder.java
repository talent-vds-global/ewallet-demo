package com.ewallet.order.saga;

import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.repo.OrderStepRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi lại từng bước saga vào {@code order_steps}.
 *
 * <p>Dùng {@code REQUIRES_NEW} để dấu vết vẫn còn ngay cả khi giao dịch chính bị rollback —
 * điều tra sự cố cần biết saga đã đi tới đâu rồi mới hỏng.</p>
 */
@Component
public class SagaStepRecorder {

    private final OrderStepRepository orderStepRepository;

    public SagaStepRecorder(OrderStepRepository orderStepRepository) {
        this.orderStepRepository = orderStepRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orderId, String stepName, String stepStatus,
                       String detail, int attempt, Long durationMs) {
        orderStepRepository.save(
                OrderStep.of(orderId, stepName, stepStatus, truncate(detail), attempt, durationMs));
    }

    public void done(UUID orderId, String stepName, String detail, long durationMs) {
        record(orderId, stepName, OrderStep.DONE, detail, 1, durationMs);
    }

    public void failed(UUID orderId, String stepName, String detail, int attempt, long durationMs) {
        record(orderId, stepName, OrderStep.FAILED, detail, attempt, durationMs);
    }

    public void compensated(UUID orderId, String detail, long durationMs) {
        record(orderId, OrderStep.COMPENSATE, OrderStep.COMPENSATED, detail, 1, durationMs);
    }

    /** Cột detail là TEXT nhưng vẫn cắt cho gọn log và tránh nuốt cả stack trace. */
    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > 500 ? detail.substring(0, 500) : detail;
    }
}
