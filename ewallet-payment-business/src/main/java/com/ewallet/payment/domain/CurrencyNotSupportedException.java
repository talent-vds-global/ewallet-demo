package com.ewallet.payment.domain;

/** Không có tỉ giá cho loại tiền yêu cầu — R-CURRENCY-01. */
public class CurrencyNotSupportedException extends RuntimeException {

    private final String currency;

    public CurrencyNotSupportedException(String currency) {
        super("Khong co ti gia cho currency=" + currency);
        this.currency = currency;
    }

    public String getCurrency() { return currency; }
}
