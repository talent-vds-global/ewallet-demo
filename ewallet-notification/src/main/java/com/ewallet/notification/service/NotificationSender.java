package com.ewallet.notification.service;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Giả lập việc gửi ra nhà cung cấp push/SMS/email. Demo không tích hợp nhà cung cấp thật.
 *
 * <p>Khách hàng cấu hình ở {@code notification.fail-customer} sẽ luôn gửi hỏng — đây là
 * <b>công tắc để demo retry và dead letter</b> (F5 §3.3), KHÔNG phải một trong sáu lỗi
 * có chủ đích.</p>
 */
@Component
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    /** Lỗi khi gửi — bên gọi bắt để quyết định thử lại hay đẩy sang DLT. */
    public static class SendFailedException extends RuntimeException {
        public SendFailedException(String message) {
            super(message);
        }
    }

    private final String failCustomer;

    public NotificationSender(@Value("${notification.fail-customer:}") String failCustomer) {
        this.failCustomer = failCustomer == null ? "" : failCustomer.trim();
        if (!this.failCustomer.isEmpty()) {
            log.info("cau hinh demo: luon gui hong cho customerId={}", this.failCustomer);
        }
    }

    public void send(String customerId, String channel, String message) {
        if (!failCustomer.isEmpty() && failCustomer.equalsIgnoreCase(customerId)) {
            throw new SendFailedException(
                    "nha cung cap tu choi cho customerId=" + customerId + " channel=" + channel);
        }

        // Gửi thật sẽ có độ trễ mạng; giữ lại một chút để trace không bị phẳng.
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(5, 25));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("da gui {} toi {}: {}", channel, customerId, message);
    }
}
