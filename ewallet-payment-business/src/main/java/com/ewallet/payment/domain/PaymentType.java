package com.ewallet.payment.domain;

/** Loại giao dịch (docs/specs/00-domain-and-conventions.md §3). */
public enum PaymentType {
    TOP_UP,
    BILL,
    TELCO,
    P2P,
    REFUND;

    public static PaymentType from(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PaymentType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** TOP_UP / BILL / TELCO đi qua đối tác; P2P thì không (R-P2P-02). */
    public boolean requiresPartner() {
        return this == TOP_UP || this == BILL || this == TELCO;
    }

    /** Tiền ra khỏi ví khách nên phải kiểm tra số dư (R-BALANCE-01). TOP_UP thì không (R-TOPUP-01). */
    public boolean debitsCustomerWallet() {
        return this == BILL || this == TELCO || this == P2P;
    }
}
