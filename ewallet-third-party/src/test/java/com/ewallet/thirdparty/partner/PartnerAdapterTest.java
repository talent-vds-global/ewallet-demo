package com.ewallet.thirdparty.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.thirdparty.client.PartnerSimClient;
import com.ewallet.thirdparty.entity.PartnerConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Hai adapter đối tác có kiểm tra riêng trước khi gọi ra ngoài (R-BILL-01, R-TELCO-01).
 *
 * <p>Adapter nạp tiền (F1) cố ý không có test — xem {@code docs/testing.md} §3.</p>
 */
@ExtendWith(MockitoExtension.class)
class PartnerAdapterTest {

    private static final String ORDER_ID = UUID.randomUUID().toString();

    @Mock
    private PartnerSimClient client;

    private static ExecuteCommand command(String accountRef, String billCode) {
        return new ExecuteCommand(ORDER_ID, "EVN", "BILL", 450_000L, "VND", accountRef, billCode);
    }

    private static PartnerSimClient.Result success() {
        return new PartnerSimClient.Result(PartnerSimClient.Result.SUCCESS, "OK", "PRT-1", Map.of());
    }

    @Nested
    @DisplayName("BillAdapter — thanh toán hoá đơn")
    class Bill {

        private BillAdapter adapter() {
            return new BillAdapter(client);
        }

        @Test
        @DisplayName("nhận đúng loại dịch vụ BILL")
        void dungLoaiDichVu() {
            assertThat(adapter().serviceType()).isEqualTo(PartnerConfig.BILL);
        }

        @Test
        @DisplayName("R-BILL-01: có mã hoá đơn thì lệnh hợp lệ")
        void coMaHoaDonThiHopLe() {
            assertThat(adapter().validate(command(null, "PD0123456"))).isNull();
        }

        @ParameterizedTest(name = "billCode = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("R-BILL-01: thiếu mã hoá đơn thì từ chối ngay, không gọi đối tác")
        void thieuMaHoaDon(String billCode) {
            assertThat(adapter().validate(command(null, billCode))).isEqualTo("MISSING_BILL_CODE");
        }

        @Test
        @DisplayName("gửi mã hoá đơn xuống đối tác, không gửi accountRef")
        void goiDoiTacVoiMaHoaDon() {
            when(client.execute(anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                    .thenReturn(success());

            PartnerSimClient.Result result = adapter().execute(command("0901234567", "PD0123456"));

            assertThat(result.isSuccess()).isTrue();
            verify(client).execute("EVN", ORDER_ID, 450_000L, "VND", null, "PD0123456");
        }
    }

    @Nested
    @DisplayName("TelcoAdapter — nạp tiền điện thoại")
    class Telco {

        private TelcoAdapter adapter() {
            return new TelcoAdapter(client);
        }

        @Test
        @DisplayName("nhận đúng loại dịch vụ TELCO")
        void dungLoaiDichVu() {
            assertThat(adapter().serviceType()).isEqualTo(PartnerConfig.TELCO);
        }

        @ParameterizedTest(name = "số thuê bao [{0}] hợp lệ")
        @ValueSource(strings = {"0901234567", "0987654321", "1234567890"})
        @DisplayName("R-TELCO-01: đúng 10 chữ số thì hợp lệ")
        void soThueBaoHopLe(String phone) {
            assertThat(adapter().validate(command(phone, null))).isNull();
        }

        @ParameterizedTest(name = "số thuê bao [{0}] không hợp lệ")
        @NullAndEmptySource
        @ValueSource(strings = {
                "090123456",        // 9 chữ số
                "09012345678",      // 11 chữ số
                "090123456a",       // có chữ
                "+84901234567",     // có dấu cộng
                "0901 234 567"      // có khoảng trắng
        })
        @DisplayName("R-TELCO-01: sai định dạng thì trả INVALID_PHONE_NUMBER")
        void soThueBaoKhongHopLe(String phone) {
            assertThat(adapter().validate(command(phone, null))).isEqualTo("INVALID_PHONE_NUMBER");
        }

        @Test
        @DisplayName("gửi số thuê bao xuống đối tác, không gửi mã hoá đơn")
        void goiDoiTacVoiSoThueBao() {
            when(client.execute(anyString(), anyString(), anyLong(), anyString(), anyString(), any()))
                    .thenReturn(success());

            adapter().execute(command("0901234567", null));

            verify(client).execute(eq("EVN"), eq(ORDER_ID), eq(450_000L), eq("VND"),
                    eq("0901234567"), eq(null));
        }
    }
}
