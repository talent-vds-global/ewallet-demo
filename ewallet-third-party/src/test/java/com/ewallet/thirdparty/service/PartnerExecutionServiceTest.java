package com.ewallet.thirdparty.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.thirdparty.client.PartnerSimClient;
import com.ewallet.thirdparty.entity.PartnerConfig;
import com.ewallet.thirdparty.entity.PartnerTransaction;
import com.ewallet.thirdparty.partner.BillAdapter;
import com.ewallet.thirdparty.partner.ExecuteCommand;
import com.ewallet.thirdparty.partner.TelcoAdapter;
import com.ewallet.thirdparty.repo.PartnerConfigRepository;
import com.ewallet.thirdparty.repo.PartnerTransactionRepository;
import com.ewallet.thirdparty.ws.PartnerWsClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Điều phối một lệnh ra đối tác: chọn adapter theo {@code service_type}, ghi dấu vết,
 * gọi, phân loại kết quả thành SUCCESS / DECLINED / TIMEOUT.
 *
 * <p>Chỉ nạp adapter hoá đơn và thẻ điện thoại — adapter nạp tiền (F1) nằm ngoài
 * phạm vi test, xem {@code docs/testing.md} §3.</p>
 */
@ExtendWith(MockitoExtension.class)
class PartnerExecutionServiceTest {

    private static final String ORDER_ID = "33333333-3333-4333-8333-333333333333";

    @Mock private PartnerConfigRepository configRepository;
    @Mock private PartnerTransactionRepository transactionRepository;
    @Mock private PartnerSimClient partnerSimClient;
    @Mock private PartnerWsClient wsClient;

    private PartnerExecutionService service;

    @BeforeEach
    void setUp() {
        service = newService(2);
    }

    /** Dựng service với đúng hai adapter hoá đơn và thẻ điện thoại. */
    private PartnerExecutionService newService(int maxAttempts) {
        return new PartnerExecutionService(
                configRepository, transactionRepository, partnerSimClient, wsClient,
                List.of(new BillAdapter(partnerSimClient), new TelcoAdapter(partnerSimClient)),
                maxAttempts);
    }

    private static PartnerConfig config(String serviceType, boolean enabled) {
        PartnerConfig c = new PartnerConfig();
        c.setPartnerCode("EVN");
        c.setPartnerName("Dien luc");
        c.setServiceType(serviceType);
        c.setBaseUrl("http://partner-sim:8090");
        c.setEnabled(enabled);
        return c;
    }

    private static ExecuteCommand billCommand() {
        return new ExecuteCommand(ORDER_ID, "EVN", "BILL", 450_000L, "VND", null, "PD0123456");
    }

    private void partnerIs(String serviceType, boolean enabled) {
        when(configRepository.findById("EVN")).thenReturn(Optional.of(config(serviceType, enabled)));
    }

    private void partnerAnswers(PartnerSimClient.Result... results) {
        var stub = when(partnerSimClient.execute(anyString(), anyString(), anyLong(),
                anyString(), any(), any()));
        for (PartnerSimClient.Result r : results) {
            stub = stub.thenReturn(r);
        }
    }

    private static PartnerSimClient.Result success() {
        return new PartnerSimClient.Result(PartnerSimClient.Result.SUCCESS, "OK", "PRT-9911", Map.of());
    }

    @Nested
    @DisplayName("chọn đối tác và adapter")
    class ChonDoiTac {

