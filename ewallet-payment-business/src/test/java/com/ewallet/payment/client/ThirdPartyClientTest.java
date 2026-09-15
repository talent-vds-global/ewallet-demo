package com.ewallet.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ewallet.payment.domain.BillInquiryResult;
import com.ewallet.payment.domain.PartnerExecutionResult;
import com.ewallet.payment.domain.ReasonCodes;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Client HTTP sang ewallet-third-party.
 *
 * <p>Điểm quan trọng nhất là <b>phân loại lỗi</b>: "đối tác từ chối" và "đối tác không trả lời"
 * dẫn tới hai quyết định khác nhau ở saga, nên client phải trả DECLINED và TIMEOUT
 * cho đúng trường hợp. Timeout thường nằm sau vài lớp bọc exception nên dễ bị
 * báo nhầm thành từ chối.</p>
 */
class ThirdPartyClientTest {

    private static final String BASE_URL = "http://ewallet-third-party:8084";
    private static final String EXECUTE_URL = BASE_URL + "/api/thirdparty/execute";
    private static final String INQUIRY_URL = BASE_URL + "/api/thirdparty/bill-inquiry";

    private final UUID orderId = UUID.randomUUID();

    private MockRestServiceServer server;
    private ThirdPartyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ThirdPartyClient(builder.build());
    }

    private PartnerExecutionResult execute() {
        return client.execute(orderId, "EVN", "BILL", 450_000L, "VND", "0901234567", "PE0123456789");
    }

    @Nested
    @DisplayName("execute — gọi đối tác")
    class Execute {

        @Test
        @DisplayName("đối tác chấp nhận thì trả SUCCESS kèm mã tham chiếu và thời gian đối tác báo")
        void chapNhan() {
            server.expect(requestTo(EXECUTE_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.partnerCode").value("EVN"))
                    .andExpect(jsonPath("$.amount").value(450_000L))
                    .andExpect(jsonPath("$.billCode").value("PE0123456789"))
                    .andRespond(withSuccess("""
                            {"status":"SUCCESS","partnerRef":"PRT-9911","reasonCode":"OK","elapsedMs":123}
                            """, MediaType.APPLICATION_JSON));

            PartnerExecutionResult result = execute();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.partnerRef()).isEqualTo("PRT-9911");
            assertThat(result.elapsedMs()).isEqualTo(123L);
            server.verify();
        }

        @Test
        @DisplayName("đối tác từ chối thì trả DECLINED kèm đúng lý do của họ")
        void tuChoi() {
            server.expect(requestTo(EXECUTE_URL)).andRespond(withSuccess("""
                    {"status":"DECLINED","partnerRef":null,
                     "reasonCode":"INSUFFICIENT_PARTNER_BALANCE","elapsedMs":80}
                    """, MediaType.APPLICATION_JSON));

            PartnerExecutionResult result = execute();

            assertThat(result.status()).isEqualTo(PartnerExecutionResult.DECLINED);
            assertThat(result.reasonCode()).isEqualTo("INSUFFICIENT_PARTNER_BALANCE");
            assertThat(result.partnerRef()).isEmpty();
        }

        @Test
        @DisplayName("R-TOPUP-05: báo SUCCESS nhưng thiếu mã tham chiếu thì coi như thất bại")
        void thieuMaThamChieu() {
            server.expect(requestTo(EXECUTE_URL)).andRespond(withSuccess("""
                    {"status":"SUCCESS","partnerRef":"","reasonCode":"OK","elapsedMs":50}
                    """, MediaType.APPLICATION_JSON));

            PartnerExecutionResult result = execute();

            assertThat(result.status()).isEqualTo(PartnerExecutionResult.DECLINED);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.PARTNER_DECLINED);
        }

        @Test
        @DisplayName("thiếu lý do trong phản hồi thì coi như OK")
        void thieuLyDo() {
            server.expect(requestTo(EXECUTE_URL)).andRespond(withSuccess("""
                    {"status":"SUCCESS","partnerRef":"PRT-1","elapsedMs":0}
                    """, MediaType.APPLICATION_JSON));

            PartnerExecutionResult result = execute();

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.OK);
            // đối tác không báo thời gian thì lấy thời gian tự đo
            assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("mất kết nối tới third-party là DECLINED, không phải TIMEOUT")
        void matKetNoi() {
            server.expect(requestTo(EXECUTE_URL))
                    .andRespond(req -> {
                        throw new ResourceAccessException("connection refused");
                    });

            PartnerExecutionResult result = execute();

            assertThat(result.status()).isEqualTo(PartnerExecutionResult.DECLINED);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.PARTNER_DECLINED);
        }

        @Test
        @DisplayName("hết hạn chờ là TIMEOUT — saga phải phân biệt được với bị từ chối")
        void quaHanCho() {
            server.expect(requestTo(EXECUTE_URL))
                    .andRespond(req -> {
                        throw new ResourceAccessException("doc qua han",
                                new SocketTimeoutException("Read timed out"));
                    });

            PartnerExecutionResult result = execute();

            assertThat(result.status()).isEqualTo(PartnerExecutionResult.TIMEOUT);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.PARTNER_TIMEOUT);
        }

        @Test
        @DisplayName("timeout nằm sâu sau nhiều lớp bọc vẫn được nhận ra")
        void quaHanBocNhieuLop() {
            server.expect(requestTo(EXECUTE_URL))
                    .andRespond(req -> {
                        throw new ResourceAccessException("lop ngoai",
                                new IOException("lop giua",
                                        new SocketTimeoutException("Read timed out")));
                    });

            assertThat(execute().status()).isEqualTo(PartnerExecutionResult.TIMEOUT);
        }

        @Test
        @DisplayName("third-party trả lỗi 5xx thì coi là DECLINED")
        void loiMayChu() {
            server.expect(requestTo(EXECUTE_URL)).andRespond(withServerError());

            PartnerExecutionResult result = execute();

            assertThat(result.status()).isEqualTo(PartnerExecutionResult.DECLINED);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.PARTNER_DECLINED);
        }
    }

    @Nested
    @DisplayName("inquireBill — tra cứu hoá đơn")
    class InquireBill {

        @Test
        @DisplayName("tìm thấy hoá đơn thì trả đủ thông tin")
        void timThay() {
            server.expect(requestTo(INQUIRY_URL))
                    .andExpect(jsonPath("$.billCode").value("PE0123456789"))
                    .andRespond(withSuccess("""
                            {"status":"FOUND","billCode":"PE0123456789","customerName":"Nguyen Van A",
                             "period":"2026-08","amount":1250000,"currency":"VND","billStatus":"UNPAID"}
                            """, MediaType.APPLICATION_JSON));

            BillInquiryResult result = client.inquireBill("EVN", "PE0123456789");

            assertThat(result.status()).isEqualTo(BillInquiryResult.FOUND);
            assertThat(result.customerName()).isEqualTo("Nguyen Van A");
            assertThat(result.amount()).isEqualTo(1_250_000L);
            assertThat(result.billStatus()).isEqualTo("UNPAID");
        }

        @Test
        @DisplayName("phản hồi thiếu trường thì điền mặc định thay vì để null")
        void thieuTruong() {
            server.expect(requestTo(INQUIRY_URL)).andRespond(withSuccess("""
                    {"status":"FOUND","amount":0}
                    """, MediaType.APPLICATION_JSON));

            BillInquiryResult result = client.inquireBill("EVN", "PE0123456789");

            assertThat(result.billCode()).isEqualTo("PE0123456789");
            assertThat(result.customerName()).isEmpty();
            assertThat(result.period()).isEmpty();
            assertThat(result.currency()).isEqualTo("VND");
            assertThat(result.billStatus()).isEmpty();
        }

        @Test
        @DisplayName("hoá đơn đã thanh toán thì trả ALREADY_PAID")
        void daThanhToan() {
            server.expect(requestTo(INQUIRY_URL)).andRespond(withSuccess("""
                    {"status":"ALREADY_PAID","billCode":"PE0999999999","amount":850000,
                     "billStatus":"PAID"}
                    """, MediaType.APPLICATION_JSON));

            assertThat(client.inquireBill("EVN", "PE0999999999").status())
                    .isEqualTo(BillInquiryResult.ALREADY_PAID);
        }

        @Test
        @DisplayName("gọi hỏng thì coi như không tìm thấy, không làm đứt luồng thanh toán")
        void goiHong() {
            server.expect(requestTo(INQUIRY_URL)).andRespond(withServerError());

            BillInquiryResult result = client.inquireBill("EVN", "PE0123456789");

            assertThat(result.status()).isEqualTo(BillInquiryResult.NOT_FOUND);
            assertThat(result.billCode()).isEqualTo("PE0123456789");
        }
    }
}
