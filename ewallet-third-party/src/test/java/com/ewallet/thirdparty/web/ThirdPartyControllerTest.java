package com.ewallet.thirdparty.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.thirdparty.entity.PartnerTransaction;
import com.ewallet.thirdparty.partner.ExecuteCommand;
import com.ewallet.thirdparty.repo.PartnerConfigRepository;
import com.ewallet.thirdparty.service.PartnerExecutionService;
import com.ewallet.thirdparty.ws.PartnerWsClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Ranh giới HTTP ra hệ ngoài (docs/specs/01-api-contracts.md §7).
 *
 * <p>Điểm quan trọng: endpoint {@code /execute} luôn trả HTTP 200, kết quả nghiệp vụ
 * nằm trong body — payment-business đọc {@code status} để quyết định có bù trừ hay không.</p>
 */
@ExtendWith(MockitoExtension.class)
class ThirdPartyControllerTest {

    private static final String ORDER_ID = "33333333-3333-4333-8333-333333333333";

    @Mock private PartnerExecutionService executionService;
    @Mock private PartnerConfigRepository configRepository;
    @Mock private PartnerWsClient wsClient;

    @InjectMocks
    private ThirdPartyController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String executeBody(String orderId) {
        return """
                {"orderId":"%s","partnerCode":"EVN","paymentType":"BILL","amount":450000,
                 "currency":"VND","accountRef":null,"billCode":"PD0123456"}
                """.formatted(orderId);
    }

