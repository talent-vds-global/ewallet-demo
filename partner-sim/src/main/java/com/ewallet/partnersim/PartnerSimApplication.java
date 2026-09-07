package com.ewallet.partnersim;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "partner-sim API", version = "0.1.0",
        description = "Test double giả lập đối tác ngoài"))
public class PartnerSimApplication {
    public static void main(String[] args) {
        SpringApplication.run(PartnerSimApplication.class, args);
    }
}
