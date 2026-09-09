package com.ewallet.payment.domain;

/** Mã lý do trả về cho payment-order (docs/specs/00-domain-and-conventions.md §6). */
public final class ReasonCodes {

    public static final String OK = "OK";
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String ACCOUNT_INACTIVE = "ACCOUNT_INACTIVE";
    public static final String AMOUNT_TOO_SMALL = "AMOUNT_TOO_SMALL";
    public static final String AMOUNT_TOO_LARGE = "AMOUNT_TOO_LARGE";
    public static final String LIMIT_EXCEEDED = "LIMIT_EXCEEDED";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String CURRENCY_NOT_SUPPORTED = "CURRENCY_NOT_SUPPORTED";
    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String MANUAL_REVIEW = "MANUAL_REVIEW";
    public static final String PARTNER_DECLINED = "PARTNER_DECLINED";
    public static final String PARTNER_TIMEOUT = "PARTNER_TIMEOUT";
    public static final String BILL_NOT_FOUND = "BILL_NOT_FOUND";
    public static final String BILL_ALREADY_PAID = "BILL_ALREADY_PAID";
    public static final String SELF_TRANSFER_NOT_ALLOWED = "SELF_TRANSFER_NOT_ALLOWED";
    public static final String INVALID_PAYMENT_TYPE = "INVALID_PAYMENT_TYPE";
    public static final String TXN_NOT_FOUND = "TXN_NOT_FOUND";
    public static final String INVALID_STATE = "INVALID_STATE";
    public static final String ALREADY_REVERSED = "ALREADY_REVERSED";

    private ReasonCodes() { }
}
