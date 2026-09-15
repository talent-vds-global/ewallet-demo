package com.ewallet.thirdparty.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
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
 * Client HTTP sang partner-sim.
 *
 * <p>Giống {@code ThirdPartyClient} ở tầng trên, điểm mấu chốt là phân biệt
 * "đối tác từ chối" với "đối tác không trả lời": chỉ trường hợp thứ hai mới được
 * gọi lại, vì gọi lại một lệnh đã bị từ chối có nguy cơ ghi nợ hai lần.</p>
 */
class PartnerSimClientTest {

    private static final String BASE_URL = "http://partner-sim:8090";
    private static final String EXECUTE_URL = BASE_URL + "/partner/EVN/execute";
    private static final String INQUIRY_URL = BASE_URL + "/partner/EVN/bill-inquiry";

    private final String orderId = UUID.randomUUID().toString();

    private MockRestServiceServer server;
    private PartnerSimClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PartnerSimClient(builder.build());
    }

    private PartnerSimClient.Result execute() {
        return client.execute("EVN", orderId, 450_000L, "VND", null, "PE0123456789");
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("đối tác chấp nhận thì trả SUCCESS kèm mã tham chiếu và phản hồi gốc")
        void chapNhan() {
            server.expect(requestTo(EXECUTE_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.orderId").value(orderId))
                    .andExpect(jsonPath("$.amount").value(450_000L))
                    .andExpect(jsonPath("$.billCode").value("PE0123456789"))
                    .andRespond(withSuccess("""
                            {"status":"SUCCESS","reasonCode":"OK","partnerRef":"PS-abc123"}
                            """, MediaType.APPLICATION_JSON));

            PartnerSimClient.Result result = execute();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.partnerRef()).isEqualTo("PS-abc123");
            assertThat(result.raw()).containsEntry("status", "SUCCESS");
            server.verify();
        }

        @Test
        @DisplayName("đối tác từ chối thì giữ nguyên lý do của họ")
        void tuChoi() {
            server.expect(requestTo(EXECUTE_URL)).andRespond(withSuccess("""
                    {"status":"DECLINED","reasonCode":"PARTNER_DECLINED","partnerRef":null}
                    """, MediaType.APPLICATION_JSON));

            PartnerSimClient.Result result = execute();

            assertThat(result.status()).isEqualTo(PartnerSimClient.Result.DECLINED);
            assertThat(result.reasonCode()).isEqualTo("PARTNER_DECLINED");
            assertThat(result.partnerRef()).isNull();
        }

        @Test
        @DisplayName("từ chối mà không nêu lý do thì điền lý do mặc định")
        void tuChoiKhongLyDo() {
            server.expect(requestTo(EXECUTE_URL)).andRespond(withSuccess("""
                    {"status":"DECLINED"}
                    """, MediaType.APPLICATION_JSON));

            assertThat(execute().reasonCode()).isEqualTo("PARTNER_DECLINED");
        }

        @Test
        @DisplayName("phản hồi không có trường status thì coi là từ chối")
        void thieuTruongStatus() {
            server.expect(requestTo(EXECUTE_URL))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            assertThat(execute().status()).isEqualTo(PartnerSimClient.Result.DECLINED);
        }

        @Test
        @DisplayName("đối tác không trả lời trong hạn thì trả TIMEOUT để tầng trên gọi lại")
        void quaHan() {
            server.expect(requestTo(EXECUTE_URL))
                    .andRespond(req -> {
                        throw new ResourceAccessException("doc qua han",
                                new SocketTimeoutException("Read timed out"));
                    });

            PartnerSimClient.Result result = execute();

            assertThat(result.status()).isEqualTo(PartnerSimClient.Result.TIMEOUT);
            assertThat(result.reasonCode()).isEqualTo("PARTNER_TIMEOUT");
        }

        @Test
        @DisplayName("timeout nằm sâu sau nhiều lớp bọc vẫn được nhận ra, không báo nhầm thành từ chối")
        void quaHanBocNhieuLop() {
            server.expect(requestTo(EXECUTE_URL))
                    .andRespond(req -> {
                        throw new ResourceAccessException("lop ngoai",
                                new IOException("lop giua", new SocketTimeoutException("Read timed out")));
                    });

            assertThat(execute().status()).isEqualTo(PartnerSimClient.Result.TIMEOUT);
        }

        @Test
        @DisplayName("không kết nối được đối tác là DECLINED/PARTNER_UNREACHABLE, không phải TIMEOUT")
        void khongKetNoiDuoc() {
            server.expect(requestTo(EXECUTE_URL))
                    .andRespond(req -> {
                        throw new ResourceAccessException("connection refused");
                    });

            PartnerSimClient.Result result = execute();

            assertThat(result.status()).isEqualTo(PartnerSimClient.Result.DECLINED);
            assertThat(result.reasonCode()).isEqualTo("PARTNER_UNREACHABLE");
        }

        @Test
        @DisplayName("đối tác trả lỗi 5xx thì coi là từ chối")
        void loiMayChu() {
            server.expect(requestTo(EXECUTE_URL)).andRespond(withServerError());

            PartnerSimClient.Result result = execute();

            assertThat(result.status()).isEqualTo(PartnerSimClient.Result.DECLINED);
            assertThat(result.reasonCode()).isEqualTo("PARTNER_DECLINED");
        }
    }

    @Nested
    @DisplayName("billInquiry")
    class BillInquiry {

        @Test
        @DisplayName("trả nguyên phản hồi của đối tác để tầng trên tự đọc")
        void traNguyenPhanHoi() {
            server.expect(requestTo(INQUIRY_URL))
                    .andExpect(jsonPath("$.billCode").value("PE0123456789"))
                    .andRespond(withSuccess("""
                            {"status":"FOUND","billCode":"PE0123456789","amount":1250000}
                            """, MediaType.APPLICATION_JSON));

            Map<String, Object> result = client.billInquiry("EVN", "PE0123456789");

            assertThat(result).containsEntry("status", "FOUND")
                    .containsEntry("amount", 1_250_000);
        }

        @Test
        @DisplayName("mã hoá đơn null vẫn gửi được, gửi chuỗi rỗng thay vì null")
        void maHoaDonNull() {
            server.expect(requestTo(INQUIRY_URL))
                    .andExpect(jsonPath("$.billCode").value(""))
                    .andRespond(withSuccess("{\"status\":\"NOT_FOUND\"}", MediaType.APPLICATION_JSON));

            assertThat(client.billInquiry("EVN", null)).containsEntry("status", "NOT_FOUND");
            server.verify();
        }

        @Test
        @DisplayName("gọi hỏng thì trả NOT_FOUND chứ không ném lỗi ngược lên")
        void goiHong() {
            server.expect(requestTo(INQUIRY_URL)).andRespond(withServerError());

            assertThat(client.billInquiry("EVN", "PE0123456789"))
                    .containsEntry("status", "NOT_FOUND");
        }
    }
}
