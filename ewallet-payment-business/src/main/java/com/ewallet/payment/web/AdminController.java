package com.ewallet.payment.web;

import com.ewallet.payment.entity.Account;
import com.ewallet.payment.entity.LedgerEntry;
import com.ewallet.payment.entity.LimitConfig;
import com.ewallet.payment.entity.PaymentTransaction;
import com.ewallet.payment.ledger.LedgerService;
import com.ewallet.payment.repo.AccountRepository;
import com.ewallet.payment.repo.DailyUsageRepository;
import com.ewallet.payment.repo.LedgerEntryRepository;
import com.ewallet.payment.repo.LimitConfigRepository;
import com.ewallet.payment.repo.PaymentTransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint nội bộ cho Ops / Lead và cho việc kiểm chứng khi demo.
 * Không phải đường đi của khách hàng — nghiệp vụ thật đi qua gRPC.
 * Hợp đồng: docs/specs/01-api-contracts.md §6.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final LimitConfigRepository limitConfigRepository;
    private final AccountRepository accountRepository;
    private final DailyUsageRepository dailyUsageRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerService ledgerService;

    public AdminController(LimitConfigRepository limitConfigRepository,
                           AccountRepository accountRepository,
                           DailyUsageRepository dailyUsageRepository,
                           PaymentTransactionRepository transactionRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           LedgerService ledgerService) {
        this.limitConfigRepository = limitConfigRepository;
        this.accountRepository = accountRepository;
        this.dailyUsageRepository = dailyUsageRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerService = ledgerService;
    }

    @GetMapping("/ping")
    @Operation(summary = "Smoke test - service va paymentdb con song khong")
    public Map<String, Object> ping() {
        long rows = limitConfigRepository.count();
        return Map.of("service", "ewallet-payment-business", "status", "UP", "limitConfigRows", rows);
    }

    /**
     * Hạn mức đang lưu trong DB. Dùng để đối chiếu spec drift: giá trị ở đây phải khớp
     * với R-LIMIT-01 trong spec và với hằng số trong code.
     */
    @GetMapping("/limits")
    @Operation(summary = "Han muc dang luu trong limit_config")
    public Map<String, Object> limits() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (LimitConfig c : limitConfigRepository.findAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("limitValue", c.getLimitValue());
            item.put("currency", c.getCurrency());
            items.add(item);
        }
        return Map.of("count", items.size(), "items", items);
    }

    @GetMapping("/accounts/{customerId}/balance")
    @Operation(summary = "So du vi va han muc da dung trong ngay")
    public ResponseEntity<Map<String, Object>> balance(@PathVariable String customerId) {
        Optional<Account> wallet = accountRepository.findWalletByCustomerId(customerId);
        if (wallet.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "code", "ACCOUNT_NOT_FOUND",
                    "message", "Khong tim thay vi cua " + customerId));
        }
        Account account = wallet.get();
        LocalDate today = LocalDate.now();
        Long used = dailyUsageRepository.findTotalAmount(customerId, today);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("date", today.toString());
        usage.put("totalAmount", used == null ? 0L : used);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("accountId", account.getId().toString());
        body.put("balance", ledgerService.balanceOf(account.getId()));
        body.put("currency", account.getCurrency());
        body.put("status", account.getStatus());
        body.put("dailyUsage", usage);
        return ResponseEntity.ok(body);
    }

    /** Transaction và toàn bộ bút toán của một order — dùng để kiểm chứng sổ có cân sau khi bù trừ. */
    @GetMapping("/transactions/{orderId}")
    @Operation(summary = "Transaction va but toan cua mot order")
    public ResponseEntity<Map<String, Object>> transactions(@PathVariable String orderId) {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "BAD_REQUEST", "message", "orderId khong hop le"));
        }

        List<PaymentTransaction> txns = transactionRepository.findByOrderIdOrderByCreatedAtAsc(id);
        if (txns.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "code", "TXN_NOT_FOUND", "message", "Khong co giao dich cho order " + orderId));
        }

        List<UUID> txnIds = txns.stream().map(PaymentTransaction::getTxnId).toList();
        List<LedgerEntry> entries = ledgerEntryRepository.findByTxnIdInOrderByIdAsc(txnIds);

        List<Map<String, Object>> txnItems = new ArrayList<>();
        for (PaymentTransaction t : txns) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("txnId", t.getTxnId().toString());
            item.put("paymentType", t.getPaymentType());
            item.put("amount", t.getAmount());
            item.put("fee", t.getFee());
            item.put("currency", t.getCurrency());
            item.put("amountVnd", t.getAmountVnd());
            item.put("status", t.getStatus());
            item.put("reasonCode", t.getReasonCode());
            item.put("partnerCode", t.getPartnerCode());
            item.put("partnerRef", t.getPartnerRef());
            item.put("reversedTxnId", t.getReversedTxnId() == null ? null : t.getReversedTxnId().toString());
            item.put("createdAt", String.valueOf(t.getCreatedAt()));
            txnItems.add(item);
        }

        long sum = 0L;
        List<Map<String, Object>> entryItems = new ArrayList<>();
        for (LedgerEntry e : entries) {
            sum += e.signedAmount();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getId());
            item.put("txnId", e.getTxnId().toString());
            item.put("accountId", e.getAccountId().toString());
            item.put("direction", e.getDirection());
            item.put("amount", e.getAmount());
            item.put("entryType", e.getEntryType());
            item.put("status", e.getStatus());
            entryItems.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("transactions", txnItems);
        body.put("ledgerEntries", entryItems);
        // R-LEDGER-01: tổng có dấu của mọi bút toán phải bằng 0 thì sổ mới cân.
        body.put("ledgerBalanced", sum == 0L);
        body.put("ledgerSum", sum);
        return ResponseEntity.ok(body);
    }
}
