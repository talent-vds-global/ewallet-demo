package com.ewallet.payment.grpc;

import com.ewallet.payment.contract.grpc.AuthorizePaymentRequest;
import com.ewallet.payment.contract.grpc.AuthorizePaymentResponse;
import com.ewallet.payment.contract.grpc.ConfirmPaymentRequest;
import com.ewallet.payment.contract.grpc.ConfirmPaymentResponse;
import com.ewallet.payment.contract.grpc.ExecutePartnerPaymentRequest;
import com.ewallet.payment.contract.grpc.ExecutePartnerPaymentResponse;
import com.ewallet.payment.contract.grpc.InquireBillRequest;
import com.ewallet.payment.contract.grpc.InquireBillResponse;
import com.ewallet.payment.contract.grpc.PaymentBusinessServiceGrpc;
import com.ewallet.payment.contract.grpc.ReversePaymentRequest;
import com.ewallet.payment.contract.grpc.ReversePaymentResponse;
import com.ewallet.payment.domain.AuthorizeCommand;
import com.ewallet.payment.domain.AuthorizeResult;
import com.ewallet.payment.domain.BillInquiryResult;
import com.ewallet.payment.domain.ConfirmResult;
import com.ewallet.payment.domain.CurrencyConverter;
import com.ewallet.payment.domain.PartnerExecutionResult;
import com.ewallet.payment.domain.PaymentType;
import com.ewallet.payment.domain.ReasonCodes;
import com.ewallet.payment.domain.ReverseResult;
import com.ewallet.payment.service.PaymentService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server cho payment-order (contract: contracts/proto/payment.proto).
 * Năm method tương ứng các bước saga: AuthorizePayment, ExecutePartnerPayment,
 * ConfirmPayment, ReversePayment, InquireBill.
 *
 * <p>Handler chỉ làm việc ánh xạ proto ↔ domain; nghiệp vụ nằm ở {@link PaymentService}.</p>
 */
