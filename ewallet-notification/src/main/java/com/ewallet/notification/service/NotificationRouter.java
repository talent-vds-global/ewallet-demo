package com.ewallet.notification.service;

import com.ewallet.notification.entity.NotificationOutbox;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Chọn kênh và soạn nội dung thông báo — R-NOTIF-01, R-NOTIF-02, R-NOTIF-07.
 * Bảng định tuyến: docs/specs/F5-async-notification.md §2.4.
 */
@Component
public class NotificationRouter {

    /** Giao dịch từ mức này trở lên thì gửi thêm SMS. */
    private static final long SMS_THRESHOLD_VND = 10_000_000L;

    /** Một thông báo cần gửi: gửi cho ai, kênh nào, nội dung gì. */
    public record Target(String customerId, String channel, String message) { }

    private static final DecimalFormat MONEY =
            new DecimalFormat("#,###", new DecimalFormatSymbols(Locale.forLanguageTag("vi-VN")));

    /**
     * @param counterpartyCustomerId người nhận trong giao dịch P2P, null nếu không có
     */
    public List<Target> route(String eventType, String customerId, String counterpartyCustomerId,
                              long amount, long amountVnd, String reasonCode) {
        List<Target> targets = new ArrayList<>(3);
        String money = MONEY.format(amount) + "d";

        switch (eventType) {
            case "PaymentCompleted" -> {
                targets.add(new Target(customerId, NotificationOutbox.PUSH,
                        "Giao dich thanh cong " + money));
                if (amountVnd >= SMS_THRESHOLD_VND) {
                    targets.add(new Target(customerId, NotificationOutbox.SMS,
                            "Giao dich lon " + money + " da thuc hien thanh cong"));
                }
                // R-NOTIF-02: người nhận cũng phải được báo, với nội dung khác.
                if (counterpartyCustomerId != null && !counterpartyCustomerId.isBlank()) {
                    targets.add(new Target(counterpartyCustomerId, NotificationOutbox.PUSH,
                            "Ban da nhan " + money + " tu " + customerId));
                }
            }
            case "PaymentFailed" -> targets.add(new Target(customerId, NotificationOutbox.PUSH,
                    "Giao dich khong thanh cong. Ly do " + safe(reasonCode)));

            case "PaymentHeld" -> {
                targets.add(new Target(customerId, NotificationOutbox.PUSH,
                        "Giao dich " + money + " dang cho ra soat"));
                targets.add(new Target(customerId, NotificationOutbox.EMAIL,
                        "Giao dich " + money + " cua ban dang cho ra soat thu cong"));
            }
            case "PaymentRefunded" -> {
                targets.add(new Target(customerId, NotificationOutbox.PUSH,
                        "Giao dich that bai. Da hoan " + money + " vao vi"));
                targets.add(new Target(customerId, NotificationOutbox.SMS,
                        "Da hoan " + money + " vao vi cua ban"));
            }
            default -> { /* eventType lạ thì không gửi gì */ }
        }
        return targets;
    }

    private String safe(String reasonCode) {
        return (reasonCode == null || reasonCode.isBlank()) ? "KHONG RO" : reasonCode;
    }
}
