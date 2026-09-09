package com.ewallet.thirdparty.ws;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/** Hai kênh ra đối tác: HTTP cho lệnh, WebSocket cho xác nhận quyết toán. */
@Configuration
public class PartnerWsClientConfig {

    @Bean
    WebSocketClient partnerWebSocketClient() {
        return new StandardWebSocketClient();
    }

    /**
     * NFR-TIMEOUT-01: chờ đối tác tối đa 3000ms.
     * Quá hạn thì {@code PartnerSimClient} đổi thành TIMEOUT để phân biệt với bị từ chối.
     */
    @Bean
    RestClient partnerRestClient(@Value("${partner-sim.base-url:http://partner-sim:8090}") String baseUrl,
                                 @Value("${partner.timeout-ms:3000}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