@GrpcService
public class PaymentBusinessGrpcService
        extends PaymentBusinessServiceGrpc.PaymentBusinessServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentBusinessGrpcService.class);

    private final PaymentService paymentService;

    public PaymentBusinessGrpcService(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Override
    public void authorizePayment(AuthorizePaymentRequest request,
                                 StreamObserver<AuthorizePaymentResponse> responseObserver) {
        log.info("gRPC AuthorizePayment orderId={} type={} amount={} currency={}",
                request.getOrderId(), request.getPaymentType(), request.getAmount(), request.getCurrency());

        UUID orderId = parseUuid(request.getOrderId());
        if (orderId == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("orderId khong hop le: " + request.getOrderId())
                    .asRuntimeException());
            return;
        }

        // ------------------------------------------------------------------------------------
        // LỖI CÓ CHỦ ĐÍCH #6 — DRIFT MANG QUA gRPC. KHÔNG SỬA (architecture.md §6).
        //
        // Spec R-CURRENCY-01 / R-FX-01 bắt buộc lấy request.getCurrency() rồi quy đổi sang VND
        // TRƯỚC khi áp R-AMOUNT-02, R-LIMIT-01, R-REVIEW-01.
        // Handler này bỏ qua field currency của request và luôn coi giao dịch là VND, nên
        // giao dịch ngoại tệ bị áp hạn mức VND (1.000 USD bị tính như 1.000đ).
        // Chỉ phát hiện được nếu collector thu được attribute của span gRPC (giá trị currency
        // trong request) rồi đối chiếu với amount_vnd đã lưu.
        // ------------------------------------------------------------------------------------
        final String currency = CurrencyConverter.VND;

        AuthorizeCommand command = new AuthorizeCommand(
                orderId,
                request.getCustomerId(),
                PaymentType.from(request.getPaymentType()),
                request.getAmount(),
                currency,
                emptyToNull(request.getDestCustomerId()),
                emptyToNull(request.getPartnerCode()),
                emptyToNull(request.getBillCode()),
                emptyToNull(request.getIdempotencyKey()));

        try {
            AuthorizeResult result = paymentService.authorize(command);
            AuthorizePaymentResponse response = AuthorizePaymentResponse.newBuilder()
                    .setStatus(nullSafe(result.status()))
                    .setReasonCode(nullSafe(result.reasonCode()))
                    .setFee(result.fee())
                    .setLedgerTxnId(result.txnId() == null ? "" : result.txnId().toString())
                    .setAmountVnd(result.amountVnd())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            log.error("AuthorizePayment loi orderId={}", request.getOrderId(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                    .withCause(e).asRuntimeException());
        }
    }

    @Override
    public void executePartnerPayment(ExecutePartnerPaymentRequest request,
                                      StreamObserver<ExecutePartnerPaymentResponse> responseObserver) {
        log.info("gRPC ExecutePartnerPayment orderId={} partnerCode={} amount={}",
                request.getOrderId(), request.getPartnerCode(), request.getAmount());

        UUID orderId = parseUuid(request.getOrderId());
        UUID txnId = parseUuid(request.getTxnId());
        if (orderId == null || txnId == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("orderId hoac txnId khong hop le").asRuntimeException());
            return;
        }

        try {
            PartnerExecutionResult result = paymentService.executePartner(
                    orderId, txnId, request.getPartnerCode(), request.getPaymentType(),
                    request.getAmount(), request.getCurrency(),
                    request.getAccountRef(), request.getBillCode());

            ExecutePartnerPaymentResponse response = ExecutePartnerPaymentResponse.newBuilder()
                    .setStatus(nullSafe(result.status()))
                    .setReasonCode(nullSafe(result.reasonCode()))
                    .setPartnerRef(nullSafe(result.partnerRef()))
                    .setElapsedMs(result.elapsedMs())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            log.error("ExecutePartnerPayment loi orderId={}", request.getOrderId(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                    .withCause(e).asRuntimeException());
        }
    }

    @Override
    public void confirmPayment(ConfirmPaymentRequest request,
                               StreamObserver<ConfirmPaymentResponse> responseObserver) {
        log.info("gRPC ConfirmPayment orderId={} txnId={}", request.getOrderId(), request.getTxnId());

        UUID orderId = parseUuid(request.getOrderId());
        UUID txnId = parseUuid(request.getTxnId());
        if (orderId == null || txnId == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("orderId hoac txnId khong hop le").asRuntimeException());
            return;
        }

        try {
            ConfirmResult result = paymentService.confirm(orderId, txnId, request.getPartnerRef());
            ConfirmPaymentResponse response = ConfirmPaymentResponse.newBuilder()
                    .setStatus(nullSafe(result.status()))
                    .setReasonCode(nullSafe(result.reasonCode()))
                    .setBalanceAfter(result.balanceAfter())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            log.error("ConfirmPayment loi orderId={}", request.getOrderId(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                    .withCause(e).asRuntimeException());
        }
    }

    @Override
    public void reversePayment(ReversePaymentRequest request,
                               StreamObserver<ReversePaymentResponse> responseObserver) {
        log.info("gRPC ReversePayment orderId={} txnId={} reason={}",
                request.getOrderId(), request.getTxnId(), request.getReasonCode());

        UUID orderId = parseUuid(request.getOrderId());
        UUID txnId = parseUuid(request.getTxnId());
        if (orderId == null || txnId == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("orderId hoac txnId khong hop le").asRuntimeException());
            return;
        }

        try {
            String reason = request.getReasonCode().isBlank()
                    ? ReasonCodes.PARTNER_DECLINED : request.getReasonCode();
            ReverseResult result = paymentService.reverse(orderId, txnId, reason);

            ReversePaymentResponse response = ReversePaymentResponse.newBuilder()
                    .setStatus(nullSafe(result.status()))
                    .setReasonCode(nullSafe(result.reasonCode()))
                    .setRefundTxnId(result.refundTxnId() == null ? "" : result.refundTxnId().toString())
                    .setBalanceAfter(result.balanceAfter())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            log.error("ReversePayment loi orderId={}", request.getOrderId(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                    .withCause(e).asRuntimeException());
        }
    }

    @Override
    public void inquireBill(InquireBillRequest request,
                            StreamObserver<InquireBillResponse> responseObserver) {
        log.info("gRPC InquireBill partnerCode={} billCode={}",
                request.getPartnerCode(), request.getBillCode());

        try {
            BillInquiryResult result = paymentService.inquireBill(
                    request.getPartnerCode(), request.getBillCode());

            InquireBillResponse response = InquireBillResponse.newBuilder()
                    .setStatus(nullSafe(result.status()))
                    .setBillCode(nullSafe(result.billCode()))
                    .setCustomerName(nullSafe(result.customerName()))
                    .setPeriod(nullSafe(result.period()))
                    .setAmount(result.amount())
                    .setCurrency(nullSafe(result.currency()))
                    .setBillStatus(nullSafe(result.billStatus()))
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            log.error("InquireBill loi billCode={}", request.getBillCode(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage())
                    .withCause(e).asRuntimeException());
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
