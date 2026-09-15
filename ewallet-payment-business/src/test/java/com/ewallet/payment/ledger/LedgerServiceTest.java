package com.ewallet.payment.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.payment.entity.AccountBalance;
import com.ewallet.payment.entity.LedgerEntry;
import com.ewallet.payment.entity.PaymentTransaction;
import com.ewallet.payment.repo.AccountBalanceRepository;
import com.ewallet.payment.repo.LedgerEntryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Sổ cái kép — R-LEDGER-01 (mỗi giao dịch 2 bút toán cân nhau, cộng 2 nữa nếu có phí)
 * và R-LEDGER-02 (bù trừ bằng bút toán ngược chiều mang txn_id mới, không sửa dòng cũ).
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID DEST = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID FEE_ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountBalanceRepository accountBalanceRepository;

    @InjectMocks
    private LedgerService ledgerService;

    @Captor
    private ArgumentCaptor<List<LedgerEntry>> entriesCaptor;

    private PaymentTransaction txn;

    @BeforeEach
    void setUp() {
        txn = new PaymentTransaction();
        txn.setTxnId(UUID.randomUUID());
    }

    private static LedgerEntry entry(UUID txnId, UUID accountId, String direction,
                                     long amount, String entryType, String status) {
        LedgerEntry e = LedgerEntry.of(txnId, accountId, direction, amount, entryType);
        e.setStatus(status);
        return e;
    }

    /** Số dư đang có của một tài khoản, dùng cho nhánh findForUpdate. */
    private void balanceOnFile(UUID accountId, long balance) {
        AccountBalance b = new AccountBalance();
        b.setAccountId(accountId);
        b.setBalance(balance);
        when(accountBalanceRepository.findForUpdate(accountId)).thenReturn(Optional.of(b));
    }

    @Nested
    @DisplayName("authorize — giữ tiền, ghi bút toán PENDING")
    class Authorize {

        @Test
        @DisplayName("R-LEDGER-01: không có phí thì ghi đúng 1 DEBIT + 1 CREDIT cùng txn_id")
        void khongPhiThiHaiButToan() {
            ledgerService.authorize(txn, SOURCE, DEST, 500_000L, 0L, FEE_ACCOUNT);

            verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
            List<LedgerEntry> entries = entriesCaptor.getValue();

            assertThat(entries).hasSize(2)
                    .extracting(LedgerEntry::getAccountId, LedgerEntry::getDirection,
                            LedgerEntry::getAmount, LedgerEntry::getEntryType)
                    .containsExactly(
                            Tuple.tuple(SOURCE, LedgerEntry.DEBIT, 500_000L, LedgerEntry.TYPE_PAYMENT),
                            Tuple.tuple(DEST, LedgerEntry.CREDIT, 500_000L, LedgerEntry.TYPE_PAYMENT));
            assertThat(entries).allSatisfy(e -> {
                assertThat(e.getTxnId()).isEqualTo(txn.getTxnId());
                assertThat(e.getStatus()).isEqualTo(LedgerEntry.PENDING);
            });
        }

        @Test
        @DisplayName("có phí thì ghi thêm 1 cặp bút toán entry_type=FEE về tài khoản phí")
        void coPhiThiBonButToan() {
            ledgerService.authorize(txn, SOURCE, DEST, 3_000_000L, 2_200L, FEE_ACCOUNT);

            verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
            List<LedgerEntry> entries = entriesCaptor.getValue();

            assertThat(entries).hasSize(4);
            assertThat(entries).filteredOn(e -> LedgerEntry.TYPE_FEE.equals(e.getEntryType()))
                    .extracting(LedgerEntry::getAccountId, LedgerEntry::getDirection, LedgerEntry::getAmount)
                    .containsExactly(
                            Tuple.tuple(SOURCE, LedgerEntry.DEBIT, 2_200L),
                            Tuple.tuple(FEE_ACCOUNT, LedgerEntry.CREDIT, 2_200L));
        }

        @Test
        @DisplayName("giữ tiền chưa đụng tới số dư — số dư chỉ đổi khi capture")
        void giuTienChuaDoiSoDu() {
            ledgerService.authorize(txn, SOURCE, DEST, 500_000L, 0L, FEE_ACCOUNT);

            verify(accountBalanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("tổng DEBIT bằng tổng CREDIT — sổ cái luôn cân")
        void soCaiCan() {
            ledgerService.authorize(txn, SOURCE, DEST, 3_000_000L, 2_200L, FEE_ACCOUNT);

            verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
            long sum = entriesCaptor.getValue().stream().mapToLong(LedgerEntry::signedAmount).sum();
            assertThat(sum).isZero();
        }
    }

    @Nested
    @DisplayName("capture — chốt sổ, áp vào số dư")
    class Capture {

        @Test
        @DisplayName("bút toán PENDING chuyển POSTED và số dư hai đầu đổi đúng chiều")
        void chotSoDoiSoDu() {
            UUID txnId = txn.getTxnId();
            when(ledgerEntryRepository.findByTxnIdOrderByIdAsc(txnId)).thenReturn(List.of(
                    entry(txnId, SOURCE, LedgerEntry.DEBIT, 500_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.PENDING),
                    entry(txnId, DEST, LedgerEntry.CREDIT, 500_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.PENDING)));
            balanceOnFile(SOURCE, 2_000_000L);
            balanceOnFile(DEST, 100_000L);

            ledgerService.capture(txnId);

            ArgumentCaptor<AccountBalance> balances = ArgumentCaptor.forClass(AccountBalance.class);
            verify(accountBalanceRepository, times(2)).save(balances.capture());
            assertThat(balances.getAllValues())
                    .extracting(AccountBalance::getAccountId, AccountBalance::getBalance)
                    .containsExactlyInAnyOrder(
                            Tuple.tuple(SOURCE, 1_500_000L),
                            Tuple.tuple(DEST, 600_000L));

            verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
            assertThat(entriesCaptor.getValue())
                    .allMatch(e -> LedgerEntry.POSTED.equals(e.getStatus()));
        }

        @Test
        @DisplayName("nhiều bút toán cùng tài khoản được gộp thành một lần cập nhật số dư")
        void gopButToanCungTaiKhoan() {
            UUID txnId = txn.getTxnId();
            when(ledgerEntryRepository.findByTxnIdOrderByIdAsc(txnId)).thenReturn(List.of(
                    entry(txnId, SOURCE, LedgerEntry.DEBIT, 1_000_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.PENDING),
                    entry(txnId, DEST, LedgerEntry.CREDIT, 1_000_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.PENDING),
                    entry(txnId, SOURCE, LedgerEntry.DEBIT, 2_200L,
                            LedgerEntry.TYPE_FEE, LedgerEntry.PENDING),
                    entry(txnId, FEE_ACCOUNT, LedgerEntry.CREDIT, 2_200L,
                            LedgerEntry.TYPE_FEE, LedgerEntry.PENDING)));
            balanceOnFile(SOURCE, 5_000_000L);
            balanceOnFile(DEST, 0L);
            balanceOnFile(FEE_ACCOUNT, 0L);

            ledgerService.capture(txnId);

            ArgumentCaptor<AccountBalance> balances = ArgumentCaptor.forClass(AccountBalance.class);
            verify(accountBalanceRepository, times(3)).save(balances.capture());
            assertThat(balances.getAllValues())
                    .extracting(AccountBalance::getAccountId, AccountBalance::getBalance)
                    .contains(Tuple.tuple(SOURCE, 5_000_000L - 1_000_000L - 2_200L));
        }

        @Test
        @DisplayName("tài khoản chưa có dòng số dư thì tạo mới từ 0")
        void taiKhoanChuaCoSoDu() {
            UUID txnId = txn.getTxnId();
            when(ledgerEntryRepository.findByTxnIdOrderByIdAsc(txnId)).thenReturn(List.of(
                    entry(txnId, DEST, LedgerEntry.CREDIT, 700_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.PENDING)));
            when(accountBalanceRepository.findForUpdate(DEST)).thenReturn(Optional.empty());

            ledgerService.capture(txnId);

            ArgumentCaptor<AccountBalance> balance = ArgumentCaptor.forClass(AccountBalance.class);
            verify(accountBalanceRepository).save(balance.capture());
            assertThat(balance.getValue().getBalance()).isEqualTo(700_000L);
        }

        @Test
        @DisplayName("gọi capture lần hai không chốt sổ lại vì không còn bút toán PENDING")
        void chotSoHaiLanKhongCongDon() {
            UUID txnId = txn.getTxnId();
            when(ledgerEntryRepository.findByTxnIdOrderByIdAsc(txnId)).thenReturn(List.of(
                    entry(txnId, SOURCE, LedgerEntry.DEBIT, 500_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.POSTED)));

            ledgerService.capture(txnId);

            verify(accountBalanceRepository, never()).save(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("reverse — bù trừ R-LEDGER-02")
    class Reverse {

        @Test
        @DisplayName("giao dịch đã chốt sổ: bút toán ngược chiều được POSTED và số dư hoàn lại")
        void buTruGiaoDichDaChotSo() {
            UUID txnId = txn.getTxnId();
            List<LedgerEntry> originals = new ArrayList<>(List.of(
                    entry(txnId, SOURCE, LedgerEntry.DEBIT, 500_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.POSTED),
                    entry(txnId, DEST, LedgerEntry.CREDIT, 500_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.POSTED)));
            when(ledgerEntryRepository.findByTxnIdOrderByIdAsc(txnId)).thenReturn(originals);
            balanceOnFile(SOURCE, 1_500_000L);
            balanceOnFile(DEST, 600_000L);

            UUID refundTxnId = ledgerService.reverse(txnId);

            assertThat(refundTxnId).isNotNull().isNotEqualTo(txnId);

            verify(ledgerEntryRepository, times(2)).saveAll(entriesCaptor.capture());
            List<LedgerEntry> refunds = entriesCaptor.getAllValues().get(0);
            assertThat(refunds)
                    .extracting(LedgerEntry::getTxnId, LedgerEntry::getAccountId,
                            LedgerEntry::getDirection, LedgerEntry::getEntryType,
                            LedgerEntry::getStatus)
                    .containsExactly(
                            Tuple.tuple(refundTxnId, SOURCE, LedgerEntry.CREDIT,
                                    LedgerEntry.TYPE_REFUND, LedgerEntry.POSTED),
                            Tuple.tuple(refundTxnId, DEST, LedgerEntry.DEBIT,
                                    LedgerEntry.TYPE_REFUND, LedgerEntry.POSTED));

            // Dòng gốc chỉ đổi trạng thái, nội dung giữ nguyên để truy vết.
            assertThat(entriesCaptor.getAllValues().get(1))
                    .allMatch(e -> LedgerEntry.REVERSED.equals(e.getStatus()))
                    .allMatch(e -> txnId.equals(e.getTxnId()));

            ArgumentCaptor<AccountBalance> balances = ArgumentCaptor.forClass(AccountBalance.class);
            verify(accountBalanceRepository, times(2)).save(balances.capture());
            assertThat(balances.getAllValues())
                    .extracting(AccountBalance::getAccountId, AccountBalance::getBalance)
                    .containsExactlyInAnyOrder(
                            Tuple.tuple(SOURCE, 2_000_000L),
                            Tuple.tuple(DEST, 100_000L));
        }

        @Test
        @DisplayName("giao dịch mới chỉ giữ tiền (PENDING): số dư chưa từng đổi nên không chạm tới")
        void buTruGiaoDichChuaChotSo() {
            UUID txnId = txn.getTxnId();
            when(ledgerEntryRepository.findByTxnIdOrderByIdAsc(txnId)).thenReturn(new ArrayList<>(List.of(
                    entry(txnId, SOURCE, LedgerEntry.DEBIT, 500_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.PENDING),
                    entry(txnId, DEST, LedgerEntry.CREDIT, 500_000L,
                            LedgerEntry.TYPE_PAYMENT, LedgerEntry.PENDING))));

            ledgerService.reverse(txnId);

            verify(accountBalanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("không có bút toán nào để bù trừ thì ném IllegalStateException")
        void khongCoButToanThiNem() {
            UUID txnId = txn.getTxnId();
            when(ledgerEntryRepository.findByTxnIdOrderByIdAsc(txnId)).thenReturn(List.of());

            assertThatThrownBy(() -> ledgerService.reverse(txnId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(txnId.toString());
        }
    }

    @Nested
    @DisplayName("balanceOf")
    class BalanceOf {

        @Test
        @DisplayName("có dòng số dư thì trả đúng giá trị")
        void coDongSoDu() {
            AccountBalance b = new AccountBalance();
            b.setAccountId(SOURCE);
            b.setBalance(4_200_000L);
            when(accountBalanceRepository.findById(SOURCE)).thenReturn(Optional.of(b));

            assertThat(ledgerService.balanceOf(SOURCE)).isEqualTo(4_200_000L);
        }

        @Test
        @DisplayName("chưa có dòng số dư thì coi như 0")
        void chuaCoDongSoDu() {
            when(accountBalanceRepository.findById(SOURCE)).thenReturn(Optional.empty());

            assertThat(ledgerService.balanceOf(SOURCE)).isZero();
        }
    }
}
