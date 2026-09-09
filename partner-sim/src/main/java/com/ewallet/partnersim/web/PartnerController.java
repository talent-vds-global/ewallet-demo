package com.ewallet.partnersim.web;

import com.ewallet.partnersim.domain.BillRegistry;
import com.ewallet.partnersim.domain.PartnerBehavior;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đối tác giả lập (test double, không phải service demo).
 * Hợp đồng: docs/specs/01-api-contracts.md §8.
 */
@RestController
@RequestMapping("/partner")
public class PartnerController {

    private static final Logger log = LoggerFactory.getLogger(PartnerController.class);

    private final PartnerBehavior behavior;
    private final BillRegistry bills;

    public PartnerController(PartnerBehavior behavior, BillRegistry bills) {
        this.behavior = behavior;
        this.bills = bills;
    }

    @PostMapping("/{code}/execute")
    @Operation(summary = "Doi tac thuc hien giao dich")
    public Map<String, Object> execute(@PathVariable String code,
                                       @RequestBody(required = false) Map<String, Object> body) {
        long amount = longOf(body, "amount");
        String orderId = stringOf(body, "orderId");
        String billCode = stringOf(body, "billCode");

        log.info("partner-sim execute code={} orderId={} amount={}", code, orderId, amount);

        PartnerBehavior.Outcome outcome = behavior.decide(amount);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("partnerCode", code);
        response.put("orderId", orderId);

        if (outcome == PartnerBehavior.Outcome.DECLINED) {
            response.put("status", "DECLINED");
            response.put("reasonCode", "PARTNER_DECLINED");
            response.put("partnerRef", null);
            response.put("settledAt", null);
            return response;
        }

        if (billCode != null && !billCode.isBlank()) {
            bills.markPaid(billCode);
        }

        response.put("status", "SUCCESS");
        response.put("reasonCode", "OK");
        response.put("partnerRef", "PS-" + UUID.randomUUID().toString().substring(0, 8));
        // Quyet toan chua xong ngay - se day ve qua WebSocket sau vai tram ms.
        response.put("settledAt", null);
        return response;
    }

    @PostMapping("/{code}/bill-inquiry")
    @Operation(summary = "Tra cuu hoa don")
    public Map<String, Object> billInquiry(@PathVariable String code,
                                           @RequestBody(required = false) Map<String, Object> body) {
        String billCode = stringOf(body, "billCode");
        log.info("partner-sim bill-inquiry code={} billCode={}", code, billCode);

        Optional<BillRegistry.Bill> found = bills.lookup(code, billCode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("partnerCode", code);
        response.put("billCode", billCode);

        if (found.isEmpty()) {
            response.put("status", "NOT_FOUND");
            response.put("customerName", null);
            response.put("period", null);
            response.put("amount", 0L);
            response.put("currency", "VND");
            response.put("billStatus", null);
            return response;
        }

        BillRegistry.Bill bill = found.get();
        response.put("status", BillRegistry.PAID.equals(bill.status()) ? "ALREADY_PAID" : "FOUND");
        response.put("customerName", bill.customerName());
        response.put("period", bill.period());
        response.put("amount", bill.amount());
        response.put("currency", "VND");
        response.put("billStatus", bill.status());
        response.put("inquiredAt", Instant.now().toString());
        return response;
    }

    private static String stringOf(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long longOf(Map<String, Object> body, String key) {
        if (body == null) {
            return 0L;
        }
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
