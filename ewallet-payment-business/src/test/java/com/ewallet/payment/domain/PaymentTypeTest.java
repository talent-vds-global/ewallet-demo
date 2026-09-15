package com.ewallet.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Loại giao dịch và hai câu hỏi dẫn hướng saga (docs/specs/00-domain-and-conventions.md §3). */
class PaymentTypeTest {

    @ParameterizedTest(name = "[{0}] -> {1}")
    @CsvSource({
            "BILL,   BILL",
            "bill,   BILL",
            " p2p ,  P2P",
            "Telco,  TELCO",
            "REFUND, REFUND"
    })
    @DisplayName("from() chấp nhận chữ thường và khoảng trắng thừa")
    void phanTichChuoiHopLe(String raw, String expected) {
        assertThat(PaymentType.from(raw)).isEqualTo(PaymentType.valueOf(expected));
    }

    @ParameterizedTest(name = "[{0}] -> null")
    @NullAndEmptySource
    @ValueSource(strings = {"KHONG_TON_TAI", "  ", "P2P2"})
    @DisplayName("from() trả null thay vì ném lỗi khi chuỗi không hợp lệ")
    void chuoiKhongHopLeTraNull(String raw) {
        assertThat(PaymentType.from(raw)).isNull();
    }

    @Test
    @DisplayName("R-P2P-02: chuyển tiền nội bộ không đi qua đối tác, hoá đơn và thẻ thì có")
    void loaiNaoCanDoiTac() {
        assertThat(PaymentType.BILL.requiresPartner()).isTrue();
        assertThat(PaymentType.TELCO.requiresPartner()).isTrue();
        assertThat(PaymentType.P2P.requiresPartner()).isFalse();
        assertThat(PaymentType.REFUND.requiresPartner()).isFalse();
    }

    @Test
    @DisplayName("R-BALANCE-01: chỉ giao dịch trừ tiền ví khách mới phải kiểm tra số dư")
    void loaiNaoTruViKhach() {
        assertThat(PaymentType.BILL.debitsCustomerWallet()).isTrue();
        assertThat(PaymentType.TELCO.debitsCustomerWallet()).isTrue();
        assertThat(PaymentType.P2P.debitsCustomerWallet()).isTrue();
        assertThat(PaymentType.REFUND.debitsCustomerWallet()).isFalse();
    }
}
