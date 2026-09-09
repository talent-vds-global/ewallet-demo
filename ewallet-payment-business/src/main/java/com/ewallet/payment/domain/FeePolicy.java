package com.ewallet.payment.domain;

import org.springframework.stereotype.Component;

/**
 * Biểu phí — R-FEE-01 đến R-FEE-05 (docs/specs/00-domain-and-conventions.md §8).
 * Phí luôn trừ vào ví nguồn và ghi thành bút toán riêng entry_type=FEE về tài khoản SYSTEM_FEE.
 */
@Component
public class FeePolicy {

    /** R-FEE-02: 0,5% giá trị hoá đơn, làm tròn lên bội số 1.000đ, sàn 1.000đ, trần 5.000đ. */
    private static final long BILL_FEE_RATE_PER_MILLE = 5L;   // 5/1000 = 0,5%
    private static final long BILL_FEE_MIN = 1_000L;
    private static final long BILL_FEE_MAX = 5_000L;

    /** R-FEE-04: chuyển tiền trên 2.000.000đ thu 2.200đ. */
    private static final long P2P_FREE_THRESHOLD = 2_000_000L;
    private static final long P2P_FEE = 2_200L;

    private static final long ROUNDING_UNIT = 1_000L;

    public long calculate(PaymentType paymentType, long amountVnd) {
        return switch (paymentType) {
            case TOP_UP -> 0L;                       // R-FEE-01 — đối tác chịu phí
            case TELCO -> 0L;                        // R-FEE-03 — khuyến mại
            case REFUND -> 0L;                       // R-FEE-05 — hoàn tiền không thu phí
            case BILL -> billFee(amountVnd);         // R-FEE-02
            case P2P -> amountVnd <= P2P_FREE_THRESHOLD ? 0L : P2P_FEE;  // R-FEE-04
        };
    }

    private long billFee(long amountVnd) {
        long raw = amountVnd * BILL_FEE_RATE_PER_MILLE / 1_000L;
        long rounded = roundUp(raw);
        return Math.min(Math.max(rounded, BILL_FEE_MIN), BILL_FEE_MAX);
    }

    /** Làm tròn lên bội số 1.000đ. */
    private long roundUp(long value) {
        if (value <= 0) {
            return 0L;
        }
        return ((value + ROUNDING_UNIT - 1) / ROUNDING_UNIT) * ROUNDING_UNIT;
    }
}
