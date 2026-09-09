package com.ewallet.payment.client;

/** DTO trao đổi với ewallet-third-party (docs/specs/01-api-contracts.md §7). */
public final class ThirdPartyDtos {

    public record ExecuteRequest(
            String orderId,
            String partnerCode,
            String paymentType,
            long amount,
            String currency,
            String accountRef,
            String billCode) { }

    public record ExecuteResponse(
            String status,        // SUCCESS | DECLINED | TIMEOUT
            String partnerRef,
            String reasonCode,
            long elapsedMs) { }

    public record BillInquiryRequest(
            String partnerCode,
            String billCode) { }

    public record BillInquiryResponse(
            String status,        // FOUND | NOT_FOUND | ALREADY_PAID
            String billCode,
            String customerName,
            String period,
            long amount,
            String currency,
            String billStatus) { }

    private ThirdPartyDtos() { }
}
