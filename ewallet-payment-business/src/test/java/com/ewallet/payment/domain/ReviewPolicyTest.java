package com.ewallet.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ewallet.payment.entity.LimitConfig;
import com.ewallet.payment.repo.LimitConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Ngưỡng rà soát thủ công — R-REVIEW-01. */
@ExtendWith(MockitoExtension.class)
class ReviewPolicyTest {

    private static final long DEFAULT_THRESHOLD = 20_000_000L;

    @Mock
    private LimitConfigRepository limitConfigRepository;

    @InjectMocks
    private ReviewPolicy reviewPolicy;

    @Test
    @DisplayName("không có REVIEW_THRESHOLD trong DB thì dùng ngưỡng mặc định 20.000.000đ")
    void nguongMacDinh() {
        when(limitConfigRepository.findById("REVIEW_THRESHOLD")).thenReturn(Optional.empty());

        assertThat(reviewPolicy.reviewThreshold()).isEqualTo(DEFAULT_THRESHOLD);
    }

    @Test
    @DisplayName("có REVIEW_THRESHOLD trong DB thì lấy theo DB")
    void nguongTheoDb() {
        LimitConfig c = new LimitConfig();
        c.setId("REVIEW_THRESHOLD");
        c.setLimitValue(5_000_000L);
        when(limitConfigRepository.findById("REVIEW_THRESHOLD")).thenReturn(Optional.of(c));

        assertThat(reviewPolicy.reviewThreshold()).isEqualTo(5_000_000L);
        assertThat(reviewPolicy.requiresManualReview(5_000_000L)).isTrue();
    }

    @Test
    @DisplayName("từ ngưỡng trở lên mới phải rà soát, dưới ngưỡng thì không")
    void bienNguongRaSoat() {
        when(limitConfigRepository.findById("REVIEW_THRESHOLD")).thenReturn(Optional.empty());

        assertThat(reviewPolicy.requiresManualReview(DEFAULT_THRESHOLD - 1)).isFalse();
        assertThat(reviewPolicy.requiresManualReview(DEFAULT_THRESHOLD)).isTrue();
        assertThat(reviewPolicy.requiresManualReview(DEFAULT_THRESHOLD + 1)).isTrue();
    }
}
