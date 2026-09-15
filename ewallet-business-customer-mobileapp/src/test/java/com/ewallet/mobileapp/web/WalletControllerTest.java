package com.ewallet.mobileapp.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.mobileapp.client.OrderClient;
import com.ewallet.mobileapp.dto.WalletDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * BFF client-facing (docs/specs/01-api-contracts.md §3): đổi ngôn ngữ app khách hàng
 * sang ngôn ngữ đơn hàng rồi chuyển tiếp xuống payment-order.
 *
 * <p>Phạm vi: hoá đơn (F2), thẻ điện thoại, chuyển tiền (F3), lịch sử (F6).
 * Đường nạp tiền F1 xem {@code docs/testing.md} §3.</p>
 */
@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

    private static final String KEY = "idem-0001";

    @Mock
    private OrderClient orderClient;

    @InjectMocks
    private WalletController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(orderClient.createOrder(any(), anyString()))
                .thenReturn(ResponseEntity.ok("{\"status\":\"COMPLETED\"}"));
        lenient().when(orderClient.get(anyString()))
                .thenReturn(ResponseEntity.ok("{\"count\":0}"));
    }

    private WalletDtos.CreateOrderCommand capturedCommand() {
        ArgumentCaptor<WalletDtos.CreateOrderCommand> captor =
                ArgumentCaptor.forClass(WalletDtos.CreateOrderCommand.class);
        verify(orderClient).createOrder(captor.capture(), anyString());
        return captor.getValue();
    }

    private String capturedPath() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(orderClient).get(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("POST /api/wallet/transfer — chuyển tiền (F3)")
    class Transfer {

        private static final String BODY = """
                {"customerId":"CUST-001","destCustomerId":"CUST-002","amount":3000000,
                 "currency":"VND","message":"tra no"}
                """;

        @Test
        @DisplayName("hợp lệ: chuyển thành đơn P2P gửi xuống payment-order")
        void chuyenTienHopLe() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk());

            WalletDtos.CreateOrderCommand cmd = capturedCommand();
            assertThat(cmd.paymentType()).isEqualTo("P2P");
            assertThat(cmd.customerId()).isEqualTo("CUST-001");
            assertThat(cmd.destCustomerId()).isEqualTo("CUST-002");
            assertThat(cmd.amount()).isEqualTo(3_000_000L);
            assertThat(cmd.partnerCode()).isNull();
            assertThat(cmd.billCode()).isNull();
        }

        @Test
        @DisplayName("R-P2P-01: tự chuyển cho mình bị chặn ngay ở BFF, không gọi xuống dưới")
        void tuChuyenChoMinh() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"customerId":"CUST-001","destCustomerId":"CUST-001","amount":100000}
                                    """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("SELF_TRANSFER_NOT_ALLOWED"));

            verify(orderClient, never()).createOrder(any(), anyString());
        }

        @Test
        @DisplayName("tự chuyển cho mình không phân biệt hoa thường")
        void tuChuyenKhongPhanBietHoaThuong() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"customerId":"CUST-001","destCustomerId":"cust-001","amount":100000}
                                    """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("R-IDEM-01: thiếu header idempotency thì trả 400")
        void thieuIdempotencyKey() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

            verify(orderClient, never()).createOrder(any(), anyString());
        }

        @Test
        @DisplayName("header idempotency rỗng cũng bị coi là thiếu")
        void idempotencyKeyRong() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .header("X-Idempotency-Key", "  ")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("không ghi loại tiền thì mặc định VND")
        void macDinhVnd() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"customerId":"CUST-001","destCustomerId":"CUST-002","amount":100000}
                                    """))
                    .andExpect(status().isOk());

            assertThat(capturedCommand().currency()).isEqualTo("VND");
        }

        @Test
        @DisplayName("loại tiền được chuẩn hoá về chữ hoa")
        void chuanHoaLoaiTien() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"customerId":"CUST-001","destCustomerId":"CUST-002",
                                     "amount":100000,"currency":"usd"}
                                    """))
                    .andExpect(status().isOk());

            assertThat(capturedCommand().currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("thiếu người nhận thì bị chặn ở tầng kiểm tra dữ liệu")
        void thieuNguoiNhan() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"customerId\":\"CUST-001\",\"amount\":100000}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        @DisplayName("số tiền nhỏ hơn 1 bị chặn ở tầng kiểm tra dữ liệu")
        void soTienKhongHopLe() throws Exception {
            mockMvc.perform(post("/api/wallet/transfer")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"customerId":"CUST-001","destCustomerId":"CUST-002","amount":0}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/wallet/bill/pay — thanh toán hoá đơn (F2)")
    class PayBill {

        private static final String BODY = """
                {"customerId":"CUST-001","partnerCode":"EVN","billCode":"PE0123456789",
                 "amount":1250000,"currency":"VND"}
                """;

        @Test
        @DisplayName("hợp lệ: chuyển thành đơn BILL mang mã hoá đơn và mã đối tác")
        void thanhToanHopLe() throws Exception {
            mockMvc.perform(post("/api/wallet/bill/pay")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk());

            WalletDtos.CreateOrderCommand cmd = capturedCommand();
            assertThat(cmd.paymentType()).isEqualTo("BILL");
            assertThat(cmd.partnerCode()).isEqualTo("EVN");
            assertThat(cmd.billCode()).isEqualTo("PE0123456789");
            assertThat(cmd.destCustomerId()).isNull();
            assertThat(cmd.accountRef()).isNull();
        }

        @Test
        @DisplayName("thiếu header idempotency thì trả 400")
        void thieuIdempotencyKey() throws Exception {
            mockMvc.perform(post("/api/wallet/bill/pay")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        }

        @Test
        @DisplayName("thiếu mã hoá đơn thì bị chặn ở tầng kiểm tra dữ liệu")
        void thieuMaHoaDon() throws Exception {
            mockMvc.perform(post("/api/wallet/bill/pay")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"customerId":"CUST-001","partnerCode":"EVN","amount":1250000}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/wallet/telco/topup — nạp thẻ điện thoại")
    class TelcoTopup {

        private String body(String phone, long amount) {
            return """
                    {"customerId":"CUST-001","partnerCode":"VTELCO","phoneNumber":"%s",
                     "amount":%d,"currency":"VND"}
                    """.formatted(phone, amount);
        }

        @ParameterizedTest(name = "mệnh giá {0}đ")
        @ValueSource(longs = {10_000L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L})
        @DisplayName("R-TELCO-02: các mệnh giá trong danh sách đều được chấp nhận")
        void menhGiaHopLe(long amount) throws Exception {
            mockMvc.perform(post("/api/wallet/telco/topup")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("0901234567", amount)))
                    .andExpect(status().isOk());

            WalletDtos.CreateOrderCommand cmd = capturedCommand();
            assertThat(cmd.paymentType()).isEqualTo("TELCO");
            assertThat(cmd.accountRef()).isEqualTo("0901234567");
            assertThat(cmd.amount()).isEqualTo(amount);
        }

        @ParameterizedTest(name = "mệnh giá {0}đ bị từ chối")
        @ValueSource(longs = {15_000L, 1_000L, 30_000L, 1_000_000L})
        @DisplayName("R-TELCO-02: mệnh giá ngoài danh sách thì trả 422")
        void menhGiaKhongHopLe(long amount) throws Exception {
            mockMvc.perform(post("/api/wallet/telco/topup")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("0901234567", amount)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_DENOMINATION"));

            verify(orderClient, never()).createOrder(any(), anyString());
        }

        @ParameterizedTest(name = "số thuê bao [{0}]")
        @ValueSource(strings = {"090123456", "09012345678", "090123456a", "+84901234567"})
        @DisplayName("R-TELCO-01: số thuê bao sai định dạng thì trả 400")
        void soThueBaoKhongHopLe(String phone) throws Exception {
            mockMvc.perform(post("/api/wallet/telco/topup")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(phone, 50_000L)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER"));

            verify(orderClient, never()).createOrder(any(), anyString());
        }

        @Test
        @DisplayName("số thuê bao được kiểm tra trước mệnh giá")
        void kiemTraSoThueBaoTruoc() throws Exception {
            mockMvc.perform(post("/api/wallet/telco/topup")
                            .header("X-Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("090", 15_000L)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER"));
        }

        @Test
        @DisplayName("thiếu header idempotency thì chặn trước cả hai kiểm tra kia")
        void thieuIdempotencyKey() throws Exception {
            mockMvc.perform(post("/api/wallet/telco/topup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("090", 15_000L)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        }
    }

    @Nested
    @DisplayName("các endpoint chỉ đọc")
    class ChiDoc {

        @Test
        @DisplayName("GET /api/wallet/bill dựng đúng đường dẫn tra cứu hoá đơn")
        void traCuuHoaDon() throws Exception {
            mockMvc.perform(get("/api/wallet/bill")
                            .param("partnerCode", "EVN")
                            .param("billCode", "PE0123456789")
                            .param("customerId", "CUST-001"))
                    .andExpect(status().isOk());

            assertThat(capturedPath())
                    .isEqualTo("/api/orders/bill-inquiry?partnerCode=EVN&billCode=PE0123456789"
                            + "&customerId=CUST-001");
        }

        @Test
        @DisplayName("không truyền customerId thì đường dẫn không có tham số đó")
        void traCuuHoaDonKhongCustomerId() throws Exception {
            mockMvc.perform(get("/api/wallet/bill")
                            .param("partnerCode", "EVN")
                            .param("billCode", "PE0123456789"))
                    .andExpect(status().isOk());

            assertThat(capturedPath()).doesNotContain("customerId");
        }

        @Test
        @DisplayName("tham số có ký tự đặc biệt được mã hoá để không vỡ đường dẫn")
        void maHoaThamSo() throws Exception {
            mockMvc.perform(get("/api/wallet/bill")
                            .param("partnerCode", "EVN&x=1")
                            .param("billCode", "PE 0123"))
                    .andExpect(status().isOk());

            assertThat(capturedPath()).contains("EVN%26x%3D1").contains("PE+0123");
        }

        @Test
        @DisplayName("GET /api/wallet/transactions chuyển tiếp kèm giới hạn số bản ghi")
        void lichSuGiaoDich() throws Exception {
            mockMvc.perform(get("/api/wallet/transactions")
                            .param("customerId", "CUST-001")
                            .param("limit", "5"))
                    .andExpect(status().isOk());

            assertThat(capturedPath()).isEqualTo("/api/orders/history?customerId=CUST-001&limit=5");
        }

        @Test
        @DisplayName("không truyền limit thì để payment-order tự quyết mặc định")
        void lichSuKhongLimit() throws Exception {
            mockMvc.perform(get("/api/wallet/transactions").param("customerId", "CUST-001"))
                    .andExpect(status().isOk());

            assertThat(capturedPath()).isEqualTo("/api/orders/history?customerId=CUST-001");
        }

        @Test
        @DisplayName("GET /api/wallet/orders/{id} chuyển tiếp xuống payment-order")
        void traCuuMotDon() throws Exception {
            mockMvc.perform(get("/api/wallet/orders/{id}", "abc-123"))
                    .andExpect(status().isOk());

            assertThat(capturedPath()).isEqualTo("/api/orders/abc-123");
        }
    }
}
