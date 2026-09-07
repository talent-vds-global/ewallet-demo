package com.ewallet.thirdparty.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * Stage B: chỉ khai báo bean client. Kết nối WebSocket bền tới partner-sim + HTTP adapter
 * cài ở Stage C (dùng PARTNER_SIM_WS_URL / PARTNER_SIM_BASE_URL).
 */
@Configuration
public class PartnerWsClientConfig {

    @Bean
    WebSocketClient partnerWebSocketClient() {
        return new StandardWebSocketClient();
    }

    @Bean
    RestClient partnerRestClient(@org.springframework.beans.factory.annotation.Value(
            "${partner-sim.base-url:http://partner-sim:8090}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
