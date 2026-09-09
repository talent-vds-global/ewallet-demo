package com.ewallet.thirdparty.web;

import com.ewallet.thirdparty.entity.PartnerTransaction;
import com.ewallet.thirdparty.partner.ExecuteCommand;
import com.ewallet.thirdparty.repo.PartnerConfigRepository;
import com.ewallet.thirdparty.service.PartnerExecutionService;
import com.ewallet.thirdparty.ws.PartnerWsClient;
import io.swagger.v3.oas.annotations.Operation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ranh giới ra hệ ngoài. Hợp đồng: docs/specs/01-api-contracts.md §7.
 * Chỉ payment-business gọi vào đây.
 */
@RestController
@RequestMapping("/api/thirdparty")
public class ThirdPartyController {

    /** Request từ payment-business. */
    public record ExecuteRequest(String orderId, String partnerCode, String paymentType,
                                 long amount, String currency, String accountRef, String billCode) { }

    public record BillInquiryRequest(String partnerCode, String billCode) { }

    private final PartnerExecutionService executionService;
    private final PartnerConfigRepository configRepository;
    private final PartnerWsClient wsClient;

    public ThirdPartyController(PartnerExecutionService executionService,
                                PartnerConfigRepository configRepository,
                                PartnerWsClient wsClient) {
        this.executionService = executionService;
        this.configRepository = configRepository;
        this.wsClient = wsClient;
    }

    @GetMapping("/ping")
    @Operation(summary = "Smoke test - service, thirdpartydb va ket noi WebSocket")
    public Map<String, Object> ping() {
        return Map.of(
                "service", "ewallet-third-party",
                "status", "UP",
                "partnerConfigRows", configRepository.count(),
                "websocketConnected", wsClient.isConnected());
    }

    @PostMapping("/execute")
    @Operation(summary = "Thuc hien giao dich qua doi tac")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody ExecuteRequest request) {
        if (request.orderId() == null || request.orderId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "DECLINED", "reasonCode", "MISSING_ORDER_ID",
                    "partnerRef", "", "elapsedMs", 0L));
        }
        try {
            UUID.fromString(request.orderId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "DECLINED", "reasonCode", "INVALID_ORDER_ID",
                    "partnerRef", "", "elapsedMs", 0L));
        }

        PartnerExecutionService.ExecutionOutcome outcome = executionService.execute(
                new ExecuteCommand(request.orderId(), request.partnerCode(), request.paymentType(),
                        request.amount(), request.currency(), request.accountRef(), request.billCode()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome.status());
        body.put("reasonCode", outcome.reasonCode());
        body.put("partnerRef", outcome.partnerRef() == null ? "" : outcome.partnerRef());
        body.put("elapsedMs", outcome.elapsedMs());
        // Luôn trả 200: kết quả nghiệp vụ nằm trong body, không phải trong mã HTTP.
        // payment-business phân biệt SUCCESS / DECLINED / TIMEOUT để quyết định bù trừ.
        return ResponseEntity.ok(body);
    }

    @PostMapping("/bill-inquiry")
    @Operation(summary = "Tra cuu hoa don qua doi tac")
    public ResponseEntity<Map<String, Object>> billInquiry(@RequestBody BillInquiryRequest request) {
        Map<String, Object> partnerResponse =
                executionService.billInquiry(request.partnerCode(), request.billCode());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", partnerResponse.getOrDefault("status", "NOT_FOUND"));
        body.put("billCode", partnerResponse.getOrDefault("billCode", request.billCode()));
        body.put("customerName", partnerResponse.get("customerName"));
        body.put("period", partnerResponse.get("period"));
        body.put("amount", partnerResponse.getOrDefault("amount", 0L));
        body.put("currency", partnerResponse.getOrDefault("currency", "VND"));
        body.put("billStatus", partnerResponse.get("billStatus"));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/transactions/{orderId}")
    @Operation(summary = "Giao dich doi tac cua mot don, kem thoi diem quyet toan")
    public ResponseEntity<Map<String, Object>> transactions(@PathVariable String orderId) {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST",
                    "message", "orderId khong hop le"));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (PartnerTransaction t : executionService.byOrder(id)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.getId().toString());
            item.put("partnerCode", t.getPartnerCode());
            item.put("serviceType", t.getServiceType());
            item.put("amount", t.getAmount());
            item.put("status", t.getStatus());
            item.put("partnerRef", t.getPartnerRef());
            item.put("billCode", t.getBillCode());
            item.put("attempt", t.getAttempt());
            item.put("failReason", t.getFailReason());
            item.put("createdAt", String.valueOf(t.getCreatedAt()));
            item.put("settledAt", String.valueOf(t.getSettledAt()));
            items.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("count", items.size());
        body.put("items", items);
        return ResponseEntity.ok(body);
    }
}
