package com.ewallet.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.ewallet.payment.entity.LimitConfig;
import com.ewallet.payment.repo.LimitConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Hạn mức giao dịch R-AMOUNT-01, R-AMOUNT-02, R-LIMIT-01.
 *
 * <p>Khi bảng {@code limit_config} không có bản ghi thì policy dùng giá trị mặc định
 * biên dịch sẵn — hầu hết ca dưới đây chạy ở nhánh mặc định đó.</p>
 */
@ExtendWith(MockitoExtension.class)
class LimitPolicyTest {

    @Mock
    private LimitConfigRepository limitConfigRepository;

    private LimitPolicy limitPolicy;

    @BeforeEach
    void setUp() {
        // Mặc định: không có cấu hình trong DB -> rơi về hằng số mặc định.
        lenient().when(limitConfigRepository.findById(anyString())).thenReturn(Optional.empty());
        limitPolicy = new LimitPolicy(limitConfigRepository);
    }

    private static LimitConfig config(String id, long value) {
        LimitConfig c = new LimitConfig();
        c.setId(id);
        c.setLimitValue(value);
        c.setCurrency("VND");
        return c;
    }

    @Nested
    @DisplayName("checkAmount — số tiền một giao dịch")
    class CheckAmount {

        @ParameterizedTest(name = "{0} {1}đ -> {2}")
        @CsvSource({
                // R-AMOUNT-01: dưới 10.000đ là quá nhỏ
                "P2P,    9999,      AMOUNT_TOO_SMALL",
                "BILL,   1,         AMOUNT_TOO_SMALL",
                "P2P,    10000,     OK",
                // R-AMOUNT-02: trần theo từng loại giao dịch
                "P2P,    30000000,  OK",
                "P2P,    30000001,  AMOUNT_TOO_LARGE",
                "BILL,   50000000,  OK",
                "BILL,   50000001,  AMOUNT_TOO_LARGE",
                "TELCO,  50000000,  OK"
        })
        void kiemTraTranSan(String type, long amountVnd, String expected) {
            String violation = limitPolicy.checkAmount(PaymentType.valueOf(type), amountVnd);
            assertThat(violation == null ? "OK" : violation).isEqualTo(expected);
        }

        @Test
        @DisplayName("hoàn tiền không bị chặn trần vì là tiền trả lại khách")
        void hoanTienKhongCoTran() {
            assertThat(limitPolicy.checkAmount(PaymentType.REFUND, 900_000_000L)).isNull();
            assertThat(limitPolicy.maxPerTransaction(PaymentType.REFUND)).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("giá trị trong limit_config được ưu tiên hơn hằng số mặc định")
        void uuTienGiaTriTrongDb() {
            when(limitConfigRepository.findById("MIN_TXN_AMOUNT"))
                    .thenReturn(Optional.of(config("MIN_TXN_AMOUNT", 20_000L)));

            assertThat(limitPolicy.checkAmount(PaymentType.P2P, 15_000L))
                    .isEqualTo(ReasonCodes.AMOUNT_TOO_SMALL);
            assertThat(limitPolicy.checkAmount(PaymentType.P2P, 20_000L)).isNull();
        }
    }

    @Nested
    @DisplayName("checkDailyLimit — hạn mức ngày R-LIMIT-01")
    class CheckDailyLimit {

        @Test
        @DisplayName("tổng phát sinh trong ngày còn dưới hạn mức thì cho qua")
        void duoiHanMucThiChoQua() {
            assertThat(limitPolicy.checkDailyLimit("CUST-001", 0L, 60_000_000L)).isNull();
            assertThat(limitPolicy.checkDailyLimit("CUST-001", 20_000_000L, 30_000_000L)).isNull();
        }

        @Test
        @DisplayName("vượt hạn mức ngày thì trả LIMIT_EXCEEDED")
        void vuotHanMucThiTuChoi() {
            assertThat(limitPolicy.checkDailyLimit("CUST-003", 50_000_000L, 60_000_000L))
                    .isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("đúng bằng hạn mức thì vẫn cho qua, vượt 1 đồng thì chặn")
        void bienHanMuc() {
            long limit = LimitPolicy.DAILY_TRANSFER_LIMIT;
            assertThat(limitPolicy.checkDailyLimit("CUST-003", 0L, limit)).isNull();
            assertThat(limitPolicy.checkDailyLimit("CUST-003", 0L, limit + 1))
                    .isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("hạn mức đã dùng cộng dồn với số tiền của giao dịch đang xét")
        void congDonVoiPhanDaDung() {
            long limit = LimitPolicy.DAILY_TRANSFER_LIMIT;
            assertThat(limitPolicy.checkDailyLimit("CUST-003", limit - 1_000L, 1_000L)).isNull();
            assertThat(limitPolicy.checkDailyLimit("CUST-003", limit - 1_000L, 1_001L))
                    .isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("configured — đọc cấu hình hạn mức")
    class Configured {

        @Test
        @DisplayName("không có bản ghi thì trả giá trị dự phòng truyền vào")
        void khongCoBanGhiThiDuPhong() {
            assertThat(limitPolicy.configured("KHONG_TON_TAI", 123L)).isEqualTo(123L);
        }

        @Test
        @DisplayName("có bản ghi thì trả giá trị của bản ghi")
        void coBanGhiThiTraBanGhi() {
            when(limitConfigRepository.findById("MAX_TXN_P2P"))
                    .thenReturn(Optional.of(config("MAX_TXN_P2P", 7_000_000L)));
            assertThat(limitPolicy.configured("MAX_TXN_P2P", 30_000_000L)).isEqualTo(7_000_000L);
            assertThat(limitPolicy.maxPerTransaction(PaymentType.P2P)).isEqualTo(7_000_000L);
        }
    }
}
