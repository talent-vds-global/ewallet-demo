package com.ewallet.mobileapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO client-facing của BFF. Hợp đồng: docs/specs/01-api-contracts.md §3.
 *
 * <p>Khác với DTO của payment-order ở chỗ đây là ngôn ngữ của app khách hàng
 * (nạp tiền, chuyển tiền, hoá đơn) chứ không phải ngôn ngữ đơn hàng.</p>
 */
public final class WalletDtos {

    /** POST /api/wallet/topup — F1 */
    public record TopupRequest(
            @NotBlank String customerId,
            @Min(1) long amount,
            String currency,
            @NotBlank String partnerCode,
            String partnerAccountRef) { }

    /** POST /api/wallet/bill/pay — F2 */
    public record BillPaymentRequest(
            @NotBlank String customerId,
            @NotBlank String partnerCode,
            @NotBlank String billCode,
            @Min(1) long amount,
            String currency) { }

    /** POST /api/wallet/telco/topup — F2 biến thể TELCO */
    public record TelcoTopupRequest(
            @NotBlank String customerId,
            @NotBlank String partnerCode,
            @NotBlank String phoneNumber,
            @Min(1) long amount,
            String currency) { }

    /** POST /api/wallet/transfer — F3 */
    public record TransferRequest(
            @NotBlank String customerId,
            @NotBlank String destCustomerId,
            @Min(1) long amount,
            String currency,
            String message) { }

    /** Lệnh gửi xuống payment-order. */
    public record CreateOrderCommand(
            String customerId,
            String paymentType,
            long amount,
            String currency,
            String partnerCode,
            String destCustomerId,
            String billCode,
            String accountRef) { }

    public record ErrorResponse(String code, String message, String traceId) { }

    private WalletDtos() { }
}
