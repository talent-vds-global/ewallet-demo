package com.ewallet.thirdparty.partner;

/** Lệnh thực hiện giao dịch ở đối tác, đã chuẩn hoá từ request HTTP. */
public record ExecuteCommand(
        String orderId,
        String partnerCode,
        String paymentType,
        long amount,
        String currency,
        String accountRef,
        String billCode) { }
