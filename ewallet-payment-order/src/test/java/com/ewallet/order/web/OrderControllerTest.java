package com.ewallet.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.order.dto.OrderDtos;
import com.ewallet.order.entity.PaymentOrder;
import com.ewallet.order.grpc.PaymentBusinessClient;
import com.ewallet.order.history.OrderHistoryService;
import com.ewallet.order.repo.OrderStepRepository;
import com.ewallet.order.repo.PaymentOrderRepository;
import com.ewallet.order.saga.DuplicateRequestException;
import com.ewallet.order.saga.PaymentSagaOrchestrator;
import com.ewallet.payment.contract.grpc.InquireBillResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tầng HTTP của payment-order (docs/specs/01-api-contracts.md §4).
 *
 * <p>MockMvc standalone: không nâng Spring context, không cần DB hay gRPC thật.
 * Trọng tâm là ánh xạ trạng thái đơn sang mã HTTP và xử lý lỗi.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock private PaymentSagaOrchestrator saga;
    @Mock private OrderHistoryService historyService;
    @Mock private PaymentOrderRepository orderRepository;
    @Mock private OrderStepRepository stepRepository;
    @Mock private PaymentBusinessClient businessClient;

    private OrderController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new OrderController(saga, historyService, orderRepository,
                stepRepository, businessClient, new OrderMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(stepRepository.findByOrderIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private static PaymentOrder order(String status, String reasonCode) {
        PaymentOrder o = new PaymentOrder();
        o.setId(UUID.randomUUID());
        o.setCustomerId("CUST-001");
        o.setPaymentType("P2P");
        o.setAmount(3_000_000L);
        o.setFee(2_200L);
        o.setCurrency("VND");
        o.setAmountVnd(3_000_000L);
        o.setStatus(status);
        o.setReasonCode(reasonCode);
        o.setCreatedAt(OffsetDateTime.now());
        return o;
    }

    private static final String BODY_P2P = """
            {"customerId":"CUST-001","paymentType":"P2P","amount":3000000,
             "currency":"VND","destCustomerId":"CUST-002"}
            """;

    @Test
    @DisplayName("GET /api/orders/ping báo service sống kèm số đơn")
    void ping() throws Exception {
        when(orderRepository.count()).thenReturn(42L);

        mockMvc.perform(get("/api/orders/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("ewallet-payment-order"))
                .andExpect(jsonPath("$.orderCount").value(42));
    }

    @Nested
    @DisplayName("POST /api/orders")
    class Create {

        @Test
        @DisplayName("R-IDEM-01: thiếu header X-Idempotency-Key thì trả 400, không chạy saga")
        void thieuIdempotencyKey() throws Exception {
            mockMvc.perform(post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_P2P))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

            verify(saga, never()).run(any(), anyString());
        }

        @ParameterizedTest(name = "đơn {0} -> HTTP {1}")
        @CsvSource({
                "COMPLETED, 200",
                "HELD,      202",
                "REJECTED,  422",
                "CREATED,   200"
        })
        @DisplayName("trạng thái đơn được ánh xạ sang đúng mã HTTP")
        void anhXaTrangThaiSangHttp(String orderStatus, int httpStatus) throws Exception {
            when(saga.run(any(), eq("key-1"))).thenReturn(order(orderStatus, "OK"));

            mockMvc.perform(post("/api/orders")
                            .header("X-Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_P2P))
                    .andExpect(status().is(httpStatus))
                    .andExpect(jsonPath("$.status").value(orderStatus));
        }

        @Test
        @DisplayName("R-COMP-08: hoàn tiền do đối tác quá hạn thì trả 504 chứ không phải 502")
        void quaHanDoiTacTra504() throws Exception {
            when(saga.run(any(), anyString())).thenReturn(order(PaymentOrder.REFUNDED, "PARTNER_TIMEOUT"));

            mockMvc.perform(post("/api/orders")
                            .header("X-Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_P2P))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.refunded").value(true));
        }

        @Test
        @DisplayName("hoàn tiền vì lý do khác thì trả 502")
        void loiKhacTra502() throws Exception {
            when(saga.run(any(), anyString())).thenReturn(order(PaymentOrder.REFUNDED, "PARTNER_DECLINED"));

            mockMvc.perform(post("/api/orders")
                            .header("X-Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_P2P))
                    .andExpect(status().isBadGateway());
        }

        @Test
        @DisplayName("R-IDEM-03: trùng key khác nội dung thì trả 409 DUPLICATE_REQUEST")
        void trungKeyKhacNoiDung() throws Exception {
            when(saga.run(any(), anyString())).thenThrow(new DuplicateRequestException("key-1"));

            mockMvc.perform(post("/api/orders")
                            .header("X-Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_P2P))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        }

        @Test
        @DisplayName("thiếu customerId thì bị chặn ở tầng kiểm tra dữ liệu, trả 400")
        void thieuCustomerId() throws Exception {
            mockMvc.perform(post("/api/orders")
                            .header("X-Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"paymentType":"P2P","amount":3000000,"destCustomerId":"CUST-002"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

            verify(saga, never()).run(any(), anyString());
        }

        @Test
        @DisplayName("số tiền nhỏ hơn 1 bị chặn ở tầng kiểm tra dữ liệu")
        void soTienKhongHopLe() throws Exception {
            mockMvc.perform(post("/api/orders")
                            .header("X-Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"customerId":"CUST-001","paymentType":"P2P","amount":0}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/orders/{orderId}")
    class GetOne {

        @Test
        @DisplayName("tìm thấy đơn thì trả đủ thông tin")
        void timThayDon() throws Exception {
            PaymentOrder o = order(PaymentOrder.COMPLETED, "OK");
            when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));

            mockMvc.perform(get("/api/orders/{id}", o.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(o.getId().toString()))
                    .andExpect(jsonPath("$.status").value(PaymentOrder.COMPLETED))
                    .andExpect(jsonPath("$.amount").value(3_000_000L))
                    .andExpect(jsonPath("$.fee").value(2_200L));
        }

        @Test
        @DisplayName("không có đơn thì trả 404 ORDER_NOT_FOUND")
        void khongCoDon() throws Exception {
            when(orderRepository.findById(any())).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/orders/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        }

        @Test
        @DisplayName("orderId không phải UUID thì trả 400, không đụng DB")
        void orderIdKhongHopLe() throws Exception {
            mockMvc.perform(get("/api/orders/{id}", "khong-phai-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

            verify(orderRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("GET /api/orders/history")
    class History {

        @Test
        @DisplayName("trả lịch sử của khách")
        void traLichSu() throws Exception {
            when(historyService.history("CUST-001", 5))
                    .thenReturn(new OrderDtos.HistoryResponse("CUST-001", 0, List.of()));

            mockMvc.perform(get("/api/orders/history")
                            .param("customerId", "CUST-001")
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value("CUST-001"))
                    .andExpect(jsonPath("$.count").value(0));
        }

        @Test
        @DisplayName("thiếu customerId thì trả 400")
        void thieuCustomerId() throws Exception {
            mockMvc.perform(get("/api/orders/history"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

            verify(historyService, never()).history(anyString(), any());
        }

        @Test
        @DisplayName("customerId rỗng cũng bị coi là thiếu")
        void customerIdRong() throws Exception {
            mockMvc.perform(get("/api/orders/history").param("customerId", "  "))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/orders/bill-inquiry")
    class BillInquiry {

        private InquireBillResponse bill(String status) {
            return InquireBillResponse.newBuilder()
                    .setStatus(status)
                    .setBillCode("PD0123456")
                    .setCustomerName("Nguyen Van A")
                    .setPeriod("2026-08")
                    .setAmount(450_000L)
                    .setCurrency("VND")
                    .setBillStatus("UNPAID")
                    .build();
        }

        @ParameterizedTest(name = "{0} -> HTTP {1}")
        @CsvSource({
                "FOUND,        200",
                "NOT_FOUND,    404",
                "ALREADY_PAID, 409"
        })
        @DisplayName("kết quả tra cứu được ánh xạ sang mã HTTP tương ứng")
        void anhXaKetQuaSangHttp(String billStatus, int httpStatus) throws Exception {
            when(businessClient.inquireBill(any())).thenReturn(bill(billStatus));

            mockMvc.perform(get("/api/orders/bill-inquiry")
                            .param("partnerCode", "EVN")
                            .param("billCode", "PD0123456"))
                    .andExpect(status().is(httpStatus))
                    .andExpect(jsonPath("$.status").value(billStatus))
                    .andExpect(jsonPath("$.partnerCode").value("EVN"));
        }

        @Test
        @DisplayName("tìm thấy hoá đơn thì trả đủ thông tin cho màn hình xác nhận")
        void thongTinHoaDon() throws Exception {
            when(businessClient.inquireBill(any())).thenReturn(bill("FOUND"));

            mockMvc.perform(get("/api/orders/bill-inquiry")
                            .param("partnerCode", "EVN")
                            .param("billCode", "PD0123456")
                            .param("customerId", "CUST-001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerName").value("Nguyen Van A"))
                    .andExpect(jsonPath("$.period").value("2026-08"))
                    .andExpect(jsonPath("$.amount").value(450_000L))
                    .andExpect(jsonPath("$.billStatus").value("UNPAID"));
        }
    }

    @Nested
    @DisplayName("POST /api/orders/{orderId}/refund")
    class Refund {

        @Test
        @DisplayName("hoàn tiền thành công trả đơn ở trạng thái REFUNDED")
        void hoanTienThanhCong() throws Exception {
            PaymentOrder refunded = order(PaymentOrder.REFUNDED, "CUSTOMER_REQUEST");
            when(saga.refund(any(), anyString())).thenReturn(refunded);

            mockMvc.perform(post("/api/orders/{id}/refund", refunded.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"CUSTOMER_REQUEST\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(PaymentOrder.REFUNDED))
                    .andExpect(jsonPath("$.refunded").value(true));
        }

        @Test
        @DisplayName("gọi không kèm body thì lý do để trống, saga tự quyết mặc định")
        void khongCoBody() throws Exception {
            UUID id = UUID.randomUUID();
            when(saga.refund(eq(id), eq(null))).thenReturn(order(PaymentOrder.REFUNDED, "CUSTOMER_REQUEST"));

            mockMvc.perform(post("/api/orders/{id}/refund", id))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("R-COMP-06: đơn đã hoàn rồi thì trả 409")
        void donDaHoan() throws Exception {
            when(saga.refund(any(), any())).thenThrow(new IllegalStateException("ALREADY_REFUNDED"));

            mockMvc.perform(post("/api/orders/{id}/refund", UUID.randomUUID()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ALREADY_REFUNDED"));
        }

        @Test
        @DisplayName("không tìm thấy đơn thì trả 404")
        void khongTimThayDon() throws Exception {
            when(saga.refund(any(), any())).thenThrow(new IllegalArgumentException("Khong tim thay don"));

            mockMvc.perform(post("/api/orders/{id}/refund", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        }

        @Test
        @DisplayName("orderId không hợp lệ thì trả 400, không gọi saga")
        void orderIdKhongHopLe() throws Exception {
            mockMvc.perform(post("/api/orders/{id}/refund", "khong-phai-uuid"))
                    .andExpect(status().isBadRequest());

            verify(saga, never()).refund(any(), any());
        }
    }
}
