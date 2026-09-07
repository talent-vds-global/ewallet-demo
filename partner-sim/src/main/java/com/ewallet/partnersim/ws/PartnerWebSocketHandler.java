package com.ewallet.partnersim.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Stage B: echo + báo kết nối. Stage C: định kỳ đẩy "settlement" status cho các giao dịch đang chờ.
 */
@Component
public class PartnerWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PartnerWebSocketHandler.class);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("partner-sim ws connected id={}", session.getId());
        session.sendMessage(new TextMessage("{\"type\":\"WELCOME\"}"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("partner-sim ws recv id={} payload={}", session.getId(), message.getPayload());
        session.sendMessage(new TextMessage("{\"type\":\"ACK\"}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("partner-sim ws closed id={} status={}", session.getId(), status);
    }
}
