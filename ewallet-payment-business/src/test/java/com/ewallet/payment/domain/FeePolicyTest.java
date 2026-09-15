package com.ewallet.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Biểu phí R-FEE-01..R-FEE-05 (docs/specs/00-domain-and-conventions.md §8).
 *
 * <p>FeePolicy không phụ thuộc gì bên ngoài nên test thuần, không mock.</p>
 */
class FeePolicyTest {

    private final FeePolicy feePolicy = new FeePolicy();

    @Test
    @DisplayName("R-FEE-03: nạp thẻ điện thoại đang khuyến mại nên miễn phí")
    void telcoMienPhi() {
        assertThat(feePolicy.calculate(PaymentType.TELCO, 50_000L)).isZero();
        assertThat(feePolicy.calculate(PaymentType.TELCO, 5_000_000L)).isZero();
    }

    @Test
    @DisplayName("R-FEE-05: hoàn tiền không thu phí")
    void hoanTienMienPhi() {
        assertThat(feePolicy.calculate(PaymentType.REFUND, 1_000_000L)).isZero();
    }

    @ParameterizedTest(name = "hoá đơn {0}đ thu phí {1}đ")
    @CsvSource({
            // 0,5% làm tròn lên bội số 1.000đ, sàn 1.000đ, trần 5.000đ
            "10000, 1000",      // 50đ  -> làm tròn 1.000 -> chạm sàn
            "100000, 1000",     // 500đ -> làm tròn 1.000
            "200000, 1000",     // 1.000đ đúng bội số, không phải làm tròn
            "300000, 2000",     // 1.500đ -> làm tròn 2.000
            "500000, 3000",     // 2.500đ -> làm tròn 3.000
            "1000000, 5000",    // 5.000đ đúng trần
            "2000000, 5000",    // 10.000đ -> chạm trần
            "45000000, 5000"    // giá trị lớn vẫn bị trần chặn
    })
    @DisplayName("R-FEE-02: phí hoá đơn 0,5% có làm tròn, sàn và trần")
    void phiHoaDon(long amountVnd, long expectedFee) {
        assertThat(feePolicy.calculate(PaymentType.BILL, amountVnd)).isEqualTo(expectedFee);
    }

    @ParameterizedTest(name = "chuyển {0}đ thu phí {1}đ")
    @CsvSource({
            "10000, 0",
            "1999999, 0",
            "2000000, 0",       // đúng ngưỡng vẫn miễn phí (so sánh <=)
            "2000001, 2200",    // vượt 1 đồng là thu phí
            "30000000, 2200"
    })
    @DisplayName("R-FEE-04: chuyển tiền trên 2.000.000đ mới thu 2.200đ")
    void phiChuyenTien(long amountVnd, long expectedFee) {
        assertThat(feePolicy.calculate(PaymentType.P2P, amountVnd)).isEqualTo(expectedFee);
    }
}
