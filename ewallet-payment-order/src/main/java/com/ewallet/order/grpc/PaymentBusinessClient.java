package com.ewallet.order.grpc;

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
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

/**
 * Client gRPC sang ewallet-payment-business. Chỉ bọc stub và gắn deadline;
 * quyết định nghiệp vụ nằm ở {@code saga.PaymentSagaOrchestrator}.
 *
 * <p>Deadline dài hơn timeout đối tác (3000ms ở third-party) để lỗi PARTNER_TIMEOUT
 * được phân loại đúng thay vì biến thành DEADLINE_EXCEEDED ở đây.</p>
 */
@Component
public class PaymentBusinessClient {

    private static final long DEFAULT_DEADLINE_MS = 5_000L;
    private static final long PARTNER_DEADLINE_MS = 10_000L;

    @GrpcClient("payment-business")
    private PaymentBusinessServiceGrpc.PaymentBusinessServiceBlockingStub stub;

    public AuthorizePaymentResponse authorize(AuthorizePaymentRequest request) {
        return withDeadline(DEFAULT_DEADLINE_MS).authorizePayment(request);
    }

    public ExecutePartnerPaymentResponse executePartner(ExecutePartnerPaymentRequest request) {
        return withDeadline(PARTNER_DEADLINE_MS).executePartnerPayment(request);
    }

    public ConfirmPaymentResponse confirm(ConfirmPaymentRequest request) {
        return withDeadline(DEFAULT_DEADLINE_MS).confirmPayment(request);
    }

    public ReversePaymentResponse reverse(ReversePaymentRequest request) {
        return withDeadline(DEFAULT_DEADLINE_MS).reversePayment(request);
    }

    public InquireBillResponse inquireBill(InquireBillRequest request) {
        return withDeadline(PARTNER_DEADLINE_MS).inquireBill(request);
    }

    private PaymentBusinessServiceGrpc.PaymentBusinessServiceBlockingStub withDeadline(long millis) {
        return stub.withDeadlineAfter(millis, TimeUnit.MILLISECONDS);
    }
}
