package com.ewallet.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** DTO của API HTTP — hợp đồng: docs/specs/01-api-contracts.md §4. */
public final class OrderDtos {

    /** POST /api/orders */
    public record CreateOrderRequest(
            @NotBlank String customerId,
            @NotBlank String paymentType,     // TOP_UP | BILL | TELCO | P2P
            @Min(1) long amount,
            String currency,                  // mặc định VND
            String partnerCode,
            String destCustomerId,            // chỉ P2P
            String billCode,                  // chỉ BILL
            String accountRef                 // số thẻ đối tác hoặc số thuê bao
    ) { }

    /** POST /api/orders/{id}/refund */
    public record RefundRequest(String reason) { }

    public record StepView(String stepName, String stepStatus, String detail,
                           int attempt, Long durationMs, String createdAt) { }

    public record OrderResponse(
            String orderId,
            String status,
            String reasonCode,
            String txnId,
            String customerId,
            String paymentType,
            long amount,
            long fee,
            String currency,
            Long amountVnd,
            String partnerCode,
            String partnerRef,
            String billCode,
            String destCustomerId,
            boolean refunded,
            String createdAt,
            List<StepView> steps
    ) { }

    public record HistoryResponse(String customerId, int count, List<OrderResponse> items) { }

    public record BillInquiryResponse(
            String status,          // FOUND | NOT_FOUND | ALREADY_PAID
            String partnerCode,
            String billCode,
            String customerName,
            String period,
            long amount,
            String currency,
            String billStatus
    ) { }

    public record ErrorResponse(String code, String message, String traceId) { }

    private OrderDtos() { }
}
