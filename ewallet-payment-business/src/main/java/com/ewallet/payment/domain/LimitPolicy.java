package com.ewallet.payment.domain;

import com.ewallet.payment.repo.LimitConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hạn mức giao dịch — R-AMOUNT-01, R-AMOUNT-02, R-LIMIT-01.
 * Spec: docs/specs/00-domain-and-conventions.md §8 và §9.
 */
@Component
public class LimitPolicy {

    private static final Logger log = LoggerFactory.getLogger(LimitPolicy.class);

    // ------------------------------------------------------------------------------------
    // LỖI CÓ CHỦ ĐÍCH #1 — SPEC DRIFT. KHÔNG SỬA (hợp đồng nghiệm thu, architecture.md §6).
    //
    // Spec R-LIMIT-01 và bảng limit_config đều ghi hạn mức ngày là 50.000.000đ.
    // Hằng số dưới đây ghi 100.000.000đ và được dùng thay cho giá trị trong DB.
    // Nền tảng phải phát hiện: rule (Doc Indexer) vs hằng số (Code Indexer) vs limit_config (DB).
    // ------------------------------------------------------------------------------------
    public static final long DAILY_TRANSFER_LIMIT = 100_000_000L;

    private final LimitConfigRepository limitConfigRepository;

    public LimitPolicy(LimitConfigRepository limitConfigRepository) {
        this.limitConfigRepository = limitConfigRepository;
    }

    /**
     * Kiểm tra số tiền một giao dịch. Trả về mã lý do nếu vi phạm, {@code null} nếu hợp lệ.
     *
     * @param amountVnd số tiền đã quy đổi về VND
     */
    public String checkAmount(PaymentType paymentType, long amountVnd) {
        long min = configured("MIN_TXN_AMOUNT", 10_000L);
        if (amountVnd < min) {
            log.debug("R-AMOUNT-01 vi pham amountVnd={} < min={}", amountVnd, min);
            return ReasonCodes.AMOUNT_TOO_SMALL;
        }
        long max = maxPerTransaction(paymentType);
        if (amountVnd > max) {
            log.debug("R-AMOUNT-02 vi pham amountVnd={} > max={} type={}", amountVnd, max, paymentType);
            return ReasonCodes.AMOUNT_TOO_LARGE;
        }
        return null;
    }

    /**
     * Kiểm tra hạn mức ngày — R-LIMIT-01.
     * Chứa lỗi có chủ đích #1: dùng hằng số trong code thay vì {@code limit_config}.
     */
    public String checkDailyLimit(String customerId, long usedTodayVnd, long amountVnd) {
        long limit = DAILY_TRANSFER_LIMIT;
        long total = usedTodayVnd + amountVnd;
        if (total > limit) {
            log.info("R-LIMIT-01 tu choi customerId={} usedToday={} amount={} limit={}",
                    customerId, usedTodayVnd, amountVnd, limit);
            return ReasonCodes.LIMIT_EXCEEDED;
        }
        return null;
    }

    public long maxPerTransaction(PaymentType paymentType) {
        return switch (paymentType) {
            case TOP_UP -> configured("MAX_TXN_TOP_UP", 50_000_000L);
            case BILL, TELCO -> configured("MAX_TXN_BILL", 50_000_000L);
            case P2P -> configured("MAX_TXN_P2P", 30_000_000L);
            case REFUND -> Long.MAX_VALUE;
        };
    }

    /** Giá trị hạn mức đang lưu trong DB — dùng cho endpoint admin và để đối chiếu spec drift. */
    public long configured(String key, long fallback) {
        return limitConfigRepository.findById(key)
                .map(c -> c.getLimitValue())
                .orElse(fallback);
    }
}
