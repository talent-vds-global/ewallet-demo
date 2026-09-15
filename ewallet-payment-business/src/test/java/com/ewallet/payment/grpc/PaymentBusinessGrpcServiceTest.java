package com.ewallet.payment.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.payment.contract.grpc.AuthorizePaymentRequest;
import com.ewallet.payment.contract.grpc.AuthorizePaymentResponse;
import com.ewallet.payment.contract.grpc.ConfirmPaymentRequest;
import com.ewallet.payment.contract.grpc.ConfirmPaymentResponse;
import com.ewallet.payment.contract.grpc.ExecutePartnerPaymentRequest;
import com.ewallet.payment.contract.grpc.ExecutePartnerPaymentResponse;
import com.ewallet.payment.contract.grpc.InquireBillRequest;
import com.ewallet.payment.contract.grpc.InquireBillResponse;
import com.ewallet.payment.contract.grpc.ReversePaymentRequest;
import com.ewallet.payment.contract.grpc.ReversePaymentResponse;
import com.ewallet.payment.domain.AuthorizeCommand;
import com.ewallet.payment.domain.AuthorizeResult;
import com.ewallet.payment.domain.BillInquiryResult;
import com.ewallet.payment.domain.ConfirmResult;
import com.ewallet.payment.domain.PartnerExecutionResult;
import com.ewallet.payment.domain.PaymentType;
import com.ewallet.payment.domain.ReasonCodes;
import com.ewallet.payment.domain.ReverseResult;
import com.ewallet.payment.service.PaymentService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
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
 * Lớp ánh xạ proto ↔ domain của gRPC server. Nghiệp vụ đã có test riêng ở
 * {@code PaymentServiceTest} nên ở đây chỉ kiểm tra: đọc đúng field của request,
 * dựng đúng response, và chuyển lỗi thành đúng {@link Status}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentBusinessGrpcServiceTest {

    private static final String ORDER_ID = "11111111-1111-4111-8111-111111111111";
    private static final String TXN_ID = "22222222-2222-4222-8222-222222222222";

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private PaymentBusinessGrpcService grpcService;

    /** Ghi lại những gì handler đẩy ra, thay cho kênh gRPC thật. */
    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private final List<T> values = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }

        T single() {
            assertThat(values).hasSize(1);
            assertThat(completed).isTrue();
            assertThat(error).isNull();
            return values.get(0);
        }

        Status errorStatus() {
            assertThat(error).isInstanceOf(StatusRuntimeException.class);
            return ((StatusRuntimeException) error).getStatus();
        }
    }

    @Nested
    @DisplayName("AuthorizePayment")
    class Authorize {

        private RecordingObserver<AuthorizePaymentResponse> observer;

        @BeforeEach
        void setUp() {
            observer = new RecordingObserver<>();
        }

        private AuthorizePaymentRequest.Builder request() {
            return AuthorizePaymentRequest.newBuilder()
                    .setOrderId(ORDER_ID)
                    .setCustomerId("CUST-001")
                    .setPaymentType("P2P")
                    .setAmount(3_000_000L)
                    .setCurrency("VND")
                    .setDestCustomerId("CUST-002")
                    .setIdempotencyKey("idem-1");
        }

        @Test
        @DisplayName("field của request được ánh xạ sang AuthorizeCommand")
        void anhXaRequestSangCommand() {
            UUID txnId = UUID.randomUUID();
            when(paymentService.authorize(any())).thenReturn(
                    new AuthorizeResult(AuthorizeResult.AUTHORIZED, ReasonCodes.OK, 2_200L, txnId, 3_000_000L));

            grpcService.authorizePayment(request().build(), observer);

            ArgumentCaptor<AuthorizeCommand> captor = ArgumentCaptor.forClass(AuthorizeCommand.class);
            verify(paymentService).authorize(captor.capture());
            AuthorizeCommand cmd = captor.getValue();
            assertThat(cmd.orderId()).isEqualTo(UUID.fromString(ORDER_ID));
            assertThat(cmd.customerId()).isEqualTo("CUST-001");
            assertThat(cmd.paymentType()).isEqualTo(PaymentType.P2P);
            assertThat(cmd.amount()).isEqualTo(3_000_000L);
            assertThat(cmd.destCustomerId()).isEqualTo("CUST-002");
            assertThat(cmd.idempotencyKey()).isEqualTo("idem-1");
        }

        @Test
        @DisplayName("kết quả nghiệp vụ được dựng lại đầy đủ trong response")
        void dungResponse() {
            UUID txnId = UUID.randomUUID();
            when(paymentService.authorize(any())).thenReturn(
                    new AuthorizeResult(AuthorizeResult.AUTHORIZED, ReasonCodes.OK, 2_200L, txnId, 3_000_000L));

            grpcService.authorizePayment(request().build(), observer);

            AuthorizePaymentResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(AuthorizeResult.AUTHORIZED);
            assertThat(response.getReasonCode()).isEqualTo(ReasonCodes.OK);
            assertThat(response.getFee()).isEqualTo(2_200L);
            assertThat(response.getLedgerTxnId()).isEqualTo(txnId.toString());
            assertThat(response.getAmountVnd()).isEqualTo(3_000_000L);
        }

        @Test
        @DisplayName("giao dịch bị từ chối trước khi ghi sổ thì ledger_txn_id để rỗng")
        void tuChoiThiKhongCoTxnId() {
            when(paymentService.authorize(any()))
                    .thenReturn(AuthorizeResult.rejected(ReasonCodes.INVALID_PAYMENT_TYPE, null, 0L));

            grpcService.authorizePayment(request().build(), observer);

            AuthorizePaymentResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(AuthorizeResult.REJECTED);
            assertThat(response.getLedgerTxnId()).isEmpty();
        }

        @Test
        @DisplayName("field tuỳ chọn để rỗng thì truyền xuống là null, không phải chuỗi rỗng")
        void fieldRongThanhNull() {
            when(paymentService.authorize(any())).thenReturn(
                    new AuthorizeResult(AuthorizeResult.AUTHORIZED, ReasonCodes.OK, 0L, UUID.randomUUID(), 500_000L));

            grpcService.authorizePayment(AuthorizePaymentRequest.newBuilder()
                    .setOrderId(ORDER_ID)
                    .setCustomerId("CUST-001")
                    .setPaymentType("P2P")
                    .setAmount(500_000L)
                    .setCurrency("VND")
                    .build(), observer);

            ArgumentCaptor<AuthorizeCommand> captor = ArgumentCaptor.forClass(AuthorizeCommand.class);
            verify(paymentService).authorize(captor.capture());
            assertThat(captor.getValue().destCustomerId()).isNull();
            assertThat(captor.getValue().partnerCode()).isNull();
            assertThat(captor.getValue().billCode()).isNull();
            assertThat(captor.getValue().idempotencyKey()).isNull();
        }

        @Test
        @DisplayName("orderId không phải UUID thì trả INVALID_ARGUMENT, không gọi nghiệp vụ")
        void orderIdKhongHopLe() {
            grpcService.authorizePayment(request().setOrderId("khong-phai-uuid").build(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            verify(paymentService, never()).authorize(any());
        }

        @Test
        @DisplayName("orderId rỗng cũng bị coi là không hợp lệ")
        void orderIdRong() {
            grpcService.authorizePayment(request().setOrderId("").build(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("nghiệp vụ ném lỗi thì trả INTERNAL kèm thông điệp")
        void nghiepVuNemLoi() {
            when(paymentService.authorize(any())).thenThrow(new IllegalStateException("so cai hong"));

            grpcService.authorizePayment(request().build(), observer);

            Status status = observer.errorStatus();
            assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(status.getDescription()).isEqualTo("so cai hong");
        }
    }

    @Nested
    @DisplayName("ExecutePartnerPayment")
    class ExecutePartner {

        private final RecordingObserver<ExecutePartnerPaymentResponse> observer = new RecordingObserver<>();

        private ExecutePartnerPaymentRequest.Builder request() {
            return ExecutePartnerPaymentRequest.newBuilder()
                    .setOrderId(ORDER_ID)
                    .setTxnId(TXN_ID)
                    .setPartnerCode("EVN")
                    .setPaymentType("BILL")
                    .setAmount(450_000L)
                    .setCurrency("VND")
                    .setAccountRef("0901234567")
                    .setBillCode("PD0123456");
        }

        @Test
        @DisplayName("tham số được chuyển đủ xuống nghiệp vụ và response mang mã tham chiếu đối tác")
        void goiDoiTacThanhCong() {
            when(paymentService.executePartner(any(), any(), anyString(), anyString(),
                    anyLong(), anyString(), anyString(), anyString()))
                    .thenReturn(new PartnerExecutionResult(PartnerExecutionResult.SUCCESS,
                            ReasonCodes.OK, "PRT-9911", 123L));

            grpcService.executePartnerPayment(request().build(), observer);

            verify(paymentService).executePartner(UUID.fromString(ORDER_ID), UUID.fromString(TXN_ID),
                    "EVN", "BILL", 450_000L, "VND", "0901234567", "PD0123456");

            ExecutePartnerPaymentResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(PartnerExecutionResult.SUCCESS);
            assertThat(response.getPartnerRef()).isEqualTo("PRT-9911");
            assertThat(response.getElapsedMs()).isEqualTo(123L);
        }

        @Test
        @DisplayName("đối tác quá hạn thì trả TIMEOUT cho payment-order tự quyết bù trừ")
        void doiTacQuaHan() {
            when(paymentService.executePartner(any(), any(), anyString(), anyString(),
                    anyLong(), anyString(), anyString(), anyString()))
                    .thenReturn(new PartnerExecutionResult(PartnerExecutionResult.TIMEOUT,
                            ReasonCodes.PARTNER_TIMEOUT, "", 3_000L));

            grpcService.executePartnerPayment(request().build(), observer);

            assertThat(observer.single().getStatus()).isEqualTo(PartnerExecutionResult.TIMEOUT);
        }

        @Test
        @DisplayName("txnId không hợp lệ thì trả INVALID_ARGUMENT")
        void txnIdKhongHopLe() {
            grpcService.executePartnerPayment(request().setTxnId("xxx").build(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            verify(paymentService, never()).executePartner(any(), any(), anyString(), anyString(),
                    anyLong(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("nghiệp vụ ném lỗi thì trả INTERNAL")
        void nghiepVuNemLoi() {
            when(paymentService.executePartner(any(), any(), anyString(), anyString(),
                    anyLong(), anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("mat ket noi"));

            grpcService.executePartnerPayment(request().build(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("ConfirmPayment")
    class Confirm {

        private final RecordingObserver<ConfirmPaymentResponse> observer = new RecordingObserver<>();

        private ConfirmPaymentRequest.Builder request() {
            return ConfirmPaymentRequest.newBuilder()
                    .setOrderId(ORDER_ID)
                    .setTxnId(TXN_ID)
                    .setPartnerRef("PRT-9911");
        }

        @Test
        @DisplayName("chốt sổ xong trả số dư mới của ví khách")
        void chotSoThanhCong() {
            when(paymentService.confirm(any(), any(), anyString()))
                    .thenReturn(new ConfirmResult(ConfirmResult.CAPTURED, ReasonCodes.OK, 7_000_000L));

            grpcService.confirmPayment(request().build(), observer);

            verify(paymentService).confirm(UUID.fromString(ORDER_ID), UUID.fromString(TXN_ID), "PRT-9911");
            ConfirmPaymentResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(ConfirmResult.CAPTURED);
            assertThat(response.getBalanceAfter()).isEqualTo(7_000_000L);
        }

        @Test
        @DisplayName("sai trạng thái thì trả ERROR kèm lý do, không phải lỗi kênh gRPC")
        void saiTrangThaiVanLaResponse() {
            when(paymentService.confirm(any(), any(), anyString()))
                    .thenReturn(ConfirmResult.error(ReasonCodes.INVALID_STATE));

            grpcService.confirmPayment(request().build(), observer);

            ConfirmPaymentResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(ConfirmResult.ERROR);
            assertThat(response.getReasonCode()).isEqualTo(ReasonCodes.INVALID_STATE);
        }

        @Test
        @DisplayName("orderId không hợp lệ thì trả INVALID_ARGUMENT")
        void orderIdKhongHopLe() {
            grpcService.confirmPayment(request().setOrderId("yyy").build(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("nghiệp vụ ném lỗi thì trả INTERNAL")
        void nghiepVuNemLoi() {
            when(paymentService.confirm(any(), any(), anyString()))
                    .thenThrow(new IllegalStateException("db loi"));

            grpcService.confirmPayment(request().build(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("ReversePayment")
    class Reverse {

        private final RecordingObserver<ReversePaymentResponse> observer = new RecordingObserver<>();

        private ReversePaymentRequest.Builder request() {
            return ReversePaymentRequest.newBuilder()
                    .setOrderId(ORDER_ID)
                    .setTxnId(TXN_ID)
                    .setReasonCode(ReasonCodes.PARTNER_TIMEOUT);
        }

        @Test
        @DisplayName("bù trừ xong trả txn hoàn tiền và số dư mới")
        void buTruThanhCong() {
            UUID refundTxnId = UUID.randomUUID();
            when(paymentService.reverse(any(), any(), anyString()))
                    .thenReturn(new ReverseResult(ReverseResult.REVERSED, ReasonCodes.PARTNER_TIMEOUT,
                            refundTxnId, 9_000_000L));

            grpcService.reversePayment(request().build(), observer);

            ReversePaymentResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(ReverseResult.REVERSED);
            assertThat(response.getRefundTxnId()).isEqualTo(refundTxnId.toString());
            assertThat(response.getBalanceAfter()).isEqualTo(9_000_000L);
        }

        @Test
        @DisplayName("không gửi lý do thì mặc định coi là đối tác từ chối")
        void thieuLyDoThiMacDinh() {
            when(paymentService.reverse(any(), any(), anyString()))
                    .thenReturn(new ReverseResult(ReverseResult.REVERSED, ReasonCodes.PARTNER_DECLINED,
                            UUID.randomUUID(), 0L));

            grpcService.reversePayment(request().setReasonCode("").build(), observer);

            verify(paymentService).reverse(UUID.fromString(ORDER_ID), UUID.fromString(TXN_ID),
                    ReasonCodes.PARTNER_DECLINED);
        }

        @Test
        @DisplayName("không tìm thấy giao dịch thì refund_txn_id để rỗng")
        void khongCoTxnHoanTien() {
            when(paymentService.reverse(any(), any(), anyString()))
                    .thenReturn(ReverseResult.error(ReasonCodes.TXN_NOT_FOUND));

            grpcService.reversePayment(request().build(), observer);

            ReversePaymentResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(ReverseResult.ERROR);
            assertThat(response.getRefundTxnId()).isEmpty();
        }

        @Test
        @DisplayName("txnId không hợp lệ thì trả INVALID_ARGUMENT")
        void txnIdKhongHopLe() {
            grpcService.reversePayment(request().setTxnId("zzz").build(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("nghiệp vụ ném lỗi thì trả INTERNAL")
        void nghiepVuNemLoi() {
            when(paymentService.reverse(any(), any(), anyString()))
                    .thenThrow(new IllegalStateException("khong bu tru duoc"));

            grpcService.reversePayment(request().build(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("InquireBill")
    class InquireBill {

        private final RecordingObserver<InquireBillResponse> observer = new RecordingObserver<>();

        private InquireBillRequest request() {
            return InquireBillRequest.newBuilder()
                    .setPartnerCode("EVN")
                    .setBillCode("PD0123456")
                    .setCustomerId("CUST-001")
                    .build();
        }

        @Test
        @DisplayName("tìm thấy hoá đơn thì trả đủ thông tin cho màn hình xác nhận")
        void timThayHoaDon() {
            when(paymentService.inquireBill("EVN", "PD0123456")).thenReturn(
                    new BillInquiryResult(BillInquiryResult.FOUND, "PD0123456", "Nguyen Van A",
                            "2026-08", 450_000L, "VND", "UNPAID"));

            grpcService.inquireBill(request(), observer);

            InquireBillResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(BillInquiryResult.FOUND);
            assertThat(response.getCustomerName()).isEqualTo("Nguyen Van A");
            assertThat(response.getPeriod()).isEqualTo("2026-08");
            assertThat(response.getAmount()).isEqualTo(450_000L);
            assertThat(response.getBillStatus()).isEqualTo("UNPAID");
        }

        @Test
        @DisplayName("không tìm thấy hoá đơn thì các field chuỗi vẫn rỗng chứ không null")
        void khongTimThayHoaDon() {
            when(paymentService.inquireBill("EVN", "PD0123456"))
                    .thenReturn(BillInquiryResult.notFound("PD0123456"));

            grpcService.inquireBill(request(), observer);

            InquireBillResponse response = observer.single();
            assertThat(response.getStatus()).isEqualTo(BillInquiryResult.NOT_FOUND);
            assertThat(response.getCustomerName()).isEmpty();
            assertThat(response.getAmount()).isZero();
        }

        @Test
        @DisplayName("nghiệp vụ ném lỗi thì trả INTERNAL")
        void nghiepVuNemLoi() {
            when(paymentService.inquireBill(anyString(), anyString()))
                    .thenThrow(new IllegalStateException("doi tac khong phan hoi"));

            grpcService.inquireBill(request(), observer);

            assertThat(observer.errorStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        }
    }
}
