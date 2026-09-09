package com.ewallet.partnersim.domain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Hoá đơn giả lập, giữ trong bộ nhớ (docs/specs/00-domain-and-conventions.md §11.4).
 * partner-sim là test double nên không có database.
 */
@Component
public class BillRegistry {

    public record Bill(String billCode, String customerName, String period,
                       long amount, String status) { }

    public static final String UNPAID = "UNPAID";
    public static final String PAID = "PAID";

    private final Map<String, Bill> evnBills = new ConcurrentHashMap<>();

    public BillRegistry() {
        evnBills.put("PE0123456789",
                new Bill("PE0123456789", "Nguyen Van A", "2026-08", 1_250_000L, UNPAID));
        evnBills.put("PE0999999999",
                new Bill("PE0999999999", "Tran Thi B", "2026-08", 850_000L, PAID));
    }

    /**
     * Tra cứu theo đối tác.
     * EVN dùng danh sách hoá đơn cố định; VTELCO thì số thuê bao 10 chữ số nào cũng hợp lệ
     * vì nạp điện thoại không có "hoá đơn" để tra.
     */
    public Optional<Bill> lookup(String partnerCode, String billCode) {
        if (billCode == null || billCode.isBlank()) {
            return Optional.empty();
        }
        if ("VTELCO".equalsIgnoreCase(partnerCode)) {
            if (!billCode.matches("\\d{10}")) {
                return Optional.empty();
            }
            return Optional.of(new Bill(billCode, "Thue bao " + billCode, "-", 0L, UNPAID));
        }
        return Optional.ofNullable(evnBills.get(billCode));
    }

    /** Đánh dấu đã thanh toán để lần tra cứu sau trả ALREADY_PAID. */
    public void markPaid(String billCode) {
        evnBills.computeIfPresent(billCode, (code, bill) ->
                new Bill(bill.billCode(), bill.customerName(), bill.period(), bill.amount(), PAID));
    }
}
