package com.ewallet.payment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Endpoint nội bộ /admin (docs/specs/01-api-contracts.md §6).
 *
 * <p>Dựng MockMvc kiểu standalone: không nâng Spring context, không cần DB —
 * chỉ kiểm tra tầng web (định tuyến, mã HTTP, hình dạng JSON).</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    private static final String CUSTOMER = "CUST-001";
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PARTNER_ID = UUID.fromString("00000000-0000-4000-8000-00000000000e");

    @Mock private LimitConfigRepository limitConfigRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private DailyUsageRepository dailyUsageRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private LedgerService ledgerService;

    @InjectMocks
    private AdminController adminController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminController).build();
    }

    private static LimitConfig limit(String id, long value) {
        LimitConfig c = new LimitConfig();
        c.setId(id);
        c.setLimitValue(value);
        c.setCurrency("VND");
        return c;
    }

    private static Account wallet(String status) {
        Account a = new Account();
        a.setId(WALLET_ID);
        a.setCustomerId(CUSTOMER);
        a.setAccountType("WALLET");
        a.setCurrency("VND");
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("GET /admin/ping báo service sống và đếm được số dòng cấu hình")
    void ping() throws Exception {
        when(limitConfigRepository.count()).thenReturn(7L);

        mockMvc.perform(get("/admin/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("ewallet-payment-business"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.limitConfigRows").value(7));
    }

    @Test
    @DisplayName("GET /admin/limits trả hạn mức đang lưu trong DB để đối chiếu spec")
    void limits() throws Exception {
        when(limitConfigRepository.findAll()).thenReturn(List.of(
                limit("DAILY_TRANSFER_LIMIT", 50_000_000L),
                limit("REVIEW_THRESHOLD", 20_000_000L)));

        mockMvc.perform(get("/admin/limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.items[0].id").value("DAILY_TRANSFER_LIMIT"))
                .andExpect(jsonPath("$.items[0].limitValue").value(50_000_000L))
                .andExpect(jsonPath("$.items[0].currency").value("VND"))
                .andExpect(jsonPath("$.items[1].id").value("REVIEW_THRESHOLD"));
    }

    @Test
    @DisplayName("GET /admin/limits khi bảng rỗng trả danh sách rỗng chứ không lỗi")
    void limitsRong() throws Exception {
        when(limitConfigRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/admin/limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Nested
    @DisplayName("GET /admin/accounts/{customerId}/balance")
    class Balance {

        @Test
        @DisplayName("trả số dư ví kèm hạn mức đã dùng trong ngày")
        void coVi() throws Exception {
            when(accountRepository.findWalletByCustomerId(CUSTOMER)).thenReturn(Optional.of(wallet("ACTIVE")));
            when(ledgerService.balanceOf(WALLET_ID)).thenReturn(4_200_000L);
            when(dailyUsageRepository.findTotalAmount(CUSTOMER, LocalDate.now())).thenReturn(1_500_000L);

            mockMvc.perform(get("/admin/accounts/{customerId}/balance", CUSTOMER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value(CUSTOMER))
                    .andExpect(jsonPath("$.accountId").value(WALLET_ID.toString()))
                    .andExpect(jsonPath("$.balance").value(4_200_000L))
                    .andExpect(jsonPath("$.currency").value("VND"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.dailyUsage.date").value(LocalDate.now().toString()))
                    .andExpect(jsonPath("$.dailyUsage.totalAmount").value(1_500_000L));
        }

        @Test
        @DisplayName("chưa phát sinh giao dịch trong ngày thì hạn mức đã dùng là 0")
        void chuaDungHanMuc() throws Exception {
            when(accountRepository.findWalletByCustomerId(CUSTOMER)).thenReturn(Optional.of(wallet("ACTIVE")));
            when(dailyUsageRepository.findTotalAmount(CUSTOMER, LocalDate.now())).thenReturn(null);

            mockMvc.perform(get("/admin/accounts/{customerId}/balance", CUSTOMER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dailyUsage.totalAmount").value(0));
        }

        @Test
        @DisplayName("ví bị khoá vẫn xem được số dư, trạng thái báo LOCKED")
        void viBiKhoa() throws Exception {
            when(accountRepository.findWalletByCustomerId(CUSTOMER)).thenReturn(Optional.of(wallet("LOCKED")));

            mockMvc.perform(get("/admin/accounts/{customerId}/balance", CUSTOMER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("LOCKED"));
        }

        @Test
        @DisplayName("không có ví thì trả 404 ACCOUNT_NOT_FOUND")
        void khongCoVi() throws Exception {
            when(accountRepository.findWalletByCustomerId("CUST-999")).thenReturn(Optional.empty());

            mockMvc.perform(get("/admin/accounts/{customerId}/balance", "CUST-999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("GET /admin/transactions/{orderId}")
    class Transactions {

        private final UUID orderId = UUID.randomUUID();
        private final UUID txnId = UUID.randomUUID();

        private PaymentTransaction txn() {
            PaymentTransaction t = new PaymentTransaction();
            t.setTxnId(txnId);
            t.setOrderId(orderId);
            t.setCustomerId(CUSTOMER);
            t.setPaymentType("P2P");
            t.setAmount(3_000_000L);
            t.setFee(2_200L);
            t.setCurrency("VND");
            t.setAmountVnd(3_000_000L);
            t.setStatus(PaymentTransaction.CAPTURED);
            t.setReasonCode("OK");
            t.setCreatedAt(OffsetDateTime.now());
            return t;
        }

        private LedgerEntry entry(UUID account, String direction, long amount) {
            LedgerEntry e = LedgerEntry.of(txnId, account, direction, amount, LedgerEntry.TYPE_PAYMENT);
            e.setStatus(LedgerEntry.POSTED);
            return e;
        }

        @Test
        @DisplayName("R-LEDGER-01: tổng có dấu bằng 0 thì báo sổ cân")
        void soCan() throws Exception {
            when(transactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(txn()));
            when(ledgerEntryRepository.findByTxnIdInOrderByIdAsc(List.of(txnId))).thenReturn(List.of(
                    entry(WALLET_ID, LedgerEntry.DEBIT, 3_000_000L),
                    entry(PARTNER_ID, LedgerEntry.CREDIT, 3_000_000L)));

            mockMvc.perform(get("/admin/transactions/{orderId}", orderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.transactions[0].txnId").value(txnId.toString()))
                    .andExpect(jsonPath("$.transactions[0].status").value(PaymentTransaction.CAPTURED))
                    .andExpect(jsonPath("$.transactions[0].fee").value(2_200L))
                    .andExpect(jsonPath("$.transactions[0].reversedTxnId").doesNotExist())
                    .andExpect(jsonPath("$.ledgerEntries.length()").value(2))
                    .andExpect(jsonPath("$.ledgerBalanced").value(true))
                    .andExpect(jsonPath("$.ledgerSum").value(0));
        }

        @Test
        @DisplayName("bút toán lệch nhau thì báo sổ không cân kèm chênh lệch")
        void soKhongCan() throws Exception {
            when(transactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(txn()));
            when(ledgerEntryRepository.findByTxnIdInOrderByIdAsc(List.of(txnId))).thenReturn(List.of(
                    entry(WALLET_ID, LedgerEntry.DEBIT, 3_000_000L),
                    entry(PARTNER_ID, LedgerEntry.CREDIT, 2_000_000L)));

            mockMvc.perform(get("/admin/transactions/{orderId}", orderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ledgerBalanced").value(false))
                    .andExpect(jsonPath("$.ledgerSum").value(-1_000_000L));
        }

        @Test
        @DisplayName("order chưa có giao dịch nào thì trả 404 TXN_NOT_FOUND")
        void khongCoGiaoDich() throws Exception {
            when(transactionRepository.findByOrderIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

            mockMvc.perform(get("/admin/transactions/{orderId}", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TXN_NOT_FOUND"));
        }

        @Test
        @DisplayName("orderId không phải UUID thì trả 400, không đụng tới DB")
        void orderIdKhongHopLe() throws Exception {
            mockMvc.perform(get("/admin/transactions/{orderId}", "khong-phai-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

            org.mockito.Mockito.verify(transactionRepository, org.mockito.Mockito.never())
                    .findByOrderIdOrderByCreatedAtAsc(any());
        }

        @Test
        @DisplayName("giao dịch hoàn tiền chỉ ra được giao dịch gốc qua reversedTxnId")
        void giaoDichHoanTien() throws Exception {
            UUID refundTxnId = UUID.randomUUID();
            PaymentTransaction refund = txn();
            refund.setTxnId(refundTxnId);
            refund.setPaymentType("REFUND");
            refund.setFee(0L);
            refund.setReversedTxnId(txnId);

            when(transactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId))
                    .thenReturn(List.of(txn(), refund));
            when(ledgerEntryRepository.findByTxnIdInOrderByIdAsc(List.of(txnId, refundTxnId)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/admin/transactions/{orderId}", orderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(2))
                    .andExpect(jsonPath("$.transactions[1].paymentType").value("REFUND"))
                    .andExpect(jsonPath("$.transactions[1].reversedTxnId").value(txnId.toString()))
                    .andExpect(jsonPath("$.ledgerBalanced").value(true));
        }
    }
}
