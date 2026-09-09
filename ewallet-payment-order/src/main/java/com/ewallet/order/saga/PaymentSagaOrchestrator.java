package com.ewallet.order.saga;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Điều phối saga bốn bước cho mọi giao dịch ghi (F1–F4).
 *
 * <pre>
 *   S1 CREATE_ORDER  -> ghi đơn CREATED
 *   S2 AUTHORIZE     -> gRPC AuthorizePayment (áp rule, giữ tiền)
 *   S3 PARTNER_EXECUTE -> gRPC ExecutePartnerPayment (bỏ qua nếu không có partnerCode — F3)
 *   S4 CONFIRM       -> gRPC ConfirmPayment (chốt sổ, publish event)
 *   S3' COMPENSATE   -> gRPC ReversePayment khi bước sau AUTHORIZE thất bại (R-COMP-01)
 * </pre>
 *
 * <p>Cố ý KHÔNG bọc cả saga trong một transaction DB: các bước gọi ra ngoài qua gRPC,
 * giữ transaction xuyên suốt sẽ khoá kết nối DB trong lúc chờ mạng.</p>
 */
@Service
public class PaymentSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaOrchestrator.class);

    private static final int CONFIRM_MAX_ATTEMPTS = 3;
    private static final long[] CONFIRM_BACKOFF_MS = {200L, 500L};

    private static final String DEFAULT_CURRENCY = "VND";

    private final PaymentOrderRepository orderRepository;
    private final PaymentBusinessClient businessClient;
    private final SagaStepRecorder steps;

    public PaymentSagaOrchestrator(PaymentOrderRepository orderRepository,
                                   PaymentBusinessClient businessClient,
                                   SagaStepRecorder steps) {
        this.orderRepository = orderRepository;
        this.businessClient = businessClient;
        this.steps = steps;
    }

    // =====================================================================================
    // Đường chính
    // =====================================================================================

    public PaymentOrder run(OrderDtos.CreateOrderRequest request, String idempotencyKey) {
        // ---------------------------------------------------------------- S1 CREATE_ORDER
        Optional<PaymentOrder> replay = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            PaymentOrder existing = replay.get();
            if (!samePayload(existing, request)) {
                throw new DuplicateRequestException(idempotencyKey);          // R-IDEM-03
            }
            log.info("idempotent replay key={} tra ve orderId={}", idempotencyKey, existing.getId());
            return existing;                                                  // R-IDEM-02
        }

        long t0 = System.currentTimeMillis();
        PaymentOrder order = newOrder(request, idempotencyKey);
        orderRepository.save(order);
        steps.done(order.getId(), OrderStep.CREATE_ORDER, request.paymentType(),
                System.currentTimeMillis() - t0);

        // ---------------------------------------------------------------- S2 AUTHORIZE
        AuthorizePaymentResponse auth;
        long tAuth = System.currentTimeMillis();
        try {
            updateStatus(order, PaymentOrder.AUTHORIZING, null);
            auth = businessClient.authorize(AuthorizePaymentRequest.newBuilder()
                    .setOrderId(order.getId().toString())
                    .setCustomerId(order.getCustomerId())
                    .setPaymentType(order.getPaymentType())
                    .setAmount(order.getAmount())
                    .setCurrency(order.getCurrency())
                    .setPartnerCode(nullSafe(order.getPartnerCode()))
                    .setDestCustomerId(nullSafe(order.getDestCustomerId()))
                    .setBillCode(nullSafe(order.getBillCode()))
                    .setIdempotencyKey(nullSafe(idempotencyKey))
                    .build());
        } catch (RuntimeException e) {
            long ms = System.currentTimeMillis() - tAuth;
            log.error("S2 AUTHORIZE loi ha tang orderId={}", order.getId(), e);
            steps.failed(order.getId(), OrderStep.AUTHORIZE, e.toString(), 1, ms);
            // Chưa giữ tiền nên không cần bù trừ.
            return updateStatus(order, PaymentOrder.FAILED, "AUTHORIZE_UNAVAILABLE");
        }
        long authMs = System.currentTimeMillis() - tAuth;

        order.setFee(auth.getFee());
        order.setAmountVnd(auth.getAmountVnd());
        if (!auth.getLedgerTxnId().isBlank()) {
            order.setTxnId(UUID.fromString(auth.getLedgerTxnId()));
        }

        switch (auth.getStatus()) {
            case "REJECTED" -> {
                steps.failed(order.getId(), OrderStep.AUTHORIZE, auth.getReasonCode(), 1, authMs);
                log.info("S2 tu choi orderId={} reason={}", order.getId(), auth.getReasonCode());
                return updateStatus(order, PaymentOrder.REJECTED, auth.getReasonCode());
            }
            case "HELD" -> {
                // R-COMP-07: tiền tiếp tục bị giữ, KHÔNG tự bù trừ. Saga dừng tại đây.
                steps.record(order.getId(), OrderStep.AUTHORIZE, OrderStep.DONE,
                        auth.getReasonCode(), 1, authMs);
                log.info("S2 treo cho duyet orderId={} authMs={}", order.getId(), authMs);
                return updateStatus(order, PaymentOrder.HELD, auth.getReasonCode());
            }
            case "AUTHORIZED" -> {
                steps.done(order.getId(), OrderStep.AUTHORIZE, auth.getReasonCode(), authMs);
                updateStatus(order, PaymentOrder.AUTHORIZED, auth.getReasonCode());
            }
            default -> {
                steps.failed(order.getId(), OrderStep.AUTHORIZE,
                        "status la khong biet: " + auth.getStatus(), 1, authMs);
                return updateStatus(order, PaymentOrder.FAILED, "UNKNOWN_AUTH_STATUS");
            }
        }

        // ---------------------------------------------------------------- S3 PARTNER_EXECUTE
        if (hasPartner(order)) {
            long tExec = System.currentTimeMillis();
            ExecutePartnerPaymentResponse exec;
            try {
                updateStatus(order, PaymentOrder.EXECUTING, null);
                exec = businessClient.executePartner(ExecutePartnerPaymentRequest.newBuilder()
                        .setOrderId(order.getId().toString())
                        .setTxnId(order.getTxnId().toString())
                        .setPartnerCode(order.getPartnerCode())
                        .setPaymentType(order.getPaymentType())
                        .setAmount(order.getAmount())
                        .setCurrency(order.getCurrency())
                        .setAccountRef(nullSafe(order.getAccountRef()))
                        .setBillCode(nullSafe(order.getBillCode()))
                        .build());
            } catch (RuntimeException e) {
                long ms = System.currentTimeMillis() - tExec;
                log.error("S3 PARTNER_EXECUTE loi orderId={}", order.getId(), e);
                steps.failed(order.getId(), OrderStep.PARTNER_EXECUTE, e.toString(), 1, ms);
                return compensate(order, "PARTNER_TIMEOUT");
            }
            long execMs = System.currentTimeMillis() - tExec;

            if (!"SUCCESS".equals(exec.getStatus())) {
                steps.failed(order.getId(), OrderStep.PARTNER_EXECUTE, exec.getReasonCode(), 1, execMs);
                log.info("S3 doi tac tu choi orderId={} reason={}", order.getId(), exec.getReasonCode());
                return compensate(order, exec.getReasonCode());               // R-COMP-01
            }

            order.setPartnerRef(exec.getPartnerRef());
            steps.done(order.getId(), OrderStep.PARTNER_EXECUTE, exec.getPartnerRef(), execMs);
        }

        // ---------------------------------------------------------------- S4 CONFIRM
        updateStatus(order, PaymentOrder.CONFIRMING, null);
        for (int attempt = 1; attempt <= CONFIRM_MAX_ATTEMPTS; attempt++) {
            long tConf = System.currentTimeMillis();
            try {
                ConfirmPaymentResponse confirm = businessClient.confirm(ConfirmPaymentRequest.newBuilder()
                        .setOrderId(order.getId().toString())
                        .setTxnId(order.getTxnId().toString())
                        .setPartnerRef(nullSafe(order.getPartnerRef()))
                        .build());
                long confMs = System.currentTimeMillis() - tConf;

                if ("CAPTURED".equals(confirm.getStatus())) {
                    steps.record(order.getId(), OrderStep.CONFIRM, OrderStep.DONE,
                            confirm.getReasonCode(), attempt, confMs);
                    log.info("S4 hoan tat orderId={} balanceAfter={}",
                            order.getId(), confirm.getBalanceAfter());
                    return updateStatus(order, PaymentOrder.COMPLETED, confirm.getReasonCode());
                }
                steps.failed(order.getId(), OrderStep.CONFIRM, confirm.getReasonCode(), attempt, confMs);
                return compensate(order, "CONFIRM_FAILED");

            } catch (RuntimeException e) {
                long confMs = System.currentTimeMillis() - tConf;
                steps.failed(order.getId(), OrderStep.CONFIRM, e.toString(), attempt, confMs);
                log.warn("S4 CONFIRM that bai lan {} orderId={} loi={}", attempt, order.getId(), e.toString());
                if (attempt < CONFIRM_MAX_ATTEMPTS) {
                    sleep(CONFIRM_BACKOFF_MS[attempt - 1]);
                }
            }
        }

        return compensate(order, "CONFIRM_FAILED");
    }

    // =====================================================================================
    // F4d — Ops hoàn tiền chủ động
    // =====================================================================================

    public PaymentOrder refund(UUID orderId, String reason) {
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay don " + orderId));

        if (PaymentOrder.REFUNDED.equals(order.getStatus())) {
            throw new IllegalStateException("ALREADY_REFUNDED");               // R-COMP-06
        }
        if (!PaymentOrder.COMPLETED.equals(order.getStatus())) {
            throw new IllegalStateException("INVALID_STATE");
        }
        return compensate(order, reason == null || reason.isBlank() ? "CUSTOMER_REQUEST" : reason);
    }

    // =====================================================================================
    // Nhánh bù trừ
    // =====================================================================================

    /**
     * R-COMP-01: bước sau AUTHORIZE thất bại thì orchestrator phải gọi ReversePayment
     * TRƯỚC khi trả lỗi cho client. R-COMP-04: bù trừ hỏng thì đơn về FAILED và log ERROR.
     */
    private PaymentOrder compensate(PaymentOrder order, String reasonCode) {
        updateStatus(order, PaymentOrder.COMPENSATING, reasonCode);

        long t0 = System.currentTimeMillis();
        try {
            ReversePaymentResponse reverse = businessClient.reverse(ReversePaymentRequest.newBuilder()
                    .setOrderId(order.getId().toString())
                    .setTxnId(order.getTxnId().toString())
                    .setReasonCode(reasonCode)
                    .build());
            long ms = System.currentTimeMillis() - t0;

            if ("REVERSED".equals(reverse.getStatus())) {
                steps.compensated(order.getId(), reverse.getRefundTxnId(), ms);
                log.info("bu tru xong orderId={} reason={} balanceAfter={}",
                        order.getId(), reasonCode, reverse.getBalanceAfter());
                return updateStatus(order, PaymentOrder.REFUNDED, reasonCode);
            }

            steps.failed(order.getId(), OrderStep.COMPENSATE, reverse.getReasonCode(), 1, ms);
            log.error("BU TRU THAT BAI orderId={} reason={} — can nguoi xu ly",
                    order.getId(), reverse.getReasonCode());
            return updateStatus(order, PaymentOrder.FAILED, "COMPENSATION_FAILED");

        } catch (RuntimeException e) {
            long ms = System.currentTimeMillis() - t0;
            steps.failed(order.getId(), OrderStep.COMPENSATE, e.toString(), 1, ms);
            log.error("BU TRU THAT BAI orderId={} — can nguoi xu ly", order.getId(), e);
            return updateStatus(order, PaymentOrder.FAILED, "COMPENSATION_FAILED");
        }
    }

    // =====================================================================================
    // Helper
    // =====================================================================================

    private PaymentOrder newOrder(OrderDtos.CreateOrderRequest r, String idempotencyKey) {
        PaymentOrder o = new PaymentOrder();
        o.setId(UUID.randomUUID());
        o.setCustomerId(r.customerId());
        o.setPaymentType(r.paymentType().trim().toUpperCase());
        o.setAmount(r.amount());
        o.setCurrency(blankTo(r.currency(), DEFAULT_CURRENCY).toUpperCase());
        o.setPartnerCode(emptyToNull(r.partnerCode()));
        o.setDestCustomerId(emptyToNull(r.destCustomerId()));
        o.setBillCode(emptyToNull(r.billCode()));
        o.setAccountRef(emptyToNull(r.accountRef()));
        o.setIdempotencyKey(idempotencyKey);
        o.setStatus(PaymentOrder.CREATED);
        o.setFee(0L);
        o.setCreatedAt(OffsetDateTime.now());
        o.setUpdatedAt(OffsetDateTime.now());
        return o;
    }

    private PaymentOrder updateStatus(PaymentOrder order, String status, String reasonCode) {
        order.setStatus(status);
        if (reasonCode != null) {
            order.setReasonCode(reasonCode);
        }
        order.setUpdatedAt(OffsetDateTime.now());
        return orderRepository.save(order);
    }

    /** R-IDEM-02 vs R-IDEM-03: cùng key thì payload phải giống hệt. */
    private boolean samePayload(PaymentOrder existing, OrderDtos.CreateOrderRequest r) {
        return existing.getCustomerId().equals(r.customerId())
                && existing.getPaymentType().equalsIgnoreCase(r.paymentType())
                && existing.getAmount() == r.amount()
                && existing.getCurrency().equalsIgnoreCase(blankTo(r.currency(), DEFAULT_CURRENCY))
                && java.util.Objects.equals(existing.getPartnerCode(), emptyToNull(r.partnerCode()))
                && java.util.Objects.equals(existing.getDestCustomerId(), emptyToNull(r.destCustomerId()))
                && java.util.Objects.equals(existing.getBillCode(), emptyToNull(r.billCode()));
    }

    /** R-P2P-02: không có partnerCode thì saga bỏ hẳn bước S3. */
    private boolean hasPartner(PaymentOrder order) {
        return order.getPartnerCode() != null && !order.getPartnerCode().isBlank();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String blankTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
