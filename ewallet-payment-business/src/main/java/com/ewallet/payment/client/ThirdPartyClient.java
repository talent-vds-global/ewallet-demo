package com.ewallet.payment.client;

import com.ewallet.payment.domain.BillInquiryResult;
import com.ewallet.payment.domain.PartnerExecutionResult;
import com.ewallet.payment.domain.ReasonCodes;
import java.net.SocketTimeoutException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Ranh giới ra hệ ngoài: payment-business gọi ewallet-third-party bằng HTTP,
 * third-party mới là nơi nói chuyện với đối tác thật (partner-sim trong demo).
 *
 * <p>Client này không ném lỗi ra ngoài — mọi sự cố đều quy về
 * {@link PartnerExecutionResult} với status DECLINED hoặc TIMEOUT, để orchestrator
 * (payment-order) tự quyết định có bù trừ hay không (R-COMP-01).</p>
 */
@Component
public class ThirdPartyClient {

    private static final Logger log = LoggerFactory.getLogger(ThirdPartyClient.class);

    private final RestClient thirdPartyRestClient;

    public ThirdPartyClient(RestClient thirdPartyRestClient) {
        this.thirdPartyRestClient = thirdPartyRestClient;
    }

    /** Bước saga S3 — gọi đối tác thực hiện giao dịch. */
    public PartnerExecutionResult execute(UUID orderId, String partnerCode, String paymentType,
                                          long amount, String currency, String accountRef, String billCode) {
        ThirdPartyDtos.ExecuteRequest request = new ThirdPartyDtos.ExecuteRequest(
                orderId.toString(), partnerCode, paymentType, amount, currency, accountRef, billCode);

        long start = System.currentTimeMillis();
        try {
            ThirdPartyDtos.ExecuteResponse response = thirdPartyRestClient.post()
                    .uri("/api/thirdparty/execute")
                    .body(request)
                    .retrieve()
                    .body(ThirdPartyDtos.ExecuteResponse.class);

            long elapsed = System.currentTimeMillis() - start;
            if (response == null) {
                log.warn("third-party tra body rong orderId={}", orderId);
                return new PartnerExecutionResult(PartnerExecutionResult.DECLINED,
                        ReasonCodes.PARTNER_DECLINED, "", elapsed);
            }

            // R-TOPUP-05: đối tác báo SUCCESS nhưng thiếu partnerRef thì coi như thất bại.
            if (PartnerExecutionResult.SUCCESS.equals(response.status())
                    && (response.partnerRef() == null || response.partnerRef().isBlank())) {
                log.warn("doi tac bao SUCCESS nhung thieu partnerRef orderId={}", orderId);
                return new PartnerExecutionResult(PartnerExecutionResult.DECLINED,
                        ReasonCodes.PARTNER_DECLINED, "", elapsed);
            }

            return new PartnerExecutionResult(
                    response.status(),
                    response.reasonCode() == null ? ReasonCodes.OK : response.reasonCode(),
                    response.partnerRef() == null ? "" : response.partnerRef(),
                    response.elapsedMs() > 0 ? response.elapsedMs() : elapsed);

        } catch (ResourceAccessException e) {
            long elapsed = System.currentTimeMillis() - start;
            boolean timeout = e.getCause() instanceof SocketTimeoutException;
            log.warn("goi third-party that bai orderId={} timeout={} loi={}", orderId, timeout, e.toString());
            return new PartnerExecutionResult(
                    timeout ? PartnerExecutionResult.TIMEOUT : PartnerExecutionResult.DECLINED,
                    timeout ? ReasonCodes.PARTNER_TIMEOUT : ReasonCodes.PARTNER_DECLINED,
                    "", elapsed);

        } catch (RestClientException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("third-party tra loi orderId={} loi={}", orderId, e.toString());
            return new PartnerExecutionResult(PartnerExecutionResult.DECLINED,
                    ReasonCodes.PARTNER_DECLINED, "", elapsed);
        }
    }

    /** Bước F2 S0 — tra cứu hoá đơn, chỉ đọc, không ghi sổ. */
    public BillInquiryResult inquireBill(String partnerCode, String billCode) {
        try {
            ThirdPartyDtos.BillInquiryResponse response = thirdPartyRestClient.post()
                    .uri("/api/thirdparty/bill-inquiry")
                    .body(new ThirdPartyDtos.BillInquiryRequest(partnerCode, billCode))
                    .retrieve()
                    .body(ThirdPartyDtos.BillInquiryResponse.class);

            if (response == null) {
                return BillInquiryResult.notFound(billCode);
            }
            return new BillInquiryResult(
                    response.status(),
                    response.billCode() == null ? billCode : response.billCode(),
                    response.customerName() == null ? "" : response.customerName(),
                    response.period() == null ? "" : response.period(),
                    response.amount(),
                    response.currency() == null ? "VND" : response.currency(),
                    response.billStatus() == null ? "" : response.billStatus());

        } catch (RestClientException e) {
            log.warn("tra cuu hoa don that bai partnerCode={} billCode={} loi={}",
                    partnerCode, billCode, e.toString());
            return BillInquiryResult.notFound(billCode);
        }
    }
}
