package com.ewallet.partnersim.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.partnersim.domain.BillRegistry;
import com.ewallet.partnersim.domain.PartnerBehavior;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * API của đối tác giả lập (docs/specs/01-api-contracts.md §8).
 *
 * <p>Dùng {@link PartnerBehavior} thật vì chính tính tất định theo số tiền là thứ
 * cần kiểm chứng ở đây — số tiền đuôi 999 phải luôn bị từ chối.</p>
 */
class PartnerControllerTest {

    private static final String ORDER_ID = UUID.randomUUID().toString();

    private BillRegistry bills;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        bills = new BillRegistry();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PartnerController(new PartnerBehavior(0L, 0.0, 0.0), bills))
                .build();
    }

    private String executeBody(long amount, String billCode) {
        return """
                {"orderId":"%s","amount":%d%s}
                """.formatted(ORDER_ID, amount,
                billCode == null ? "" : ",\"billCode\":\"" + billCode + "\"");
    }

    @Nested
    @DisplayName("POST /partner/{code}/execute")
    class Execute {

        @Test
        @DisplayName("số tiền bình thường thì đối tác chấp nhận và cấp mã tham chiếu")
        void chapNhan() throws Exception {
            mockMvc.perform(post("/partner/{code}/execute", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody(450_000L, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.reasonCode").value("OK"))
                    .andExpect(jsonPath("$.partnerCode").value("EVN"))
                    .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                    .andExpect(jsonPath("$.partnerRef").isNotEmpty())
                    // quyết toán đẩy về sau qua WebSocket nên chưa có ở đây
                    .andExpect(jsonPath("$.settledAt").doesNotExist());
        }

        @Test
        @DisplayName("số tiền đuôi 999 thì đối tác từ chối, không cấp mã tham chiếu")
        void tuChoi() throws Exception {
            mockMvc.perform(post("/partner/{code}/execute", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody(450_999L, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DECLINED"))
                    .andExpect(jsonPath("$.reasonCode").value("PARTNER_DECLINED"))
                    .andExpect(jsonPath("$.partnerRef").doesNotExist());
        }

        @Test
        @DisplayName("thanh toán hoá đơn thành công thì hoá đơn đó chuyển sang đã trả")
        void thanhToanThiDanhDauHoaDon() throws Exception {
            mockMvc.perform(post("/partner/{code}/execute", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody(1_250_000L, "PE0123456789")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"));

            mockMvc.perform(post("/partner/{code}/bill-inquiry", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"billCode\":\"PE0123456789\"}"))
                    .andExpect(jsonPath("$.status").value("ALREADY_PAID"));
        }

        @Test
        @DisplayName("giao dịch bị từ chối thì KHÔNG đánh dấu hoá đơn đã trả")
        void tuChoiThiKhongDanhDau() throws Exception {
            mockMvc.perform(post("/partner/{code}/execute", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(executeBody(450_999L, "PE0123456789")))
                    .andExpect(jsonPath("$.status").value("DECLINED"));

            mockMvc.perform(post("/partner/{code}/bill-inquiry", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"billCode\":\"PE0123456789\"}"))
                    .andExpect(jsonPath("$.status").value("FOUND"));
        }

        @Test
        @DisplayName("body rỗng thì coi số tiền là 0 và vẫn trả lời được")
        void bodyRong() throws Exception {
            mockMvc.perform(post("/partner/{code}/execute", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"));
        }

        @Test
        @DisplayName("số tiền gửi dạng chuỗi vẫn đọc được")
        void soTienDangChuoi() throws Exception {
            mockMvc.perform(post("/partner/{code}/execute", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + ORDER_ID + "\",\"amount\":\"450999\"}"))
                    .andExpect(jsonPath("$.status").value("DECLINED"));
        }

        @Test
        @DisplayName("số tiền không đọc được thì coi là 0 thay vì ném lỗi")
        void soTienHong() throws Exception {
            mockMvc.perform(post("/partner/{code}/execute", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + ORDER_ID + "\",\"amount\":\"khong-phai-so\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"));
        }
    }

    @Nested
    @DisplayName("POST /partner/{code}/bill-inquiry")
    class BillInquiry {

        @Test
        @DisplayName("hoá đơn chưa trả thì trả FOUND kèm đủ thông tin")
        void hoaDonChuaTra() throws Exception {
            mockMvc.perform(post("/partner/{code}/bill-inquiry", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"billCode\":\"PE0123456789\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FOUND"))
                    .andExpect(jsonPath("$.customerName").value("Nguyen Van A"))
                    .andExpect(jsonPath("$.period").value("2026-08"))
                    .andExpect(jsonPath("$.amount").value(1_250_000L))
                    .andExpect(jsonPath("$.currency").value("VND"))
                    .andExpect(jsonPath("$.billStatus").value(BillRegistry.UNPAID))
                    .andExpect(jsonPath("$.inquiredAt").isNotEmpty());
        }

        @Test
        @DisplayName("hoá đơn đã trả thì trả ALREADY_PAID")
        void hoaDonDaTra() throws Exception {
            mockMvc.perform(post("/partner/{code}/bill-inquiry", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"billCode\":\"PE0999999999\"}"))
                    .andExpect(jsonPath("$.status").value("ALREADY_PAID"))
                    .andExpect(jsonPath("$.billStatus").value(BillRegistry.PAID));
        }

        @Test
        @DisplayName("hoá đơn không tồn tại thì trả NOT_FOUND với số tiền 0")
        void hoaDonKhongTonTai() throws Exception {
            mockMvc.perform(post("/partner/{code}/bill-inquiry", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"billCode\":\"PE0000000000\"}"))
                    .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.amount").value(0))
                    .andExpect(jsonPath("$.customerName").doesNotExist());
        }

        @Test
        @DisplayName("nạp thẻ điện thoại: số thuê bao 10 chữ số nào cũng tra ra")
        void soThueBao() throws Exception {
            mockMvc.perform(post("/partner/{code}/bill-inquiry", "VTELCO")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"billCode\":\"0901234567\"}"))
                    .andExpect(jsonPath("$.status").value("FOUND"))
                    .andExpect(jsonPath("$.amount").value(0));
        }

        @Test
        @DisplayName("không gửi mã hoá đơn thì trả NOT_FOUND")
        void thieuMaHoaDon() throws Exception {
            mockMvc.perform(post("/partner/{code}/bill-inquiry", "EVN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(jsonPath("$.status").value("NOT_FOUND"));
        }
    }
}
