package com.ewallet.payment.domain;

/** Kết quả tra cứu hoá đơn (F2 bước S0). status = FOUND | NOT_FOUND | ALREADY_PAID. */
public record BillInquiryResult(
        String status,
        String billCode,
        String customerName,
        String period,
        long amount,
        String currency,
        String billStatus) {

    public static final String FOUND = "FOUND";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ALREADY_PAID = "ALREADY_PAID";

    public static BillInquiryResult notFound(String billCode) {
        return new BillInquiryResult(NOT_FOUND, billCode, "", "", 0L, "VND", "");
    }
}
