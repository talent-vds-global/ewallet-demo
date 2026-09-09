package com.ewallet.payment.service;

import com.ewallet.payment.client.ThirdPartyClient;
import com.ewallet.payment.domain.AuthorizeCommand;
import com.ewallet.payment.domain.AuthorizeResult;
import com.ewallet.payment.domain.BillInquiryResult;
import com.ewallet.payment.domain.ConfirmResult;
import com.ewallet.payment.domain.CurrencyConverter;
import com.ewallet.payment.domain.CurrencyNotSupportedException;
import com.ewallet.payment.domain.FeePolicy;
import com.ewallet.payment.domain.LimitPolicy;
import com.ewallet.payment.domain.PartnerExecutionResult;
import com.ewallet.payment.domain.PaymentType;
import com.ewallet.payment.domain.ReasonCodes;
import com.ewallet.payment.domain.ReverseResult;
import com.ewallet.payment.domain.ReviewPolicy;
import com.ewallet.payment.entity.Account;
import com.ewallet.payment.entity.PaymentTransaction;
import com.ewallet.payment.kafka.PaymentEventPublisher;
import com.ewallet.payment.ledger.LedgerService;
import com.ewallet.payment.repo.AccountRepository;
import com.ewallet.payment.repo.DailyUsageRepository;
import com.ewallet.payment.repo.PaymentTransactionRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ thanh toán. Bốn thao tác tương ứng bốn bước saga do payment-order điều phối:
 * authorize (S2) → executePartner (S3) → confirm (S4), và reverse (S3') khi phải bù trừ.
 *
 * <p>Spec: docs/specs/F1-topup-partner.md, F2-bill-telco.md, F3-p2p-transfer.md, F4-failure-refund.md.</p>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String SYSTEM_OWNER = "SYSTEM";
    private static final String TYPE_SUSPENSE = "SYSTEM_SUSPENSE";
    private static final String TYPE_FEE = "SYSTEM_FEE";
    private static final String TYPE_PARTNER_SETTLE = "PARTNER_SETTLE";

    private final AccountRepository accountRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final DailyUsageRepository dailyUsageRepository;
    private final LedgerService ledgerService;
    private final LimitPolicy limitPolicy;
    private final ReviewPolicy reviewPolicy;
    private final FeePolicy feePolicy;
    private final CurrencyConverter currencyConverter;
    private final ThirdPartyClient thirdPartyClient;
    private final PaymentEventPublisher eventPublisher;

    public PaymentService(AccountRepository accountRepository,
                          PaymentTransactionRepository transactionRepository,
                          DailyUsageRepository dailyUsageRepository,
                          LedgerService ledgerService,
                          LimitPolicy limitPolicy,
                          ReviewPolicy reviewPolicy,
                          FeePolicy feePolicy,
                          CurrencyConverter currencyConverter,
                          ThirdPartyClient thirdPartyClient,
                          PaymentEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.dailyUsageRepository = dailyUsageRepository;
        this.ledgerService = ledgerService;
        this.limitPolicy = limitPolicy;
        this.reviewPolicy = reviewPolicy;
        this.feePolicy = feePolicy;
        this.currencyConverter = currencyConverter;
        this.thirdPartyClient = thirdPartyClient;
        this.eventPublisher = eventPublisher;
    }

    // =====================================================================================
    // S2 — AUTHORIZE
    // =====================================================================================

    /**
     * Áp toàn bộ business rule, tính phí, giữ tiền (ledger PENDING) và cộng hạn mức ngày.
     * Trả về AUTHORIZED / HELD / REJECTED.
     */
    @Transactional
    public AuthorizeResult authorize(AuthorizeCommand cmd) {
        // Gọi lại cùng orderId thì trả kết quả cũ, không tạo giao dịch mới (R-IDEM-02).
        Optional<PaymentTransaction> replay =
                transactionRepository.findFirstByOrderIdAndReversedTxnIdIsNullOrderByCreatedAtAsc(cmd.orderId());
        if (replay.isPresent()) {
            PaymentTransaction existing = replay.get();
            log.info("authorize lap lai orderId={} tra ve txnId={} status={}",
                    cmd.orderId(), existing.getTxnId(), existing.getStatus());
            return replayResult(existing);
        }

        PaymentType type = cmd.paymentType();
        if (type == null) {
            return AuthorizeResult.rejected(ReasonCodes.INVALID_PAYMENT_TYPE, null, 0L);
        }

        // R-ACCOUNT-01 / R-ACCOUNT-02 — ví nguồn của khách
        Optional<Account> walletOpt = accountRepository.findWalletByCustomerId(cmd.customerId());
        if (walletOpt.isEmpty()) {
            return rejectWithoutLedger(cmd, type, 0L, ReasonCodes.ACCOUNT_NOT_FOUND);
        }
        Account wallet = walletOpt.get();
        if (!wallet.isActive()) {
            return rejectWithoutLedger(cmd, type, 0L, ReasonCodes.ACCOUNT_INACTIVE);
        }

        // R-CURRENCY-01 — quy đổi về VND trước mọi so sánh hạn mức
        long amountVnd;
        try {
            amountVnd = currencyConverter.toVnd(cmd.amount(), cmd.currency());
        } catch (CurrencyNotSupportedException e) {
            return rejectWithoutLedger(cmd, type, 0L, ReasonCodes.CURRENCY_NOT_SUPPORTED);
        }

        // R-AMOUNT-01 / R-AMOUNT-02
        String amountViolation = limitPolicy.checkAmount(type, amountVnd);
        if (amountViolation != null) {
            return rejectWithoutLedger(cmd, type, amountVnd, amountViolation);
        }

        // R-LIMIT-01 — hạn mức ngày (chứa lỗi có chủ đích #1 trong LimitPolicy)
        long usedToday = usedToday(cmd.customerId());
        String limitViolation = limitPolicy.checkDailyLimit(cmd.customerId(), usedToday, amountVnd);
        if (limitViolation != null) {
            return rejectWithoutLedger(cmd, type, amountVnd, limitViolation);
        }

        // Xác định hai đầu bút toán theo loại giao dịch
        Account source;
        Account dest;
        String counterpartyCustomerId = null;

        switch (type) {
            case TOP_UP -> {
                // R-TOPUP-01: tiền đến từ đối tác, đi qua tài khoản treo của hệ thống vào ví khách.
                Optional<Account> suspense = accountRepository.findSystemAccount(TYPE_SUSPENSE, SYSTEM_OWNER);
                if (suspense.isEmpty()) {
                    return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.ACCOUNT_NOT_FOUND);
                }
                if (isBlank(cmd.partnerCode())) {
                    return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.PARTNER_DECLINED);
                }
                source = suspense.get();
                dest = wallet;
            }
            case BILL, TELCO -> {
                // Tiền ra khỏi ví, về tài khoản quyết toán của đối tác.
                if (isBlank(cmd.partnerCode())) {
                    return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.PARTNER_DECLINED);
                }
                Optional<Account> settle =
                        accountRepository.findSystemAccount(TYPE_PARTNER_SETTLE, cmd.partnerCode());
                if (settle.isEmpty()) {
                    return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.PARTNER_DECLINED);
                }
                source = wallet;
                dest = settle.get();
            }
            case P2P -> {
                Optional<Account> destWallet = accountRepository.findWalletByCustomerId(cmd.destCustomerId());
                if (destWallet.isEmpty()) {
                    return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.ACCOUNT_NOT_FOUND);
                }
                Account receiver = destWallet.get();
                if (receiver.getId().equals(wallet.getId())) {          // R-P2P-01
                    return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.SELF_TRANSFER_NOT_ALLOWED);
                }
                if (!receiver.isActive()) {
                    return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.ACCOUNT_INACTIVE);
                }
                if (!wallet.getCurrency().equals(receiver.getCurrency())) {   // R-P2P-05
                    return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.CURRENCY_MISMATCH);
                }
                source = wallet;
                dest = receiver;
                counterpartyCustomerId = receiver.getCustomerId();
            }
            default -> {
                return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.INVALID_PAYMENT_TYPE);
            }
        }

        long fee = feePolicy.calculate(type, amountVnd);

        // R-BALANCE-01 — chỉ áp cho giao dịch trừ tiền ví khách
        if (type.debitsCustomerWallet()) {
            long balance = ledgerService.balanceOf(wallet.getId());
            if (balance < amountVnd + fee) {
                log.info("R-BALANCE-01 tu choi customerId={} balance={} can={}",
                        cmd.customerId(), balance, amountVnd + fee);
                return rejectWithoutLedger(cmd, type, amountVnd, ReasonCodes.INSUFFICIENT_FUNDS);
            }
        }

        PaymentTransaction txn = newTransaction(cmd, type, amountVnd, fee);
        txn.setSourceAccountId(source.getId());
        txn.setDestAccountId(dest.getId());
        txn.setCounterpartyCustomerId(counterpartyCustomerId);
        txn.setStatus(PaymentTransaction.AUTHORIZED);
        txn.setReasonCode(ReasonCodes.OK);
        transactionRepository.save(txn);

        UUID feeAccountId = accountRepository.findSystemAccount(TYPE_FEE, SYSTEM_OWNER)
                .map(Account::getId)
                .orElse(null);
        ledgerService.authorize(txn, source.getId(), dest.getId(), amountVnd, fee, feeAccountId);

        // R-USAGE-01
        dailyUsageRepository.addUsage(cmd.customerId(), LocalDate.now(), amountVnd);

        // R-REVIEW-01 — giao dịch lớn phải treo chờ duyệt
        if (reviewPolicy.requiresManualReview(amountVnd)) {
            txn.setStatus(PaymentTransaction.HELD);
            txn.setReasonCode(ReasonCodes.MANUAL_REVIEW);
            txn.setUpdatedAt(OffsetDateTime.now());
            transactionRepository.save(txn);

            // Bước rà soát này chậm 700ms — lỗi có chủ đích #5 nằm trong ReviewPolicy.
            reviewPolicy.runManualReviewScreening(cmd.customerId(), amountVnd);

            // --------------------------------------------------------------------------------
            // LỖI CÓ CHỦ ĐÍCH #2 — NHÁNH QUÊN PUBLISH EVENT.
            // KHÔNG SỬA (hợp đồng nghiệm thu, architecture.md §6).
            //
            // Spec R-REVIEW-01 + R-EVENT-01 bắt buộc publish PaymentHeld ở đây
            // (eventPublisher.publishHeld(txn)) để notification báo khách và order-status-cg
            // chốt trạng thái đơn. Nhánh này return thẳng, không publish gì cả.
            // Hệ quả quan sát được: trace của giao dịch HELD không có span producer nào.
            // --------------------------------------------------------------------------------
            log.info("giao dich treo cho duyet orderId={} txnId={} amountVnd={}",
                    cmd.orderId(), txn.getTxnId(), amountVnd);
            return new AuthorizeResult(AuthorizeResult.HELD, ReasonCodes.MANUAL_REVIEW,
                    fee, txn.getTxnId(), amountVnd);
        }

        log.info("authorize thanh cong orderId={} txnId={} type={} amountVnd={} fee={}",
                cmd.orderId(), txn.getTxnId(), type, amountVnd, fee);
        return new AuthorizeResult(AuthorizeResult.AUTHORIZED, ReasonCodes.OK, fee, txn.getTxnId(), amountVnd);
    }

    // =====================================================================================
    // S3 — EXECUTE PARTNER
    // =====================================================================================

    /** Gọi đối tác qua third-party. Chỉ dùng cho TOP_UP / BILL / TELCO. */
    @Transactional
    public PartnerExecutionResult executePartner(UUID orderId, UUID txnId, String partnerCode,
                                                 String paymentType, long amount, String currency,
                                                 String accountRef, String billCode) {
        Optional<PaymentTransaction> txnOpt = transactionRepository.findById(txnId);
        if (txnOpt.isEmpty()) {
            log.warn("executePartner khong tim thay txnId={}", txnId);
            return new PartnerExecutionResult(PartnerExecutionResult.DECLINED,
                    ReasonCodes.TXN_NOT_FOUND, "", 0L);
        }
        PaymentTransaction txn = txnOpt.get();

        // R-TOPUP-06: giao dịch đã chốt sổ thì không cho gọi đối tác lần nữa.
        if (PaymentTransaction.CAPTURED.equals(txn.getStatus())) {
            return new PartnerExecutionResult(PartnerExecutionResult.SUCCESS,
                    ReasonCodes.OK, nullSafe(txn.getPartnerRef()), 0L);
        }

        PartnerExecutionResult result = thirdPartyClient.execute(
                orderId, partnerCode, paymentType, amount, currency, accountRef, billCode);

        if (result.isSuccess()) {
            txn.setPartnerRef(result.partnerRef());
            txn.setUpdatedAt(OffsetDateTime.now());
            transactionRepository.save(txn);
        }
        return result;
    }

    // =====================================================================================
    // S4 — CONFIRM
    // =====================================================================================

    /** Chốt sổ: ledger PENDING chuyển POSTED, cập nhật số dư, publish PaymentCompleted. */
    @Transactional
    public ConfirmResult confirm(UUID orderId, UUID txnId, String partnerRef) {
        Optional<PaymentTransaction> txnOpt = transactionRepository.findById(txnId);
        if (txnOpt.isEmpty()) {
            return ConfirmResult.error(ReasonCodes.TXN_NOT_FOUND);
        }
        PaymentTransaction txn = txnOpt.get();

        if (PaymentTransaction.CAPTURED.equals(txn.getStatus())) {
            // Gọi lại lần hai thì trả kết quả cũ, không chốt sổ hai lần.
            return new ConfirmResult(ConfirmResult.CAPTURED, ReasonCodes.OK, walletBalance(txn.getCustomerId()));
        }
        if (!PaymentTransaction.AUTHORIZED.equals(txn.getStatus())) {
            log.warn("confirm sai trang thai txnId={} status={}", txnId, txn.getStatus());
            return ConfirmResult.error(ReasonCodes.INVALID_STATE);
        }

        ledgerService.capture(txnId);

        txn.setStatus(PaymentTransaction.CAPTURED);
        txn.setReasonCode(ReasonCodes.OK);
        if (!isBlank(partnerRef)) {
            txn.setPartnerRef(partnerRef);
        }
        txn.setUpdatedAt(OffsetDateTime.now());
        transactionRepository.save(txn);

        eventPublisher.publishCompleted(txn);

        long balanceAfter = walletBalance(txn.getCustomerId());
        log.info("confirm thanh cong orderId={} txnId={} balanceAfter={}", orderId, txnId, balanceAfter);
        return new ConfirmResult(ConfirmResult.CAPTURED, ReasonCodes.OK, balanceAfter);
    }

    // =====================================================================================
    // S3' — REVERSE (bù trừ)
    // =====================================================================================

    /**
     * Bù trừ giao dịch: sinh bút toán ngược chiều với txn_id mới, hoàn số dư và hạn mức ngày,
     * publish PaymentRefunded. R-COMP-02, R-COMP-03, R-COMP-05.
     */
    @Transactional
    public ReverseResult reverse(UUID orderId, UUID txnId, String reasonCode) {
        Optional<PaymentTransaction> txnOpt = transactionRepository.findById(txnId);
        if (txnOpt.isEmpty()) {
            return ReverseResult.error(ReasonCodes.TXN_NOT_FOUND);
        }
        PaymentTransaction original = txnOpt.get();

        // R-COMP-05: gọi bù trừ nhiều lần cho cùng txn phải idempotent.
        if (PaymentTransaction.REVERSED.equals(original.getStatus())) {
            UUID existingRefund = transactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                    .filter(t -> txnId.equals(t.getReversedTxnId()))
                    .map(PaymentTransaction::getTxnId)
                    .findFirst()
                    .orElse(null);
            log.info("bu tru lap lai txnId={} tra ve refundTxnId={}", txnId, existingRefund);
            return new ReverseResult(ReverseResult.REVERSED, ReasonCodes.ALREADY_REVERSED,
                    existingRefund, walletBalance(original.getCustomerId()));
        }

        boolean reversible = PaymentTransaction.AUTHORIZED.equals(original.getStatus())
                || PaymentTransaction.CAPTURED.equals(original.getStatus())
                || PaymentTransaction.HELD.equals(original.getStatus());
        if (!reversible) {
            log.warn("reverse sai trang thai txnId={} status={}", txnId, original.getStatus());
            return ReverseResult.error(ReasonCodes.INVALID_STATE);
        }

        UUID refundTxnId = ledgerService.reverse(txnId);

        PaymentTransaction refund = new PaymentTransaction();
        refund.setTxnId(refundTxnId);
        refund.setOrderId(orderId);
        refund.setCustomerId(original.getCustomerId());
        refund.setPaymentType(PaymentType.REFUND.name());
        refund.setAmount(original.getAmount());
        refund.setFee(0L);                                  // R-FEE-05
        refund.setCurrency(original.getCurrency());
        refund.setAmountVnd(original.getAmountVnd());
        refund.setSourceAccountId(original.getDestAccountId());
        refund.setDestAccountId(original.getSourceAccountId());
        refund.setCounterpartyCustomerId(original.getCounterpartyCustomerId());
        refund.setPartnerCode(original.getPartnerCode());
        refund.setPartnerRef(original.getPartnerRef());
        refund.setReversedTxnId(original.getTxnId());
        refund.setStatus(PaymentTransaction.CAPTURED);
        refund.setReasonCode(reasonCode);
        refund.setCreatedAt(OffsetDateTime.now());
        refund.setUpdatedAt(OffsetDateTime.now());
        transactionRepository.save(refund);

        original.setStatus(PaymentTransaction.REVERSED);
        original.setReasonCode(reasonCode);
        original.setUpdatedAt(OffsetDateTime.now());
        transactionRepository.save(original);

        // R-USAGE-02: trả lại hạn mức của đúng ngày giao dịch gốc.
        LocalDate usageDate = original.getCreatedAt() != null
                ? original.getCreatedAt().toLocalDate()
                : LocalDate.now();
        dailyUsageRepository.addUsage(original.getCustomerId(), usageDate, -original.getAmountVnd());

        eventPublisher.publishRefunded(refund);

        long balanceAfter = walletBalance(original.getCustomerId());
        log.info("bu tru xong orderId={} txnId={} refundTxnId={} reason={} balanceAfter={}",
                orderId, txnId, refundTxnId, reasonCode, balanceAfter);
        return new ReverseResult(ReverseResult.REVERSED, reasonCode, refundTxnId, balanceAfter);
    }

    // =====================================================================================
    // S0 — INQUIRE BILL (chỉ đọc)
    // =====================================================================================

    public BillInquiryResult inquireBill(String partnerCode, String billCode) {
        return thirdPartyClient.inquireBill(partnerCode, billCode);
    }

    // =====================================================================================
    // Helper
    // =====================================================================================

    /** Từ chối trước khi ghi sổ: có dấu vết transaction REJECTED nhưng không có bút toán. */
    private AuthorizeResult rejectWithoutLedger(AuthorizeCommand cmd, PaymentType type,
                                                long amountVnd, String reasonCode) {
        PaymentTransaction txn = newTransaction(cmd, type, amountVnd, 0L);
        txn.setStatus(PaymentTransaction.REJECTED);
        txn.setReasonCode(reasonCode);
        transactionRepository.save(txn);

        eventPublisher.publishFailed(txn);   // R-EVENT-01

        log.info("authorize tu choi orderId={} reason={} amountVnd={}", cmd.orderId(), reasonCode, amountVnd);
        return AuthorizeResult.rejected(reasonCode, txn.getTxnId(), amountVnd);
    }

    private PaymentTransaction newTransaction(AuthorizeCommand cmd, PaymentType type,
                                              long amountVnd, long fee) {
        PaymentTransaction txn = new PaymentTransaction();
        txn.setTxnId(UUID.randomUUID());
        txn.setOrderId(cmd.orderId());
        txn.setCustomerId(cmd.customerId());
        txn.setPaymentType(type == null ? PaymentType.P2P.name() : type.name());
        txn.setAmount(cmd.amount());
        txn.setFee(fee);
        txn.setCurrency(cmd.currency() == null || cmd.currency().isBlank()
                ? CurrencyConverter.VND : cmd.currency());
        txn.setAmountVnd(amountVnd);
        txn.setPartnerCode(isBlank(cmd.partnerCode()) ? null : cmd.partnerCode());
        txn.setCreatedAt(OffsetDateTime.now());
        txn.setUpdatedAt(OffsetDateTime.now());
        return txn;
    }

    private AuthorizeResult replayResult(PaymentTransaction existing) {
        String status = switch (existing.getStatus()) {
            case PaymentTransaction.HELD -> AuthorizeResult.HELD;
            case PaymentTransaction.REJECTED -> AuthorizeResult.REJECTED;
            default -> AuthorizeResult.AUTHORIZED;
        };
        return new AuthorizeResult(status, existing.getReasonCode(), existing.getFee(),
                existing.getTxnId(), existing.getAmountVnd());
    }

    private long usedToday(String customerId) {
        Long used = dailyUsageRepository.findTotalAmount(customerId, LocalDate.now());
        return used == null ? 0L : used;
    }

    private long walletBalance(String customerId) {
        return accountRepository.findWalletByCustomerId(customerId)
                .map(a -> ledgerService.balanceOf(a.getId()))
                .orElse(0L);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
