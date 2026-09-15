package com.ewallet.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.order.dto.OrderDtos;
import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.entity.PaymentOrder;
import com.ewallet.order.grpc.PaymentBusinessClient;
import com.ewallet.order.repo.PaymentOrderRepository;
import com.ewallet.payment.contract.grpc.AuthorizePaymentRequest;
import com.ewallet.payment.contract.grpc.AuthorizePaymentResponse;
import com.ewallet.payment.contract.grpc.ConfirmPaymentRequest;
import com.ewallet.payment.contract.grpc.ConfirmPaymentResponse;
import com.ewallet.payment.contract.grpc.ExecutePartnerPaymentRequest;
import com.ewallet.payment.contract.grpc.ExecutePartnerPaymentResponse;
import com.ewallet.payment.contract.grpc.ReversePaymentRequest;
import com.ewallet.payment.contract.grpc.ReversePaymentResponse;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Saga bốn bước S1 → S2 → S3 → S4 và nhánh bù trừ S3' (docs/specs/F2, F3, F4).
 *
 * <p>Toàn bộ gọi ra ngoài đi qua {@link PaymentBusinessClient} nên test giả lập client này
 * để dựng từng kịch bản hỏng ở đúng một bước, rồi kiểm tra trạng thái cuối của đơn
 * và việc bù trừ có được gọi hay không.</p>
 *
 * <p>Phạm vi: F2 hoá đơn, F3 chuyển tiền, F4 bù trừ. Đường nạp tiền F1 xem
 * {@code docs/testing.md} §3.</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentSagaOrchestratorTest {

    private static final String IDEM_KEY = "idem-0001";
    private static final String TXN_ID = "22222222-2222-4222-8222-222222222222";

    @Mock private PaymentOrderRepository orderRepository;
    @Mock private PaymentBusinessClient businessClient;
    @Mock private SagaStepRecorder steps;

    @InjectMocks
    private PaymentSagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        lenient().when(orderRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        lenient().when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------- dựng dữ liệu

    /** Chuyển tiền: không có partnerCode nên saga bỏ hẳn bước S3. */
    private OrderDtos.CreateOrderRequest p2pRequest() {
        return new OrderDtos.CreateOrderRequest("CUST-001", "P2P", 3_000_000L, "VND",
                null, "CUST-002", null, null);
    }

    /** Hoá đơn: có partnerCode nên saga chạy đủ bốn bước. */
    private OrderDtos.CreateOrderRequest billRequest() {
        return new OrderDtos.CreateOrderRequest("CUST-001", "BILL", 450_000L, "VND",
                "EVN", null, "PD0123456", "0901234567");
    }

    private AuthorizePaymentResponse auth(String status, String reason) {
        return AuthorizePaymentResponse.newBuilder()
                .setStatus(status)
                .setReasonCode(reason)
                .setFee(2_200L)
                .setLedgerTxnId(TXN_ID)
                .setAmountVnd(3_000_000L)
                .build();
    }

    private ExecutePartnerPaymentResponse exec(String status, String reason) {
        return ExecutePartnerPaymentResponse.newBuilder()
                .setStatus(status)
                .setReasonCode(reason)
                .setPartnerRef("PRT-9911")
                .setElapsedMs(120L)
                .build();
    }

    private ConfirmPaymentResponse confirm(String status) {
        return ConfirmPaymentResponse.newBuilder()
                .setStatus(status)
                .setReasonCode("OK")
                .setBalanceAfter(7_000_000L)
                .build();
    }

    private ReversePaymentResponse reverse(String status) {
        return ReversePaymentResponse.newBuilder()
                .setStatus(status)
                .setReasonCode("OK")
                .setRefundTxnId(UUID.randomUUID().toString())
                .setBalanceAfter(10_000_000L)
                .build();
    }

    private void authorizeOk() {
        when(businessClient.authorize(any())).thenReturn(auth("AUTHORIZED", "OK"));
    }

    // =====================================================================================

    @Nested
    @DisplayName("đường chính chạy trọn vẹn")
    class DuongChinh {

        @Test
        @DisplayName("R-P2P-02: không có đối tác thì bỏ bước S3, đơn về COMPLETED")
        void chuyenTienBoQuaBuocDoiTac() {
            authorizeOk();
            when(businessClient.confirm(any())).thenReturn(confirm("CAPTURED"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.COMPLETED);
            assertThat(order.getTxnId()).isEqualTo(UUID.fromString(TXN_ID));
            assertThat(order.getFee()).isEqualTo(2_200L);
            assertThat(order.getAmountVnd()).isEqualTo(3_000_000L);

            verify(businessClient, never()).executePartner(any());
            verify(businessClient, never()).reverse(any());
            verify(steps).done(any(), eq(OrderStep.CREATE_ORDER), anyString(), anyLong());
        }

        @Test
        @DisplayName("có đối tác thì chạy đủ bốn bước và lưu mã tham chiếu của đối tác")
        void hoaDonChayDuBonBuoc() {
            authorizeOk();
            when(businessClient.executePartner(any())).thenReturn(exec("SUCCESS", "OK"));
            when(businessClient.confirm(any())).thenReturn(confirm("CAPTURED"));

            PaymentOrder order = orchestrator.run(billRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.COMPLETED);
            assertThat(order.getPartnerRef()).isEqualTo("PRT-9911");
            verify(steps).done(any(), eq(OrderStep.PARTNER_EXECUTE), eq("PRT-9911"), anyLong());
            verify(businessClient, never()).reverse(any());
        }

        @Test
        @DisplayName("thông tin đơn được chuyển đủ sang bước AUTHORIZE")
        void chuyenDuThongTinSangAuthorize() {
            authorizeOk();
            when(businessClient.executePartner(any())).thenReturn(exec("SUCCESS", "OK"));
            when(businessClient.confirm(any())).thenReturn(confirm("CAPTURED"));

            orchestrator.run(billRequest(), IDEM_KEY);

            ArgumentCaptor<AuthorizePaymentRequest> captor =
                    ArgumentCaptor.forClass(AuthorizePaymentRequest.class);
            verify(businessClient).authorize(captor.capture());
            AuthorizePaymentRequest req = captor.getValue();
            assertThat(req.getCustomerId()).isEqualTo("CUST-001");
            assertThat(req.getPaymentType()).isEqualTo("BILL");
            assertThat(req.getAmount()).isEqualTo(450_000L);
            assertThat(req.getCurrency()).isEqualTo("VND");
            assertThat(req.getPartnerCode()).isEqualTo("EVN");
            assertThat(req.getBillCode()).isEqualTo("PD0123456");
            assertThat(req.getIdempotencyKey()).isEqualTo(IDEM_KEY);
        }

        @Test
        @DisplayName("bước gọi đối tác nhận đúng txnId mà bước AUTHORIZE vừa cấp")
        void buocDoiTacDungTxnId() {
            authorizeOk();
            when(businessClient.executePartner(any())).thenReturn(exec("SUCCESS", "OK"));
            when(businessClient.confirm(any())).thenReturn(confirm("CAPTURED"));

            orchestrator.run(billRequest(), IDEM_KEY);

            ArgumentCaptor<ExecutePartnerPaymentRequest> captor =
                    ArgumentCaptor.forClass(ExecutePartnerPaymentRequest.class);
            verify(businessClient).executePartner(captor.capture());
            assertThat(captor.getValue().getTxnId()).isEqualTo(TXN_ID);
            assertThat(captor.getValue().getAccountRef()).isEqualTo("0901234567");
        }

        @Test
        @DisplayName("loại giao dịch và loại tiền được chuẩn hoá về chữ hoa, thiếu tiền tệ thì mặc định VND")
        void chuanHoaDuLieuDauVao() {
            authorizeOk();
            when(businessClient.confirm(any())).thenReturn(confirm("CAPTURED"));

            PaymentOrder order = orchestrator.run(new OrderDtos.CreateOrderRequest(
                    "CUST-001", " p2p ", 500_000L, null, "  ", "CUST-002", "", ""), IDEM_KEY);

            assertThat(order.getPaymentType()).isEqualTo("P2P");
            assertThat(order.getCurrency()).isEqualTo("VND");
            assertThat(order.getPartnerCode()).isNull();
            assertThat(order.getBillCode()).isNull();
            assertThat(order.getAccountRef()).isNull();
        }
    }

    @Nested
    @DisplayName("S2 AUTHORIZE hỏng")
    class AuthorizeHong {

        @Test
        @DisplayName("bị từ chối thì đơn về REJECTED, không bù trừ vì chưa giữ tiền")
        void biTuChoi() {
            when(businessClient.authorize(any())).thenReturn(auth("REJECTED", "INSUFFICIENT_FUNDS"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.REJECTED);
            assertThat(order.getReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
            verify(businessClient, never()).reverse(any());
            verify(businessClient, never()).confirm(any());
        }

        @Test
        @DisplayName("R-COMP-07: treo chờ duyệt thì saga dừng, tiền vẫn bị giữ, KHÔNG tự bù trừ")
        void treoChoDuyet() {
            when(businessClient.authorize(any())).thenReturn(auth("HELD", "MANUAL_REVIEW"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.HELD);
            assertThat(order.getReasonCode()).isEqualTo("MANUAL_REVIEW");
            verify(businessClient, never()).reverse(any());
            verify(businessClient, never()).confirm(any());
            verify(steps).record(any(), eq(OrderStep.AUTHORIZE), eq(OrderStep.DONE),
                    eq("MANUAL_REVIEW"), eq(1), anyLong());
        }

        @Test
        @DisplayName("business trả trạng thái lạ thì đơn về FAILED thay vì đi tiếp")
        void trangThaiLa() {
            when(businessClient.authorize(any())).thenReturn(auth("KHONG_BIET", ""));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.FAILED);
            assertThat(order.getReasonCode()).isEqualTo("UNKNOWN_AUTH_STATUS");
            verify(businessClient, never()).confirm(any());
        }

        @Test
        @DisplayName("gọi gRPC hỏng thì đơn FAILED và không bù trừ vì chưa có gì để trả lại")
        void loiHaTang() {
            when(businessClient.authorize(any())).thenThrow(new IllegalStateException("mat ket noi"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.FAILED);
            assertThat(order.getReasonCode()).isEqualTo("AUTHORIZE_UNAVAILABLE");
            verify(businessClient, never()).reverse(any());
            verify(steps).failed(any(), eq(OrderStep.AUTHORIZE), anyString(), eq(1), anyLong());
        }
    }

    @Nested
    @DisplayName("S3 PARTNER_EXECUTE hỏng — R-COMP-01")
    class DoiTacHong {

        @BeforeEach
        void authorizeThanhCong() {
            authorizeOk();
        }

        @Test
        @DisplayName("đối tác từ chối thì bù trừ ngay, đơn về REFUNDED")
        void doiTacTuChoi() {
            when(businessClient.executePartner(any())).thenReturn(exec("DECLINED", "PARTNER_DECLINED"));
            when(businessClient.reverse(any())).thenReturn(reverse("REVERSED"));

            PaymentOrder order = orchestrator.run(billRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.REFUNDED);
            assertThat(order.getReasonCode()).isEqualTo("PARTNER_DECLINED");

            ArgumentCaptor<ReversePaymentRequest> captor =
                    ArgumentCaptor.forClass(ReversePaymentRequest.class);
            verify(businessClient).reverse(captor.capture());
            assertThat(captor.getValue().getTxnId()).isEqualTo(TXN_ID);
            assertThat(captor.getValue().getReasonCode()).isEqualTo("PARTNER_DECLINED");
            verify(businessClient, never()).confirm(any());
        }

        @Test
        @DisplayName("đối tác quá hạn (ném lỗi) thì bù trừ với lý do PARTNER_TIMEOUT")
        void doiTacQuaHan() {
            when(businessClient.executePartner(any()))
                    .thenThrow(new IllegalStateException("deadline exceeded"));
            when(businessClient.reverse(any())).thenReturn(reverse("REVERSED"));

            PaymentOrder order = orchestrator.run(billRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.REFUNDED);
            ArgumentCaptor<ReversePaymentRequest> captor =
                    ArgumentCaptor.forClass(ReversePaymentRequest.class);
            verify(businessClient).reverse(captor.capture());
            assertThat(captor.getValue().getReasonCode()).isEqualTo("PARTNER_TIMEOUT");
        }
    }

    @Nested
    @DisplayName("S4 CONFIRM hỏng")
    class ConfirmHong {

        @BeforeEach
        void authorizeThanhCong() {
            authorizeOk();
        }

        @Test
        @DisplayName("business trả trạng thái khác CAPTURED thì bù trừ với lý do CONFIRM_FAILED")
        void khongChotDuocSo() {
            when(businessClient.confirm(any())).thenReturn(confirm("ERROR"));
            when(businessClient.reverse(any())).thenReturn(reverse("REVERSED"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.REFUNDED);
            assertThat(order.getReasonCode()).isEqualTo("CONFIRM_FAILED");
        }

        @Test
        @DisplayName("hỏng lần đầu rồi thành công thì đơn vẫn COMPLETED, lần thử được ghi lại")
        void thuLaiRoiThanhCong() {
            when(businessClient.confirm(any()))
                    .thenThrow(new IllegalStateException("tam thoi mat ket noi"))
                    .thenReturn(confirm("CAPTURED"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.COMPLETED);
            verify(steps).failed(any(), eq(OrderStep.CONFIRM), anyString(), eq(1), anyLong());
            verify(steps).record(any(), eq(OrderStep.CONFIRM), eq(OrderStep.DONE),
                    anyString(), eq(2), anyLong());
            verify(businessClient, never()).reverse(any());
        }

        @Test
        @DisplayName("hỏng đủ ba lần thì bỏ cuộc và bù trừ")
        void hongDuBaLan() {
            when(businessClient.confirm(any())).thenThrow(new IllegalStateException("mat ket noi"));
            when(businessClient.reverse(any())).thenReturn(reverse("REVERSED"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.REFUNDED);
            verify(businessClient, org.mockito.Mockito.times(3)).confirm(any());
            verify(steps).failed(any(), eq(OrderStep.CONFIRM), anyString(), eq(3), anyLong());
        }

        @Test
        @DisplayName("partnerRef rỗng vẫn gửi được xuống bước chốt sổ")
        void partnerRefRong() {
            when(businessClient.confirm(any())).thenReturn(confirm("CAPTURED"));

            orchestrator.run(p2pRequest(), IDEM_KEY);

            ArgumentCaptor<ConfirmPaymentRequest> captor =
                    ArgumentCaptor.forClass(ConfirmPaymentRequest.class);
            verify(businessClient).confirm(captor.capture());
            assertThat(captor.getValue().getPartnerRef()).isEmpty();
        }
    }

    @Nested
    @DisplayName("bù trừ hỏng — R-COMP-04")
    class BuTruHong {

        @BeforeEach
        void authorizeThanhCong() {
            authorizeOk();
        }

        @Test
        @DisplayName("business không bù trừ được thì đơn về FAILED/COMPENSATION_FAILED")
        void businessTuChoiBuTru() {
            when(businessClient.confirm(any())).thenReturn(confirm("ERROR"));
            when(businessClient.reverse(any())).thenReturn(reverse("ERROR"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.FAILED);
            assertThat(order.getReasonCode()).isEqualTo("COMPENSATION_FAILED");
            verify(steps).failed(any(), eq(OrderStep.COMPENSATE), anyString(), eq(1), anyLong());
        }

        @Test
        @DisplayName("gọi bù trừ ném lỗi thì cũng về FAILED/COMPENSATION_FAILED chứ không nuốt lỗi")
        void goiBuTruNemLoi() {
            when(businessClient.confirm(any())).thenReturn(confirm("ERROR"));
            when(businessClient.reverse(any())).thenThrow(new IllegalStateException("business chet"));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.FAILED);
            assertThat(order.getReasonCode()).isEqualTo("COMPENSATION_FAILED");
        }
    }

    @Nested
    @DisplayName("idempotency — R-IDEM-02, R-IDEM-03")
    class Idempotency {

        private PaymentOrder existing() {
            PaymentOrder o = new PaymentOrder();
            o.setId(UUID.randomUUID());
            o.setCustomerId("CUST-001");
            o.setPaymentType("P2P");
            o.setAmount(3_000_000L);
            o.setCurrency("VND");
            o.setDestCustomerId("CUST-002");
            o.setStatus(PaymentOrder.COMPLETED);
            o.setIdempotencyKey(IDEM_KEY);
            o.setCreatedAt(OffsetDateTime.now());
            return o;
        }

        @Test
        @DisplayName("cùng key và cùng nội dung thì trả nguyên đơn cũ, không chạy lại saga")
        void cungKeyCungNoiDung() {
            PaymentOrder old = existing();
            when(orderRepository.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.of(old));

            PaymentOrder order = orchestrator.run(p2pRequest(), IDEM_KEY);

            assertThat(order.getId()).isEqualTo(old.getId());
            verify(businessClient, never()).authorize(any());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("khác hoa thường ở loại giao dịch và tiền tệ vẫn coi là cùng nội dung")
        void khacHoaThuongVanLaMot() {
            when(orderRepository.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.of(existing()));

            PaymentOrder order = orchestrator.run(new OrderDtos.CreateOrderRequest(
                    "CUST-001", "p2p", 3_000_000L, "vnd", null, "CUST-002", null, null), IDEM_KEY);

            assertThat(order.getStatus()).isEqualTo(PaymentOrder.COMPLETED);
            verify(businessClient, never()).authorize(any());
        }

        @Test
        @DisplayName("R-IDEM-03: cùng key nhưng khác số tiền thì báo lỗi trùng key")
        void cungKeyKhacSoTien() {
            when(orderRepository.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.of(existing()));

            assertThatThrownBy(() -> orchestrator.run(new OrderDtos.CreateOrderRequest(
                    "CUST-001", "P2P", 9_999_999L, "VND", null, "CUST-002", null, null), IDEM_KEY))
                    .isInstanceOf(DuplicateRequestException.class);

            verify(businessClient, never()).authorize(any());
        }

        @Test
        @DisplayName("R-IDEM-03: cùng key nhưng khác người nhận cũng bị chặn")
        void cungKeyKhacNguoiNhan() {
            when(orderRepository.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.of(existing()));

            assertThatThrownBy(() -> orchestrator.run(new OrderDtos.CreateOrderRequest(
                    "CUST-001", "P2P", 3_000_000L, "VND", null, "CUST-099", null, null), IDEM_KEY))
                    .isInstanceOf(DuplicateRequestException.class);
        }
    }

    @Nested
    @DisplayName("refund — Ops hoàn tiền chủ động (F4d)")
    class Refund {

        private final UUID orderId = UUID.randomUUID();

        private PaymentOrder order(String status) {
            PaymentOrder o = new PaymentOrder();
            o.setId(orderId);
            o.setCustomerId("CUST-001");
            o.setPaymentType("BILL");
            o.setAmount(450_000L);
            o.setCurrency("VND");
            o.setTxnId(UUID.fromString(TXN_ID));
            o.setStatus(status);
            return o;
        }

        @Test
        @DisplayName("đơn đã hoàn tất thì hoàn tiền được, đơn chuyển REFUNDED")
        void hoanTienDonDaHoanTat() {
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.COMPLETED)));
            when(businessClient.reverse(any())).thenReturn(reverse("REVERSED"));

            PaymentOrder result = orchestrator.refund(orderId, "CUSTOMER_REQUEST");

            assertThat(result.getStatus()).isEqualTo(PaymentOrder.REFUNDED);
            assertThat(result.getReasonCode()).isEqualTo("CUSTOMER_REQUEST");
        }

        @Test
        @DisplayName("không ghi lý do thì mặc định là khách yêu cầu")
        void thieuLyDoThiMacDinh() {
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.COMPLETED)));
            when(businessClient.reverse(any())).thenReturn(reverse("REVERSED"));

            orchestrator.refund(orderId, "  ");

            ArgumentCaptor<ReversePaymentRequest> captor =
                    ArgumentCaptor.forClass(ReversePaymentRequest.class);
            verify(businessClient).reverse(captor.capture());
            assertThat(captor.getValue().getReasonCode()).isEqualTo("CUSTOMER_REQUEST");
        }

        @Test
        @DisplayName("R-COMP-06: đơn đã hoàn rồi thì không hoàn lần hai")
        void khongHoanHaiLan() {
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.REFUNDED)));

            assertThatThrownBy(() -> orchestrator.refund(orderId, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ALREADY_REFUNDED");
            verify(businessClient, never()).reverse(any());
        }

        @Test
        @DisplayName("đơn chưa hoàn tất thì không hoàn tiền được")
        void donChuaHoanTat() {
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order(PaymentOrder.HELD)));

            assertThatThrownBy(() -> orchestrator.refund(orderId, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("INVALID_STATE");
        }

        @Test
        @DisplayName("không tìm thấy đơn thì báo lỗi rõ ràng")
        void khongTimThayDon() {
            when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orchestrator.refund(orderId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(orderId.toString());
        }
    }

    @Test
    @DisplayName("mọi bước của saga đều để lại dấu vết trong order_steps")
    void moiBuocDeuCoDauVet() {
        authorizeOk();
        when(businessClient.executePartner(any())).thenReturn(exec("SUCCESS", "OK"));
        when(businessClient.confirm(any())).thenReturn(confirm("CAPTURED"));

        orchestrator.run(billRequest(), IDEM_KEY);

        verify(steps).done(any(), eq(OrderStep.CREATE_ORDER), anyString(), anyLong());
        verify(steps).done(any(), eq(OrderStep.AUTHORIZE), anyString(), anyLong());
        verify(steps).done(any(), eq(OrderStep.PARTNER_EXECUTE), anyString(), anyLong());
        verify(steps).record(any(), eq(OrderStep.CONFIRM), eq(OrderStep.DONE),
                anyString(), anyInt(), anyLong());
    }
}
