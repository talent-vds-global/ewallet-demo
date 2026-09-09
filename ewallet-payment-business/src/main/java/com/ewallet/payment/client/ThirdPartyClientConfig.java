package com.ewallet.payment.client;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient đi ra ewallet-third-party.
 * Timeout ở đây rộng hơn timeout third-party gọi đối tác (NFR-TIMEOUT-01 = 3000ms)
 * để lỗi PARTNER_TIMEOUT được third-party phân loại chứ không bị cắt sớm ở đây.
 */
@Configuration
public class ThirdPartyClientConfig {

    @Bean
    public RestClient thirdPartyRestClient(
            @Value("${thirdparty.base-url}") String baseUrl,
            @Value("${thirdparty.timeout-ms:5000}") long timeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
