package com.ewallet.payment.domain;

import java.util.UUID;

/** Kết quả bù trừ. status = REVERSED | ERROR. */
public record ReverseResult(String status, String reasonCode, UUID refundTxnId, long balanceAfter) {

    public static final String REVERSED = "REVERSED";
    public static final String ERROR = "ERROR";

    public static ReverseResult error(String reasonCode) {
        return new ReverseResult(ERROR, reasonCode, null, 0L);
    }
}
