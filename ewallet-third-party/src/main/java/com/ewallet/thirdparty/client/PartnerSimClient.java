package com.ewallet.thirdparty.client;

import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi ra đối tác thật (partner-sim trong demo). Đây là ranh giới ngoài cùng của hệ.
 *
 * <p>NFR-TIMEOUT-01: timeout 3000ms, cấu hình ở {@code partner.timeout-ms}.
 * Quá hạn thì trả {@link Result#timeout()} chứ không ném ra ngoài — bên trên cần biết
 * "đối tác không trả lời" khác với "đối tác từ chối".</p>
 */
@Component
public class PartnerSimClient {

    private static final Logger log = LoggerFactory.getLogger(PartnerSimClient.class);

    public record Result(String status, String reasonCode, String partnerRef, Map<String, Object> raw) {

        public static final String SUCCESS = "SUCCESS";
        public static final String DECLINED = "DECLINED";
        public static final String TIMEOUT = "TIMEOUT";

        public static Result timeout() {
            return new Result(TIMEOUT, "PARTNER_TIMEOUT", null, Map.of());
        }

        public static Result declined(String reasonCode) {
            return new Result(DECLINED, reasonCode == null ? "PARTNER_DECLINED" : reasonCode, null, Map.of());
        }

        public boolean isSuccess() { return SUCCESS.equals(status); }
    }

    private final RestClient partnerRestClient;

    public PartnerSimClient(RestClient partnerRestClient) {
        this.partnerRestClient = partnerRestClient;
    }

    public Result execute(String partnerCode, String orderId, long amount, String currency,
                          String accountRef, String billCode) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", orderId);
        request.put("amount", amount);
        request.put("currency", currency);
        request.put("accountRef", accountRef);
        request.put("billCode", billCode);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = partnerRestClient.post()
                    .uri("/partner/{code}/execute", partnerCode)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                return Result.declined("PARTNER_DECLINED");
            }
            String status = String.valueOf(body.getOrDefault("status", "DECLINED"));
            String reason = body.get("reasonCode") == null ? null : String.valueOf(body.get("reasonCode"));
            String ref = body.get("partnerRef") == null ? null : String.valueOf(body.get("partnerRef"));

            if (!Result.SUCCESS.equals(status)) {
                return Result.declined(reason);
            }
            return new Result(Result.SUCCESS, "OK", ref, body);

        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                log.warn("doi tac qua han orderId={} partnerCode={}", orderId, partnerCode);
                return Result.timeout();
            }
            log.warn("khong goi duoc doi tac orderId={} loi={}", orderId, e.toString());
            return Result.declined("PARTNER_UNREACHABLE");

        } catch (RestClientException e) {
            // Doc timeout thuong xay ra luc trich body, luc do RestClient boc thanh
            // RestClientException chu khong phai ResourceAccessException. Phai xet ca chuoi cause,
            // neu khong se bao nham "doi tac tu choi" trong khi thuc te la ho khong tra loi.
            if (isTimeout(e)) {
                log.warn("doi tac qua han khi doc phan hoi orderId={} partnerCode={}", orderId, partnerCode);
                return Result.timeout();
            }
            log.warn("doi tac tra loi orderId={} loi={}", orderId, e.toString());
            return Result.declined("PARTNER_DECLINED");
        }
    }

    /** Timeout co the nam sau vai lop boc, nen phai duyet het chuoi cause. */
    private static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Tra cứu hoá đơn — chỉ đọc, không ghi gì ở phía đối tác. */
    public Map<String, Object> billInquiry(String partnerCode, String billCode) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = partnerRestClient.post()
                    .uri("/partner/{code}/bill-inquiry", partnerCode)
                    .body(Map.of("billCode", billCode == null ? "" : billCode))
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of("status", "NOT_FOUND") : body;
        } catch (RestClientException e) {
            log.warn("tra cuu hoa don that bai partnerCode={} billCode={} loi={}",
                    partnerCode, billCode, e.toString());
            return Map.of("status", "NOT_FOUND");
        }
    }
}
