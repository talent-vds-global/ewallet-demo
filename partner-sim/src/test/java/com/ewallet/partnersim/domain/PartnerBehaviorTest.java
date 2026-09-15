package com.ewallet.partnersim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Hành vi tất định của đối tác giả lập (docs/specs/00-domain-and-conventions.md §11.5).
 *
 * <p>Tính tất định là điều kiện để kịch bản demo F4 lặp lại được: cùng một số tiền
 * luôn cho cùng một kết quả, không phụ thuộc may rủi.</p>
 *
 * <p>Ca "treo quá hạn" (đuôi 888) ngủ 5 giây nên không đưa vào test — đặc tính đó
 * kiểm chứng bằng kịch bản chạy thật, xem {@code scripts/demo-flows.ps1 -Flow F4}.</p>
 */
class PartnerBehaviorTest {

    /** Không bơm nhiễu: chỉ còn hành vi tất định theo số tiền. */
    private final PartnerBehavior behavior = new PartnerBehavior(0L, 0.0, 0.0);

    @ParameterizedTest(name = "số tiền {0} -> từ chối")
    @ValueSource(longs = {999L, 1_999L, 50_999L, 1_000_999L})
    @DisplayName("số tiền có đuôi 999 thì đối tác luôn từ chối")
    void duoi999ThiTuChoi(long amount) {
        assertThat(behavior.decide(amount)).isEqualTo(PartnerBehavior.Outcome.DECLINED);
    }

    @ParameterizedTest(name = "số tiền {0} -> chấp nhận")
    @ValueSource(longs = {10_000L, 450_000L, 1_000_000L, 998L, 1_000L, 99_000L})
    @DisplayName("số tiền bình thường thì đối tác chấp nhận")
    void soTienThuongThiChapNhan(long amount) {
        assertThat(behavior.decide(amount)).isEqualTo(PartnerBehavior.Outcome.SUCCESS);
    }

    @Test
    @DisplayName("cùng một số tiền cho cùng một kết quả — kịch bản demo lặp lại được")
    void tatDinh() {
        for (int i = 0; i < 5; i++) {
            assertThat(behavior.decide(450_999L)).isEqualTo(PartnerBehavior.Outcome.DECLINED);
            assertThat(behavior.decide(450_000L)).isEqualTo(PartnerBehavior.Outcome.SUCCESS);
        }
    }

    @Test
    @DisplayName("bơm nhiễu tỉ lệ hỏng 100% thì mọi giao dịch thường đều bị từ chối")
    void bomNhieuHongToanBo() {
        PartnerBehavior noisy = new PartnerBehavior(0L, 1.0, 0.0);

        assertThat(noisy.decide(450_000L)).isEqualTo(PartnerBehavior.Outcome.DECLINED);
    }

    @Test
    @DisplayName("hành vi tất định theo số tiền được ưu tiên hơn nhiễu ngẫu nhiên")
    void tatDinhUuTienHonNhieu() {
        PartnerBehavior noisy = new PartnerBehavior(0L, 1.0, 0.0);

        // đuôi 999 vốn đã là từ chối, nhiễu không đổi được điều đó
        assertThat(noisy.decide(450_999L)).isEqualTo(PartnerBehavior.Outcome.DECLINED);
    }

    @Test
    @DisplayName("độ trễ thêm vào làm giao dịch chậm hơn nhưng không đổi kết quả")
    void themDoTre() {
        PartnerBehavior slow = new PartnerBehavior(100L, 0.0, 0.0);

        long started = System.currentTimeMillis();
        PartnerBehavior.Outcome outcome = slow.decide(450_000L);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(outcome).isEqualTo(PartnerBehavior.Outcome.SUCCESS);
        assertThat(elapsed).isGreaterThanOrEqualTo(100L);
    }
}
