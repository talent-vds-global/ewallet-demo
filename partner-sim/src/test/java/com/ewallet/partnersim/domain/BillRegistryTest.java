package com.ewallet.partnersim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Hoá đơn giả lập trong bộ nhớ (docs/specs/00-domain-and-conventions.md §11.4). */
class BillRegistryTest {

    private static final String BILL_UNPAID = "PE0123456789";
    private static final String BILL_PAID = "PE0999999999";

    private BillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BillRegistry();
    }

    @Nested
    @DisplayName("hoá đơn điện EVN")
    class Evn {

        @Test
        @DisplayName("hoá đơn chưa thanh toán tra được đủ thông tin")
        void hoaDonChuaThanhToan() {
            Optional<BillRegistry.Bill> bill = registry.lookup("EVN", BILL_UNPAID);

            assertThat(bill).isPresent();
            assertThat(bill.get().customerName()).isEqualTo("Nguyen Van A");
            assertThat(bill.get().period()).isEqualTo("2026-08");
            assertThat(bill.get().amount()).isEqualTo(1_250_000L);
            assertThat(bill.get().status()).isEqualTo(BillRegistry.UNPAID);
        }

        @Test
        @DisplayName("có sẵn một hoá đơn đã thanh toán để demo ca ALREADY_PAID")
        void hoaDonDaThanhToan() {
            assertThat(registry.lookup("EVN", BILL_PAID))
                    .get()
                    .extracting(BillRegistry.Bill::status)
                    .isEqualTo(BillRegistry.PAID);
        }

        @Test
        @DisplayName("mã hoá đơn không có trong danh sách thì không tra ra")
        void hoaDonKhongTonTai() {
            assertThat(registry.lookup("EVN", "PE0000000000")).isEmpty();
        }

        @Test
        @DisplayName("đánh dấu đã thanh toán thì lần tra sau thấy trạng thái PAID")
        void danhDauDaThanhToan() {
            registry.markPaid(BILL_UNPAID);

            assertThat(registry.lookup("EVN", BILL_UNPAID))
                    .get()
                    .extracting(BillRegistry.Bill::status)
                    .isEqualTo(BillRegistry.PAID);
        }

        @Test
        @DisplayName("đánh dấu thanh toán giữ nguyên các thông tin khác của hoá đơn")
        void danhDauGiuNguyenThongTin() {
            registry.markPaid(BILL_UNPAID);

            BillRegistry.Bill bill = registry.lookup("EVN", BILL_UNPAID).orElseThrow();
            assertThat(bill.customerName()).isEqualTo("Nguyen Van A");
            assertThat(bill.amount()).isEqualTo(1_250_000L);
        }

        @Test
        @DisplayName("đánh dấu mã không tồn tại thì không tạo ra hoá đơn mới")
        void danhDauMaKhongTonTai() {
            registry.markPaid("PE0000000000");

            assertThat(registry.lookup("EVN", "PE0000000000")).isEmpty();
        }
    }

    @Nested
    @DisplayName("nạp thẻ điện thoại VTELCO")
    class Vtelco {

        @Test
        @DisplayName("số thuê bao 10 chữ số nào cũng hợp lệ vì nạp thẻ không có hoá đơn để tra")
        void soThueBaoHopLe() {
            Optional<BillRegistry.Bill> bill = registry.lookup("VTELCO", "0901234567");

            assertThat(bill).isPresent();
            assertThat(bill.get().billCode()).isEqualTo("0901234567");
            assertThat(bill.get().customerName()).contains("0901234567");
            assertThat(bill.get().amount()).isZero();
            assertThat(bill.get().status()).isEqualTo(BillRegistry.UNPAID);
        }

        @Test
        @DisplayName("mã đối tác không phân biệt hoa thường")
        void khongPhanBietHoaThuong() {
            assertThat(registry.lookup("vtelco", "0901234567")).isPresent();
        }

        @ParameterizedTest(name = "số thuê bao [{0}]")
        @ValueSource(strings = {"090123456", "09012345678", "090123456a", "PE0123456789"})
        @DisplayName("sai định dạng số thuê bao thì không tra ra")
        void soThueBaoSaiDinhDang(String billCode) {
            assertThat(registry.lookup("VTELCO", billCode)).isEmpty();
        }
    }

    @ParameterizedTest(name = "mã hoá đơn [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("thiếu mã hoá đơn thì không tra được, kể cả với đối tác nào")
    void thieuMaHoaDon(String billCode) {
        assertThat(registry.lookup("EVN", billCode)).isEmpty();
        assertThat(registry.lookup("VTELCO", billCode)).isEmpty();
    }
}
