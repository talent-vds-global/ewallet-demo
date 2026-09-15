package com.ewallet.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.repo.OrderStepRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Ghi dấu vết từng bước saga vào {@code order_steps}. */
@ExtendWith(MockitoExtension.class)
class SagaStepRecorderTest {

    @Mock
    private OrderStepRepository orderStepRepository;

    @InjectMocks
    private SagaStepRecorder recorder;

    private final UUID orderId = UUID.randomUUID();

    private OrderStep captured() {
        ArgumentCaptor<OrderStep> captor = ArgumentCaptor.forClass(OrderStep.class);
        Mockito.verify(orderStepRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("done() ghi bước ở trạng thái DONE, lần thử thứ nhất")
    void ghiBuocThanhCong() {
        recorder.done(orderId, OrderStep.AUTHORIZE, "OK", 42L);

        OrderStep step = captured();
        assertThat(step.getOrderId()).isEqualTo(orderId);
        assertThat(step.getStepName()).isEqualTo(OrderStep.AUTHORIZE);
        assertThat(step.getStepStatus()).isEqualTo(OrderStep.DONE);
        assertThat(step.getDetail()).isEqualTo("OK");
        assertThat(step.getAttempt()).isEqualTo(1);
        assertThat(step.getDurationMs()).isEqualTo(42L);
    }

    @Test
    @DisplayName("failed() ghi bước hỏng kèm số lần đã thử")
    void ghiBuocHong() {
        recorder.failed(orderId, OrderStep.CONFIRM, "deadline exceeded", 3, 5_000L);

        OrderStep step = captured();
        assertThat(step.getStepStatus()).isEqualTo(OrderStep.FAILED);
        assertThat(step.getAttempt()).isEqualTo(3);
        assertThat(step.getDetail()).isEqualTo("deadline exceeded");
    }

    @Test
    @DisplayName("compensated() ghi bước COMPENSATE với trạng thái COMPENSATED")
    void ghiBuocBuTru() {
        recorder.compensated(orderId, "refund-txn-1", 120L);

        OrderStep step = captured();
        assertThat(step.getStepName()).isEqualTo(OrderStep.COMPENSATE);
        assertThat(step.getStepStatus()).isEqualTo(OrderStep.COMPENSATED);
        assertThat(step.getDetail()).isEqualTo("refund-txn-1");
    }

    @Test
    @DisplayName("chi tiết quá dài bị cắt còn 500 ký tự để không nuốt cả stack trace")
    void catChiTietQuaDai() {
        String qua_dai = "x".repeat(2_000);

        recorder.failed(orderId, OrderStep.CONFIRM, qua_dai, 1, 10L);

        assertThat(captured().getDetail()).hasSize(500);
    }

    @Test
    @DisplayName("chi tiết đúng 500 ký tự thì giữ nguyên")
    void giuNguyenChiTietVuaDu() {
        recorder.failed(orderId, OrderStep.CONFIRM, "y".repeat(500), 1, 10L);

        assertThat(captured().getDetail()).hasSize(500);
    }

    @Test
    @DisplayName("chi tiết null thì ghi null chứ không ném lỗi")
    void chiTietNull() {
        recorder.record(orderId, OrderStep.CREATE_ORDER, OrderStep.PENDING, null, 1, null);

        OrderStep step = captured();
        assertThat(step.getDetail()).isNull();
        assertThat(step.getDurationMs()).isNull();
    }
}
