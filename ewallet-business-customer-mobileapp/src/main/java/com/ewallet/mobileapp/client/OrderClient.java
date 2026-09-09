package com.ewallet.mobileapp.client;

import com.ewallet.mobileapp.dto.WalletDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * BFF gọi xuống ewallet-payment-order.
 *
 * <p>Trả nguyên status và body của payment-order thay vì bọc lại: mã trạng thái giao dịch
 * (200 COMPLETED / 202 HELD / 422 vi phạm rule / 502 đối tác lỗi / 504 timeout) là thông tin
 * nghiệp vụ, bọc lại sẽ làm mất nghĩa.</p>
 */
@Component
public class OrderClient {

    private static final Logger log = LoggerFactory.getLogger(OrderClient.class);

    private final RestClient orderRestClient;

    public OrderClient(RestClient orderRestClient) {
        this.orderRestClient = orderRestClient;
    }

    public ResponseEntity<String> createOrder(WalletDtos.CreateOrderCommand command, String idempotencyKey) {
        try {
            return orderRestClient.post()
                    .uri("/api/orders")
                    .header("X-Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    .onStatus(status -> true, (request, response) -> { })   // giữ nguyên mọi status
                    .toEntity(String.class);
        } catch (RestClientException e) {
            log.error("goi payment-order that bai type={}", command.paymentType(), e);
            return downstreamUnavailable();
        }
    }

    public ResponseEntity<String> get(String path) {
        try {
            return orderRestClient.get()
                    .uri(path)
                    .retrieve()
                    .onStatus(status -> true, (request, response) -> { })
                    .toEntity(String.class);
        } catch (RestClientException e) {
            log.error("goi payment-order that bai path={}", path, e);
            return downstreamUnavailable();
        }
    }

    private ResponseEntity<String> downstreamUnavailable() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"ORDER_SERVICE_UNAVAILABLE\","
                        + "\"message\":\"Khong goi duoc payment-order\",\"traceId\":null}");
    }
}
