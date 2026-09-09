package com.ewallet.payment.domain;

/** Kết quả chốt sổ. status = CAPTURED | ERROR. */
public record ConfirmResult(String status, String reasonCode, long balanceAfter) {

    public static final String CAPTURED = "CAPTURED";
    public static final String ERROR = "ERROR";

    public static ConfirmResult error(String reasonCode) {
        return new ConfirmResult(ERROR, reasonCode, 0L);
    }
}
