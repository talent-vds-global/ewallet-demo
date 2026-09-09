package com.ewallet.payment.domain;

import com.ewallet.payment.repo.LimitConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ngưỡng rà soát thủ công — R-REVIEW-01.
 * Giao dịch từ 20.000.000đ trở lên phải chuyển trạng thái HELD, giữ tiền và
 * (theo spec) publish event PaymentHeld.
 */
@Component
public class ReviewPolicy {

    private static final Logger log = LoggerFactory.getLogger(ReviewPolicy.class);

    private static final long DEFAULT_REVIEW_THRESHOLD = 20_000_000L;

    // ------------------------------------------------------------------------------------
    // LỖI CÓ CHỦ ĐÍCH #5 — VI PHẠM NFR. KHÔNG SỬA (hợp đồng nghiệm thu, architecture.md §6).
    //
    // NFR-LAT-01 yêu cầu gRPC AuthorizePayment có p95 < 500ms ở MỌI nhánh.
    // Bước "đối chiếu danh sách rà soát" dưới đây ngủ 700ms nên nhánh HELD luôn vượt ngưỡng.
    // Nền tảng phải phát hiện: SpanStat.p95 của nhánh HELD vs NFRConstraint.
    // ------------------------------------------------------------------------------------
    private static final long MANUAL_REVIEW_SCREENING_MS = 700L;

    private final LimitConfigRepository limitConfigRepository;

    public ReviewPolicy(LimitConfigRepository limitConfigRepository) {
        this.limitConfigRepository = limitConfigRepository;
    }

    public long reviewThreshold() {
        return limitConfigRepository.findById("REVIEW_THRESHOLD")
                .map(c -> c.getLimitValue())
                .orElse(DEFAULT_REVIEW_THRESHOLD);
    }

    /** R-REVIEW-01: từ ngưỡng trở lên thì phải treo chờ duyệt. */
    public boolean requiresManualReview(long amountVnd) {
        return amountVnd >= reviewThreshold();
    }

    /**
     * Đối chiếu giao dịch lớn với danh sách rà soát nội bộ.
     * Chứa lỗi có chủ đích #5: bước này chậm 700ms, vượt NFR-LAT-01 (&lt; 500ms).
     */
    public void runManualReviewScreening(String customerId, long amountVnd) {
        log.info("R-REVIEW-01 giao dich lon can ra soat customerId={} amountVnd={}", customerId, amountVnd);
        try {
            Thread.sleep(MANUAL_REVIEW_SCREENING_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
