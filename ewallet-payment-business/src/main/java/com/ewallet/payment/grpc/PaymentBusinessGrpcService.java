package com.ewallet.payment.grpc;

import com.ewallet.payment.contract.grpc.AuthorizePaymentRequest;
import com.ewallet.payment.contract.grpc.AuthorizePaymentResponse;
import com.ewallet.payment.contract.grpc.PaymentBusinessServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage B: trả canned AUTHORIZED. Rule thật (limit / threshold / HELD / fee / ledger) + các lỗi
 * có chủ đích #1, #2, #5, #6 cài ở Stage C.
 */
@GrpcService
public class PaymentBusinessGrpcService
        extends PaymentBusinessServiceGrpc.PaymentBusinessServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentBusinessGrpcService.class);

    @Override
    public void authorizePayment(AuthorizePaymentRequest request,
                                 StreamObserver<AuthorizePaymentResponse> responseObserver) {
        log.info("gRPC AuthorizePayment orderId={} type={} amount={} currency={}",
                request.getOrderId(), request.getPaymentType(), request.getAmount(), request.getCurrency());

        AuthorizePaymentResponse response = AuthorizePaymentResponse.newBuilder()
                .setStatus("AUTHORIZED")
                .setReasonCode("STAGE_B_STUB")
                .setFee(0L)
                .setLedgerTxnId("")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
