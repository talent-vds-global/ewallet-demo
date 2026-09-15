package com.ewallet.order.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.entity.PaymentOrder;
import com.ewallet.order.repo.OrderStepRepository;
import com.ewallet.order.repo.PaymentOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Consumer group {@code order-status-cg} — chốt trạng thái đơn bất đồng bộ (F5).
 *
 * <p>Kiểm tra R-ORDST-01 (ánh xạ eventType sang trạng thái đơn), R-ORDST-02 (không ghi đè
 * trạng thái kết thúc) và R-ORDST-03 (cùng eventId chỉ ghi một lần).</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderStatusListenerTest {

    private static final String TOPIC = "ewallet.payment.events";

    @Mock private PaymentOrderRepository orderRepository;
    @Mock private OrderStepRepository stepRepository;

    private OrderStatusListener listener;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        listener = new OrderStatusListener(orderRepository, stepRepository, new ObjectMapper());
        orderId = UUID.randomUUID();
        lenient().when(stepRepository.existsByOrderIdAndStepNameAndDetail(any(), anyString(), anyString()))
                .thenReturn(false);
    }

    private ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, orderId.toString(), json);
    }

    private String event(String eventId, String eventType, String reasonCode) {
        return """
                {"eventId":"%s","eventType":"%s","orderId":"%s",
                 "txnId":"%s","status":"CAPTURED","reasonCode":"%s"}
                """.formatted(eventId, eventType, orderId, UUID.randomUUID(), reasonCode);
    }

    private PaymentOrder order(String status) {
        PaymentOrder o = new PaymentOrder();
        o.setId(orderId);
        o.setCustomerId("CUST-001");
        o.setStatus(status);
        return o;
    }

    @ParameterizedTest(name = "{0} -> đơn chuyển {1}")
    @CsvSource({
            "PaymentCompleted, COMPLETED",
            "PaymentFailed,    FAILED",
            "PaymentHeld,      HELD",
            "PaymentRefunded,  REFUNDED"
    })
    @DisplayName("R-ORDST-01: mỗi loại event ánh xạ sang đúng trạng thái đơn")
    void anhXaTrangThai(String eventType, String expectedStatus) {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.CONFIRMING)));

        listener.onPaymentEvent(record(event("evt-1", eventType, "OK")));

        ArgumentCaptor<PaymentOrder> captor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(expectedStatus);
        assertThat(captor.getValue().getReasonCode()).isEqualTo("OK");
    }

    @Test
    @DisplayName("mỗi event xử lý xong đều để lại dấu vết EVENT_APPLIED mang eventId")
    void ghiDauVetEventApplied() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.CONFIRMING)));

        listener.onPaymentEvent(record(event("evt-77", "PaymentCompleted", "OK")));

        ArgumentCaptor<OrderStep> captor = ArgumentCaptor.forClass(OrderStep.class);
        verify(stepRepository).save(captor.capture());
        OrderStep step = captor.getValue();
        assertThat(step.getStepName()).isEqualTo(OrderStep.EVENT_APPLIED);
        assertThat(step.getStepStatus()).isEqualTo(OrderStep.DONE);
        assertThat(step.getDetail()).isEqualTo("evt-77");
        assertThat(step.getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("header x-attempt của producer được ghi lại để phân biệt lần retry")
    void ghiLaiSoLanThu() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.CONFIRMING)));
        ConsumerRecord<String, String> rec = record(event("evt-9", "PaymentCompleted", "OK"));
        rec.headers().add(new RecordHeader("x-attempt", "3".getBytes(StandardCharsets.UTF_8)));

        listener.onPaymentEvent(rec);

        ArgumentCaptor<OrderStep> captor = ArgumentCaptor.forClass(OrderStep.class);
        verify(stepRepository).save(captor.capture());
        assertThat(captor.getValue().getAttempt()).isEqualTo(3);
    }

    @Test
    @DisplayName("header x-attempt không phải số thì coi như lần đầu")
    void headerHongThiCoiLaLanDau() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.CONFIRMING)));
        ConsumerRecord<String, String> rec = record(event("evt-9", "PaymentCompleted", "OK"));
        rec.headers().add(new RecordHeader("x-attempt", "ba".getBytes(StandardCharsets.UTF_8)));

        listener.onPaymentEvent(rec);

        ArgumentCaptor<OrderStep> captor = ArgumentCaptor.forClass(OrderStep.class);
        verify(stepRepository).save(captor.capture());
        assertThat(captor.getValue().getAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R-ORDST-03: event trùng eventId bị bỏ qua hoàn toàn")
    void eventTrungThiBoQua() {
        when(stepRepository.existsByOrderIdAndStepNameAndDetail(orderId, OrderStep.EVENT_APPLIED, "evt-1"))
                .thenReturn(true);

        listener.onPaymentEvent(record(event("evt-1", "PaymentCompleted", "OK")));

        verify(orderRepository, never()).findById(any());
        verify(orderRepository, never()).save(any());
        verify(stepRepository, never()).save(any());
    }

    @Test
    @DisplayName("R-ORDST-02: đơn đã ở trạng thái kết thúc khác thì không bị ghi đè")
    void khongGhiDeTrangThaiKetThuc() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.REFUNDED)));

        listener.onPaymentEvent(record(event("evt-2", "PaymentCompleted", "OK")));

        verify(orderRepository, never()).save(any());
        // vẫn ghi dấu vết để biết event đã tới
        verify(stepRepository).save(any());
    }

    @Test
    @DisplayName("event lặp lại đúng trạng thái đang có thì không ghi DB lần nữa")
    void trungTrangThaiThiKhongGhiLai() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.COMPLETED)));

        listener.onPaymentEvent(record(event("evt-3", "PaymentCompleted", "OK")));

        verify(orderRepository, never()).save(any());
        verify(stepRepository).save(any());
    }

    @Test
    @DisplayName("JSON hỏng thì bỏ qua message chứ không để consumer chết")
    void jsonHongThiBoQua() {
        listener.onPaymentEvent(record("{khong-phai-json"));

        verify(orderRepository, never()).findById(any());
        verify(stepRepository, never()).save(any());
    }

    @Test
    @DisplayName("orderId trong event không phải UUID thì bỏ qua")
    void orderIdKhongHopLe() {
        String json = """
                {"eventId":"evt-4","eventType":"PaymentCompleted","orderId":"khong-phai-uuid"}
                """;

        listener.onPaymentEvent(record(json));

        verify(orderRepository, never()).findById(any());
    }

    @Test
    @DisplayName("event trỏ tới đơn không tồn tại thì chỉ ghi log, không ghi dấu vết")
    void donKhongTonTai() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        listener.onPaymentEvent(record(event("evt-5", "PaymentCompleted", "OK")));

        verify(orderRepository, never()).save(any());
        verify(stepRepository, never()).save(any());
    }

    @Test
    @DisplayName("eventType lạ thì bỏ qua, không đoán trạng thái")
    void eventTypeLa() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.CONFIRMING)));

        listener.onPaymentEvent(record(event("evt-6", "PaymentKhongBiet", "OK")));

        verify(orderRepository, never()).save(any());
        verify(stepRepository, never()).save(any());
    }
}
