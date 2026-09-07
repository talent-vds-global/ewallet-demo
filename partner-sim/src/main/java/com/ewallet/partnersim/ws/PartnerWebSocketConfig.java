package com.ewallet.partnersim.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class PartnerWebSocketConfig implements WebSocketConfigurer {

    private final PartnerWebSocketHandler handler;

    public PartnerWebSocketConfig(PartnerWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/partner").setAllowedOrigins("*");
    }
}
