package com.ewallet.payment.ledger;

import com.ewallet.payment.entity.AccountBalance;
import com.ewallet.payment.entity.LedgerEntry;
import com.ewallet.payment.entity.PaymentTransaction;
import com.ewallet.payment.repo.AccountBalanceRepository;
import com.ewallet.payment.repo.LedgerEntryRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sổ cái kép. Ba thao tác:
 * <ul>
 *   <li>{@link #authorize} — ghi bút toán PENDING (giữ tiền), chưa đụng số dư.</li>
 *   <li>{@link #capture} — PENDING chuyển POSTED và áp vào số dư.</li>
 *   <li>{@link #reverse} — bù trừ bằng bút toán ngược chiều, txn_id mới (R-LEDGER-02).</li>
 * </ul>
 * Sổ cái là append-only: không sửa, không xoá bút toán gốc.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountBalanceRepository accountBalanceRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository,
                         AccountBalanceRepository accountBalanceRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountBalanceRepository = accountBalanceRepository;
    }

    /**
     * R-LEDGER-01: ghi đúng 2 bút toán cho tiền gốc, cộng 2 bút toán nữa nếu có phí.
     * Tất cả ở trạng thái PENDING — số dư chưa đổi cho tới khi capture.
     */
    @Transactional
    public void authorize(PaymentTransaction txn, UUID sourceAccountId, UUID destAccountId,
                          long amountVnd, long fee, UUID feeAccountId) {
        List<LedgerEntry> entries = new ArrayList<>(4);
        entries.add(LedgerEntry.of(txn.getTxnId(), sourceAccountId, LedgerEntry.DEBIT, amountVnd,
                LedgerEntry.TYPE_PAYMENT));
        entries.add(LedgerEntry.of(txn.getTxnId(), destAccountId, LedgerEntry.CREDIT, amountVnd,
                LedgerEntry.TYPE_PAYMENT));

        if (fee > 0) {
            entries.add(LedgerEntry.of(txn.getTxnId(), sourceAccountId, LedgerEntry.DEBIT, fee,
                    LedgerEntry.TYPE_FEE));
            entries.add(LedgerEntry.of(txn.getTxnId(), feeAccountId, LedgerEntry.CREDIT, fee,
                    LedgerEntry.TYPE_FEE));
        }

        ledgerEntryRepository.saveAll(entries);
        log.debug("ghi {} but toan PENDING cho txnId={}", entries.size(), txn.getTxnId());
    }

    /** Chốt sổ: PENDING chuyển POSTED và áp vào số dư. */
    @Transactional
    public void capture(UUID txnId) {
        List<LedgerEntry> pending = ledgerEntryRepository.findByTxnIdOrderByIdAsc(txnId).stream()
                .filter(e -> LedgerEntry.PENDING.equals(e.getStatus()))
                .toList();
        if (pending.isEmpty()) {
            log.warn("capture txnId={} nhung khong con but toan PENDING", txnId);
            return;
        }
        applyToBalances(pending);
        pending.forEach(e -> e.setStatus(LedgerEntry.POSTED));
        ledgerEntryRepository.saveAll(pending);
        log.debug("chot {} but toan POSTED cho txnId={}", pending.size(), txnId);
    }

    /**
     * R-LEDGER-02: bù trừ bằng cặp bút toán ngược chiều mang txn_id mới, entry_type=REFUND.
     * Bút toán gốc chỉ đổi trạng thái sang REVERSED, nội dung giữ nguyên để truy vết.
     *
     * <p>Số dư chỉ được hoàn lại nếu bút toán gốc đã POSTED. Nếu giao dịch mới chỉ AUTHORIZED
     * (còn PENDING) thì số dư chưa từng đổi nên không cần chạm tới.</p>
     *
     * @return txn_id của bút toán bù trừ
     */
    @Transactional
    public UUID reverse(UUID originalTxnId) {
        List<LedgerEntry> originals = ledgerEntryRepository.findByTxnIdOrderByIdAsc(originalTxnId);
        if (originals.isEmpty()) {
            throw new IllegalStateException("Khong co but toan de bu tru cho txnId=" + originalTxnId);
        }

        boolean wasPosted = originals.stream()
                .anyMatch(e -> LedgerEntry.POSTED.equals(e.getStatus()));

        UUID refundTxnId = UUID.randomUUID();
        List<LedgerEntry> refunds = new ArrayList<>(originals.size());
        for (LedgerEntry original : originals) {
            LedgerEntry refund = LedgerEntry.of(refundTxnId, original.getAccountId(),
                    opposite(original.getDirection()), original.getAmount(), LedgerEntry.TYPE_REFUND);
            refund.setStatus(LedgerEntry.POSTED);
            refunds.add(refund);
            original.setStatus(LedgerEntry.REVERSED);
        }

        ledgerEntryRepository.saveAll(refunds);
        ledgerEntryRepository.saveAll(originals);

        if (wasPosted) {
            applyToBalances(refunds);
        }

        log.info("bu tru txnId={} bang refundTxnId={} wasPosted={}", originalTxnId, refundTxnId, wasPosted);
        return refundTxnId;
    }

    public long balanceOf(UUID accountId) {
        return accountBalanceRepository.findById(accountId)
                .map(AccountBalance::getBalance)
                .orElse(0L);
    }

    /**
     * Áp danh sách bút toán vào số dư.
     * R-P2P-07: khoá tài khoản theo thứ tự account_id tăng dần để hai giao dịch chéo nhau
     * không khoá lẫn nhau gây deadlock.
     */
    private void applyToBalances(List<LedgerEntry> entries) {
        Map<UUID, Long> deltaByAccount = new LinkedHashMap<>();
        for (LedgerEntry e : entries) {
            deltaByAccount.merge(e.getAccountId(), e.signedAmount(), Long::sum);
        }

        List<UUID> ordered = new ArrayList<>(deltaByAccount.keySet());
        ordered.sort(Comparator.naturalOrder());

        for (UUID accountId : ordered) {
            long delta = deltaByAccount.get(accountId);
            AccountBalance balance = accountBalanceRepository.findForUpdate(accountId)
                    .orElseGet(() -> {
                        AccountBalance fresh = new AccountBalance();
                        fresh.setAccountId(accountId);
                        fresh.setBalance(0L);
                        return fresh;
                    });
            balance.setBalance(balance.getBalance() + delta);
            balance.setUpdatedAt(OffsetDateTime.now());
            accountBalanceRepository.save(balance);
        }
    }

    private String opposite(String direction) {
        return LedgerEntry.DEBIT.equals(direction) ? LedgerEntry.CREDIT : LedgerEntry.DEBIT;
    }
}
