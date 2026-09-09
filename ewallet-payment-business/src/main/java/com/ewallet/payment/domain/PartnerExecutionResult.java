package com.ewallet.payment.domain;

/** Kết quả gọi đối tác qua third-party. status = SUCCESS | DECLINED | TIMEOUT. */
public record PartnerExecutionResult(
        String status,
        String reasonCode,
        String partnerRef,
        long elapsedMs) {

    public static final String SUCCESS = "SUCCESS";
    public static final String DECLINED = "DECLINED";
    public static final String TIMEOUT = "TIMEOUT";

    public boolean isSuccess() { return SUCCESS.equals(status); }
}