    @Test
    @DisplayName("GET /api/thirdparty/ping báo cả số đối tác lẫn tình trạng kênh WebSocket")
    void ping() throws Exception {
        when(configRepository.count()).thenReturn(3L);
        when(wsClient.isConnected()).thenReturn(true);

        mockMvc.perform(get("/api/thirdparty/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("ewallet-third-party"))
                .andExpect(jsonPath("$.partnerConfigRows").value(3))
                .andExpect(jsonPath("$.websocketConnected").value(true));
    }

    @Nested
    @DisplayName("POST /api/thirdparty/execute")
    class Execute {

        @Test
        @DisplayName("đối tác chấp nhận: HTTP 200, body mang SUCCESS và mã tham chiếu")
        void doiTacChapNhan() throws Exception {
            when(executionService.execute(any())).thenReturn(
                    new PartnerExecutionService.ExecutionOutcome("SUCCESS", "OK", "PRT-9911", 123L));

            mockMvc.perform(post("/api/thirdparty/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody(ORDER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.partnerRef").value("PRT-9911"))
                    .andExpect(jsonPath("$.elapsedMs").value(123));
        }

        @Test
        @DisplayName("đối tác từ chối vẫn là HTTP 200 — kết quả nghiệp vụ nằm trong body")
        void tuChoiVanLa200() throws Exception {
            when(executionService.execute(any())).thenReturn(
                    new PartnerExecutionService.ExecutionOutcome("DECLINED", "PARTNER_DECLINED", null, 80L));

            mockMvc.perform(post("/api/thirdparty/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody(ORDER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DECLINED"))
                    .andExpect(jsonPath("$.partnerRef").value(""));
        }

        @Test
        @DisplayName("quá hạn cũng là HTTP 200, phân biệt với DECLINED bằng trường status")
        void quaHanVanLa200() throws Exception {
            when(executionService.execute(any())).thenReturn(
                    new PartnerExecutionService.ExecutionOutcome("TIMEOUT", "PARTNER_TIMEOUT", null, 3_000L));

            mockMvc.perform(post("/api/thirdparty/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody(ORDER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("TIMEOUT"));
        }

        @Test
        @DisplayName("nội dung request được chuyển đủ thành lệnh cho tầng nghiệp vụ")
        void chuyenDuNoiDung() throws Exception {
            when(executionService.execute(any())).thenReturn(
                    new PartnerExecutionService.ExecutionOutcome("SUCCESS", "OK", "PRT-1", 10L));

            mockMvc.perform(post("/api/thirdparty/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody(ORDER_ID)))
                    .andExpect(status().isOk());

            ArgumentCaptor<ExecuteCommand> captor = ArgumentCaptor.forClass(ExecuteCommand.class);
            verify(executionService).execute(captor.capture());
            ExecuteCommand cmd = captor.getValue();
            assertThat(cmd.partnerCode()).isEqualTo("EVN");
            assertThat(cmd.paymentType()).isEqualTo("BILL");
            assertThat(cmd.amount()).isEqualTo(450_000L);
            assertThat(cmd.billCode()).isEqualTo("PD0123456");
        }

        @Test
        @DisplayName("thiếu orderId thì trả 400, không gọi đối tác")
        void thieuOrderId() throws Exception {
            mockMvc.perform(post("/api/thirdparty/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"partnerCode":"EVN","paymentType":"BILL","amount":450000}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reasonCode").value("MISSING_ORDER_ID"));

            verify(executionService, never()).execute(any());
        }

        @Test
        @DisplayName("orderId không phải UUID thì trả 400 INVALID_ORDER_ID")
        void orderIdKhongHopLe() throws Exception {
            mockMvc.perform(post("/api/thirdparty/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody("khong-phai-uuid")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reasonCode").value("INVALID_ORDER_ID"));

            verify(executionService, never()).execute(any());
        }
    }

    @Nested
    @DisplayName("POST /api/thirdparty/bill-inquiry")
    class BillInquiry {

        @Test
        @DisplayName("đối tác trả đủ thông tin thì chuyển nguyên ra ngoài")
        void hoaDonDayDu() throws Exception {
            when(executionService.billInquiry("EVN", "PD0123456")).thenReturn(Map.of(
                    "status", "FOUND",
                    "billCode", "PD0123456",
                    "customerName", "Nguyen Van A",
                    "period", "2026-08",
                    "amount", 450_000L,
                    "currency", "VND",
                    "billStatus", "UNPAID"));

            mockMvc.perform(post("/api/thirdparty/bill-inquiry")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"partnerCode\":\"EVN\",\"billCode\":\"PD0123456\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FOUND"))
                    .andExpect(jsonPath("$.customerName").value("Nguyen Van A"))
                    .andExpect(jsonPath("$.amount").value(450_000L));
        }

        @Test
        @DisplayName("đối tác trả thiếu trường thì điền giá trị mặc định thay vì để trống")
        void dienGiaTriMacDinh() throws Exception {
            when(executionService.billInquiry(anyString(), anyString()))
                    .thenReturn(Map.of("status", "NOT_FOUND"));

            mockMvc.perform(post("/api/thirdparty/bill-inquiry")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"partnerCode\":\"EVN\",\"billCode\":\"PD9999999\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.billCode").value("PD9999999"))
                    .andExpect(jsonPath("$.amount").value(0))
                    .andExpect(jsonPath("$.currency").value("VND"));
        }
    }

    @Nested
    @DisplayName("GET /api/thirdparty/transactions/{orderId}")
    class Transactions {

        private PartnerTransaction txn() {
            PartnerTransaction t = new PartnerTransaction();
            t.setId(UUID.randomUUID());
            t.setOrderId(UUID.fromString(ORDER_ID));
            t.setPartnerCode("EVN");
            t.setServiceType("BILL");
            t.setAmount(450_000L);
            t.setStatus(PartnerTransaction.SUCCESS);
            t.setPartnerRef("PRT-9911");
            t.setBillCode("PD0123456");
            t.setAttempt(1);
            t.setCreatedAt(OffsetDateTime.now());
            return t;
        }

        @Test
        @DisplayName("trả các lần gọi đối tác của một đơn")
        void traDanhSach() throws Exception {
            when(executionService.byOrder(UUID.fromString(ORDER_ID))).thenReturn(List.of(txn()));

            mockMvc.perform(get("/api/thirdparty/transactions/{orderId}", ORDER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                    .andExpect(jsonPath("$.count").value(1))
                    .andExpect(jsonPath("$.items[0].partnerCode").value("EVN"))
                    .andExpect(jsonPath("$.items[0].status").value(PartnerTransaction.SUCCESS))
                    .andExpect(jsonPath("$.items[0].partnerRef").value("PRT-9911"))
                    .andExpect(jsonPath("$.items[0].attempt").value(1));
        }

        @Test
        @DisplayName("đơn chưa gọi đối tác lần nào thì trả danh sách rỗng")
        void danhSachRong() throws Exception {
            when(executionService.byOrder(any())).thenReturn(List.of());

            mockMvc.perform(get("/api/thirdparty/transactions/{orderId}", ORDER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(0));
        }

        @Test
        @DisplayName("orderId không hợp lệ thì trả 400, không đụng DB")
        void orderIdKhongHopLe() throws Exception {
            mockMvc.perform(get("/api/thirdparty/transactions/{orderId}", "khong-phai-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

            verify(executionService, never()).byOrder(any());
        }
    }
}
