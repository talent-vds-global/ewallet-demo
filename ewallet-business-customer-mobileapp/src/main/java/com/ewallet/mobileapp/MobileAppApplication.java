package com.ewallet.mobileapp;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "ewallet-business-customer-mobileapp API", version = "0.1.0",
        description = "BFF cho app khách hàng — nơi khởi tạo giao dịch"))
public class MobileAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(MobileAppApplication.class, args);
    }

    @Bean
    RestClient orderRestClient(@org.springframework.beans.factory.annotation.Value(
            "${clients.order.base-url:http://ewallet-payment-order:8082}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