        @Test
        @DisplayName("R-TOPUP-02: đối tác không có trong cấu hình thì từ chối ngay")
        void doiTacKhongTonTai() {
            when(configRepository.findById("EVN")).thenReturn(Optional.empty());

            var outcome = service.execute(billCommand());

            assertThat(outcome.status()).isEqualTo("DECLINED");
            assertThat(outcome.reasonCode()).isEqualTo("PARTNER_NOT_FOUND");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("đối tác bị tắt thì từ chối, không gọi ra ngoài")
        void doiTacBiTat() {
            partnerIs(PartnerConfig.BILL, false);

            var outcome = service.execute(billCommand());

            assertThat(outcome.reasonCode()).isEqualTo("PARTNER_DISABLED");
            verify(partnerSimClient, never()).execute(anyString(), anyString(), anyLong(),
                    anyString(), any(), any());
        }

        @Test
        @DisplayName("R-TOPUP-03: không có adapter cho loại dịch vụ thì từ chối")
        void khongCoAdapterPhuHop() {
            partnerIs("LOAI_LA", true);

            var outcome = service.execute(billCommand());

            assertThat(outcome.reasonCode()).isEqualTo("SERVICE_TYPE_NOT_SUPPORTED");
        }

        @Test
        @DisplayName("lệnh không hợp lệ với adapter thì dừng trước khi ghi dấu vết")
        void lenhKhongHopLeVoiAdapter() {
            partnerIs(PartnerConfig.TELCO, true);

            // adapter thẻ điện thoại đòi số thuê bao 10 chữ số, lệnh này không có
            var outcome = service.execute(billCommand());

            assertThat(outcome.status()).isEqualTo("DECLINED");
            assertThat(outcome.reasonCode()).isEqualTo("INVALID_PHONE_NUMBER");
            verify(transactionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("gọi đối tác")
    class GoiDoiTac {

        @BeforeEach
        void doiTacHoaDon() {
            partnerIs(PartnerConfig.BILL, true);
        }

        @Test
        @DisplayName("đối tác chấp nhận: giao dịch SUCCESS, lưu mã tham chiếu, mở dõi quyết toán")
        void doiTacChapNhan() {
            partnerAnswers(success());

            var outcome = service.execute(billCommand());

            assertThat(outcome.status()).isEqualTo("SUCCESS");
            assertThat(outcome.reasonCode()).isEqualTo("OK");
            assertThat(outcome.partnerRef()).isEqualTo("PRT-9911");
            assertThat(outcome.elapsedMs()).isGreaterThanOrEqualTo(0L);

            verify(wsClient).watch(ORDER_ID, "PRT-9911");

            ArgumentCaptor<PartnerTransaction> captor = ArgumentCaptor.forClass(PartnerTransaction.class);
            verify(transactionRepository, times(2)).save(captor.capture());
            PartnerTransaction saved = captor.getAllValues().get(1);
            assertThat(saved.getStatus()).isEqualTo(PartnerTransaction.SUCCESS);
            assertThat(saved.getPartnerRef()).isEqualTo("PRT-9911");
        }

        @Test
        @DisplayName("dấu vết đầu tiên ghi ở trạng thái PENDING kèm loại dịch vụ và mã hoá đơn")
        void ghiDauVetTruocKhiGoi() {
            partnerAnswers(success());

            // Service sửa tại chỗ cùng một đối tượng nên phải chụp trạng thái ngay lúc ghi,
            // captor chỉ giữ tham chiếu và sẽ cho thấy giá trị sau cùng.
            List<String> statusKhiGhi = new ArrayList<>();
            when(transactionRepository.save(any())).thenAnswer(inv -> {
                PartnerTransaction t = inv.getArgument(0);
                statusKhiGhi.add(t.getStatus());
                return t;
            });

            service.execute(billCommand());

            assertThat(statusKhiGhi).containsExactly(
                    PartnerTransaction.PENDING, PartnerTransaction.SUCCESS);

            ArgumentCaptor<PartnerTransaction> captor = ArgumentCaptor.forClass(PartnerTransaction.class);
            verify(transactionRepository, times(2)).save(captor.capture());
            PartnerTransaction txn = captor.getAllValues().get(0);
            assertThat(txn.getServiceType()).isEqualTo(PartnerConfig.BILL);
            assertThat(txn.getBillCode()).isEqualTo("PD0123456");
            assertThat(txn.getOrderId()).isEqualTo(UUID.fromString(ORDER_ID));
            assertThat(txn.getAmount()).isEqualTo(450_000L);
        }

        @Test
        @DisplayName("đối tác từ chối: KHÔNG gọi lại, vì gọi lại có nguy cơ ghi nợ hai lần")
        void biTuChoiThiKhongGoiLai() {
            partnerAnswers(PartnerSimClient.Result.declined("INSUFFICIENT_PARTNER_BALANCE"));

            var outcome = service.execute(billCommand());

            assertThat(outcome.status()).isEqualTo("DECLINED");
            assertThat(outcome.reasonCode()).isEqualTo("INSUFFICIENT_PARTNER_BALANCE");
            assertThat(outcome.partnerRef()).isNull();
            verify(partnerSimClient, times(1)).execute(anyString(), anyString(), anyLong(),
                    anyString(), any(), any());
            verify(wsClient, never()).watch(anyString(), anyString());
        }

        @Test
        @DisplayName("đối tác quá hạn thì thử lại, lần hai thành công thì vẫn báo SUCCESS")
        void quaHanRoiThanhCong() {
            partnerAnswers(PartnerSimClient.Result.timeout(), success());

            var outcome = service.execute(billCommand());

            assertThat(outcome.status()).isEqualTo("SUCCESS");
            verify(partnerSimClient, times(2)).execute(anyString(), anyString(), anyLong(),
                    anyString(), any(), any());
        }

        @Test
        @DisplayName("quá hạn cả hai lần thì báo TIMEOUT, khác nghĩa với DECLINED")
        void quaHanCaHaiLan() {
            partnerAnswers(PartnerSimClient.Result.timeout(), PartnerSimClient.Result.timeout());

            var outcome = service.execute(billCommand());

            assertThat(outcome.status()).isEqualTo("TIMEOUT");
            assertThat(outcome.reasonCode()).isEqualTo("PARTNER_TIMEOUT");
            verify(partnerSimClient, times(2)).execute(anyString(), anyString(), anyLong(),
                    anyString(), any(), any());

            ArgumentCaptor<PartnerTransaction> captor = ArgumentCaptor.forClass(PartnerTransaction.class);
            verify(transactionRepository, times(2)).save(captor.capture());
            PartnerTransaction saved = captor.getAllValues().get(1);
            assertThat(saved.getStatus()).isEqualTo(PartnerTransaction.FAILED);
            assertThat(saved.getFailReason()).isEqualTo("PARTNER_TIMEOUT");
            assertThat(saved.getAttempt()).isEqualTo(2);
        }

        @Test
        @DisplayName("cấu hình chỉ cho gọi một lần thì quá hạn là dừng luôn")
        void chiGoiMotLan() {
            service = newService(1);
            partnerAnswers(PartnerSimClient.Result.timeout());

            var outcome = service.execute(billCommand());

            assertThat(outcome.status()).isEqualTo("TIMEOUT");
            verify(partnerSimClient, times(1)).execute(anyString(), anyString(), anyLong(),
                    anyString(), any(), any());
        }

        @Test
        @DisplayName("số lần gọi nhỏ hơn 1 được nâng lên 1 để lệnh vẫn được thực hiện")
        void soLanGoiVoLy() {
            service = newService(0);
            partnerAnswers(success());

            var outcome = service.execute(billCommand());

            assertThat(outcome.status()).isEqualTo("SUCCESS");
            verify(partnerSimClient, times(1)).execute(anyString(), anyString(), anyLong(),
                    anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("billInquiry — tra cứu hoá đơn")
    class BillInquiry {

        @Test
        @DisplayName("đối tác hợp lệ thì hỏi thẳng đối tác")
        void hoiDoiTac() {
            partnerIs(PartnerConfig.BILL, true);
            when(partnerSimClient.billInquiry("EVN", "PD0123456"))
                    .thenReturn(Map.of("status", "FOUND", "amount", 450_000L));

            Map<String, Object> result = service.billInquiry("EVN", "PD0123456");

            assertThat(result).containsEntry("status", "FOUND");
        }

        @Test
        @DisplayName("đối tác không tồn tại thì trả NOT_FOUND mà không gọi ra ngoài")
        void doiTacKhongTonTai() {
            when(configRepository.findById("KHONG_CO")).thenReturn(Optional.empty());

            Map<String, Object> result = service.billInquiry("KHONG_CO", "PD0123456");

            assertThat(result).containsEntry("status", "NOT_FOUND")
                    .containsEntry("billCode", "PD0123456");
            verify(partnerSimClient, never()).billInquiry(anyString(), anyString());
        }

        @Test
        @DisplayName("đối tác bị tắt cũng trả NOT_FOUND")
        void doiTacBiTat() {
            partnerIs(PartnerConfig.BILL, false);

            assertThat(service.billInquiry("EVN", "PD0123456")).containsEntry("status", "NOT_FOUND");
        }

        @Test
        @DisplayName("mã hoá đơn null thì trả chuỗi rỗng chứ không null trong phản hồi")
        void maHoaDonNull() {
            when(configRepository.findById("KHONG_CO")).thenReturn(Optional.empty());

            assertThat(service.billInquiry("KHONG_CO", null)).containsEntry("billCode", "");
        }
    }

    @Test
    @DisplayName("byOrder trả các lần gọi đối tác của một đơn theo thứ tự thời gian")
    void traCuuTheoDon() {
        UUID orderId = UUID.fromString(ORDER_ID);
        PartnerTransaction txn = new PartnerTransaction();
        txn.setOrderId(orderId);
        when(transactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(txn));

        assertThat(service.byOrder(orderId)).containsExactly(txn);
    }
}
