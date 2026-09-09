package com.ewallet.payment.domain;

import com.ewallet.payment.repo.FxRateRepository;
import org.springframework.stereotype.Component;

/**
 * Quy đổi về VND trước khi áp hạn mức — R-CURRENCY-01, R-FX-01.
 * Mọi so sánh hạn mức và mọi bút toán đều dùng VND.
 */
@Component
public class CurrencyConverter {

    public static final String VND = "VND";

    private final FxRateRepository fxRateRepository;

    public CurrencyConverter(FxRateRepository fxRateRepository) {
        this.fxRateRepository = fxRateRepository;
    }

    /**
     * @throws CurrencyNotSupportedException khi không tìm được tỉ giá
     */
    public long toVnd(long amount, String currency) {
        String code = (currency == null || currency.isBlank()) ? VND : currency.trim().toUpperCase();
        if (VND.equals(code)) {
            return amount;
        }
        long rate = fxRateRepository.findById(code)
                .map(r -> r.getRateToVnd())
                .orElseThrow(() -> new CurrencyNotSupportedException(code));
        return amount * rate;
    }
}
