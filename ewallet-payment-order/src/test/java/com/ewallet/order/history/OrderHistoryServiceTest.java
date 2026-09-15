package com.ewallet.order.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.order.dto.OrderDtos;
import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.entity.PaymentOrder;
import com.ewallet.order.repo.OrderStepRepository;
import com.ewallet.order.repo.PaymentOrderRepository;
import com.ewallet.order.web.OrderMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * Tra cứu lịch sử giao dịch (F6) — R-HIST-01 (mới nhất trước), R-HIST-03 (mặc định 20, trần 100).
 */
@ExtendWith(MockitoExtension.class)
class OrderHistoryServiceTest {

    private static final String CUSTOMER = "CUST-001";

    @Mock private PaymentOrderRepository orderRepository;
    @Mock private OrderStepRepository stepRepository;

    private OrderHistoryService historyService;

    @BeforeEach
    void setUp() {
        historyService = new OrderHistoryService(orderRepository, stepRepository, new OrderMapper());
    }

    private PaymentOrder order(String status, long amount) {
        PaymentOrder o = new PaymentOrder();
        o.setId(UUID.randomUUID());
        o.setCustomerId(CUSTOMER);
        o.setPaymentType("P2P");
        o.setAmount(amount);
        o.setFee(2_200L);
        o.setCurrency("VND");
        o.setAmountVnd(amount);
        o.setStatus(status);
        o.setReasonCode("OK");
        o.setCreatedAt(OffsetDateTime.now());
        return o;
    }

    private List<PaymentOrder> orders(int count) {
        List<PaymentOrder> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(order(PaymentOrder.COMPLETED, 100_000L * (i + 1)));
        }
        return list;
    }

    @Test
    @DisplayName("trả về đủ số đơn của khách kèm các bước xử lý của từng đơn")
    void traVeDuDonVaBuoc() {
        List<PaymentOrder> orders = orders(3);
        when(orderRepository.findHistory(anyString(), any(Pageable.class))).thenReturn(orders);
        when(stepRepository.findByOrderIdOrderByCreatedAtAsc(any())).thenReturn(List.of(
                OrderStep.of(orders.get(0).getId(), OrderStep.AUTHORIZE, OrderStep.DONE, "OK", 1, 42L)));

        OrderDtos.HistoryResponse response = historyService.history(CUSTOMER, 10);

        assertThat(response.customerId()).isEqualTo(CUSTOMER);
        assertThat(response.count()).isEqualTo(3);
        assertThat(response.items()).hasSize(3);
        assertThat(response.items().get(0).steps()).hasSize(1);
        assertThat(response.items().get(0).steps().get(0).stepName()).isEqualTo(OrderStep.AUTHORIZE);
        assertThat(response.items().get(0).steps().get(0).durationMs()).isEqualTo(42L);
    }

    @Test
    @DisplayName("khách chưa có giao dịch nào thì trả danh sách rỗng chứ không lỗi")
    void khachChuaCoGiaoDich() {
        when(orderRepository.findHistory(anyString(), any(Pageable.class))).thenReturn(List.of());

        OrderDtos.HistoryResponse response = historyService.history("CUST-999", null);

        assertThat(response.count()).isZero();
        assertThat(response.items()).isEmpty();
    }

    @ParameterizedTest(name = "limit={0} -> lấy {1} đơn")
    @CsvSource({
            "5,    5",
            "1,    1",
            "100,  100",
            "500,  100",     // R-HIST-03: kẹp trần ở 100
            "0,    20",      // không hợp lệ -> mặc định
            "-3,   20"
    })
    @DisplayName("R-HIST-03: limit mặc định 20, trần 100, giá trị vô lý thì về mặc định")
    void chuanHoaLimit(int limit, int expectedSize) {
        when(orderRepository.findHistory(anyString(), any(Pageable.class))).thenReturn(List.of());

        historyService.history(CUSTOMER, limit);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findHistory(anyString(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(expectedSize);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("không truyền limit thì lấy mặc định 20 đơn")
    void khongTruyenLimit(Integer limit) {
        when(orderRepository.findHistory(anyString(), any(Pageable.class))).thenReturn(List.of());

        historyService.history(CUSTOMER, limit);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findHistory(anyString(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("đơn đã hoàn tiền được đánh dấu refunded để giao diện hiển thị khác đi")
    void danhDauDonDaHoanTien() {
        when(orderRepository.findHistory(anyString(), any(Pageable.class)))
                .thenReturn(List.of(order(PaymentOrder.REFUNDED, 450_000L)));
        when(stepRepository.findByOrderIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        OrderDtos.HistoryResponse response = historyService.history(CUSTOMER, 10);

        assertThat(response.items().get(0).refunded()).isTrue();
        assertThat(response.items().get(0).status()).isEqualTo(PaymentOrder.REFUNDED);
    }
}
