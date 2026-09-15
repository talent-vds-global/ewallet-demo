package com.ewallet.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.payment.entity.FxRate;
import com.ewallet.payment.repo.FxRateRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Quy đổi ngoại tệ về VND trước khi áp hạn mức — R-CURRENCY-01, R-FX-01. */
@ExtendWith(MockitoExtension.class)
class CurrencyConverterTest {

    @Mock
    private FxRateRepository fxRateRepository;

    @InjectMocks
    private CurrencyConverter currencyConverter;

    private static FxRate rate(String code, long toVnd) {
        FxRate r = new FxRate();
        r.setCurrency(code);
        r.setRateToVnd(toVnd);
        return r;
    }

    @ParameterizedTest(name = "currency = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"VND", "vnd", "  VND  ", "   "})
    @DisplayName("VND (kể cả rỗng hoặc null) giữ nguyên số tiền, không tra bảng tỉ giá")
    void vndGiuNguyen(String currency) {
        assertThat(currencyConverter.toVnd(150_000L, currency)).isEqualTo(150_000L);
        verify(fxRateRepository, never()).findById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("ngoại tệ được nhân với tỉ giá trong bảng fx_rates")
    void ngoaiTeNhanTiGia() {
        when(fxRateRepository.findById("USD")).thenReturn(Optional.of(rate("USD", 25_000L)));

        assertThat(currencyConverter.toVnd(100L, "USD")).isEqualTo(2_500_000L);
    }

    @ParameterizedTest(name = "chuẩn hoá [{0}] thành USD")
    @ValueSource(strings = {"usd", "Usd", " usd ", "USD"})
    @DisplayName("mã tiền tệ được chuẩn hoá về chữ hoa và cắt khoảng trắng")
    void chuanHoaMaTienTe(String raw) {
        when(fxRateRepository.findById("USD")).thenReturn(Optional.of(rate("USD", 25_000L)));

        assertThat(currencyConverter.toVnd(2L, raw)).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("không có tỉ giá thì ném CurrencyNotSupportedException kèm mã tiền tệ")
    void khongCoTiGiaThiNem() {
        when(fxRateRepository.findById("JPY")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currencyConverter.toVnd(1_000L, "JPY"))
                .isInstanceOf(CurrencyNotSupportedException.class)
                .hasMessageContaining("JPY");
    }
}
