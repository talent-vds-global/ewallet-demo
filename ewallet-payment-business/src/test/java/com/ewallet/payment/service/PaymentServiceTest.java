package com.ewallet.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
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
 * Nghiệp vụ thanh toán phía business — bốn thao tác saga do payment-order điều phối.
 *
 * <p>Các policy (hạn mức, phí, rà soát, tỉ giá) đã có test riêng nên ở đây được giả lập;
 * test này tập trung vào thứ tự áp rule, trạng thái giao dịch và các tác dụng phụ
 * (ghi sổ, cộng hạn mức ngày, phát event).</p>
 *
 * <p>Phạm vi: F2 hoá đơn, F3 chuyển tiền, F4 bù trừ. Đường nạp tiền F1 xem
 * {@code docs/testing.md} §3.</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String CUSTOMER = "CUST-001";
    private static final String RECEIVER = "CUST-002";
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID RECEIVER_WALLET_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID SETTLE_ID = UUID.fromString("00000000-0000-4000-8000-00000000000e");
    private static final UUID FEE_ID = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Mock private AccountRepository accountRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private DailyUsageRepository dailyUsageRepository;
    @Mock private LedgerService ledgerService;
    @Mock private LimitPolicy limitPolicy;
    @Mock private ReviewPolicy reviewPolicy;
    @Mock private FeePolicy feePolicy;
    @Mock private CurrencyConverter currencyConverter;
    @Mock private ThirdPartyClient thirdPartyClient;
    @Mock private PaymentEventPublisher eventPublisher;

    @InjectMocks
    private PaymentService paymentService;

    private UUID orderId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();

        // Mặc định: chưa có giao dịch nào của order này, mọi rule đều cho qua.
        lenient().when(transactionRepository.findFirstByOrderIdAndReversedTxnIdIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(Optional.empty());
        lenient().when(accountRepository.findWalletByCustomerId(CUSTOMER))
                .thenReturn(Optional.of(account(WALLET_ID, CUSTOMER, "WALLET", "VND", "ACTIVE")));
        lenient().when(accountRepository.findSystemAccount("SYSTEM_FEE", "SYSTEM"))
                .thenReturn(Optional.of(account(FEE_ID, "SYSTEM", "SYSTEM_FEE", "VND", "ACTIVE")));
        lenient().when(currencyConverter.toVnd(anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(0, Long.class));
        lenient().when(limitPolicy.checkAmount(any(), anyLong())).thenReturn(null);
        lenient().when(limitPolicy.checkDailyLimit(anyString(), anyLong(), anyLong())).thenReturn(null);
        lenient().when(dailyUsageRepository.findTotalAmount(anyString(), any())).thenReturn(0L);
        lenient().when(feePolicy.calculate(any(), anyLong())).thenReturn(0L);
        lenient().when(reviewPolicy.requiresManualReview(anyLong())).thenReturn(false);
        lenient().when(ledgerService.balanceOf(any())).thenReturn(10_000_000L);
    }

    private static Account account(UUID id, String customerId, String type,
                                   String currency, String status) {
        Account a = new Account();
        a.setId(id);
        a.setCustomerId(customerId);
        a.setAccountType(type);
        a.setCurrency(currency);
        a.setStatus(status);
        return a;
    }

    private AuthorizeCommand p2p(long amount) {
        return new AuthorizeCommand(orderId, CUSTOMER, PaymentType.P2P, amount, "VND",
                RECEIVER, null, null, "idem-" + amount);
    }

    private AuthorizeCommand bill(long amount, String partnerCode) {
        return new AuthorizeCommand(orderId, CUSTOMER, PaymentType.BILL, amount, "VND",
                null, partnerCode, "PD0123456", "idem-bill");
    }

    /** Ví người nhận của giao dịch chuyển tiền. */
    private void receiverWallet(String status, String currency) {
        when(accountRepository.findWalletByCustomerId(RECEIVER))
                .thenReturn(Optional.of(account(RECEIVER_WALLET_ID, RECEIVER, "WALLET", currency, status)));
    }

    private PaymentTransaction savedTransaction() {
        ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        return captor.getValue();
    }

    // =====================================================================================
    // S2 — AUTHORIZE
    // =====================================================================================

    @Nested
    @DisplayName("authorize — chuyển tiền (F3)")
    class AuthorizeP2p {

        @Test
        @DisplayName("hợp lệ: trả AUTHORIZED, ghi sổ giữ tiền và cộng hạn mức ngày")
        void chuyenTienHopLe() {
            receiverWallet("ACTIVE", "VND");
            when(feePolicy.calculate(PaymentType.P2P, 3_000_000L)).thenReturn(2_200L);

            AuthorizeResult result = paymentService.authorize(p2p(3_000_000L));

            assertThat(result.status()).isEqualTo(AuthorizeResult.AUTHORIZED);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.OK);
            assertThat(result.fee()).isEqualTo(2_200L);
            assertThat(result.amountVnd()).isEqualTo(3_000_000L);
            assertThat(result.txnId()).isNotNull();

            PaymentTransaction txn = savedTransaction();
            assertThat(txn.getStatus()).isEqualTo(PaymentTransaction.AUTHORIZED);
            assertThat(txn.getSourceAccountId()).isEqualTo(WALLET_ID);
            assertThat(txn.getDestAccountId()).isEqualTo(RECEIVER_WALLET_ID);
            assertThat(txn.getCounterpartyCustomerId()).isEqualTo(RECEIVER);

            verify(ledgerService).authorize(any(), eq(WALLET_ID), eq(RECEIVER_WALLET_ID),
                    eq(3_000_000L), eq(2_200L), eq(FEE_ID));
            verify(dailyUsageRepository).addUsage(CUSTOMER, LocalDate.now(), 3_000_000L);
            verify(eventPublisher, never()).publishFailed(any());
        }

        @Test
        @DisplayName("R-P2P-01: không cho tự chuyển cho chính mình")
        void khongChoTuChuyenChoMinh() {
            when(accountRepository.findWalletByCustomerId(RECEIVER))
                    .thenReturn(Optional.of(account(WALLET_ID, RECEIVER, "WALLET", "VND", "ACTIVE")));

            AuthorizeResult result = paymentService.authorize(p2p(500_000L));

            assertThat(result.status()).isEqualTo(AuthorizeResult.REJECTED);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.SELF_TRANSFER_NOT_ALLOWED);
            verify(ledgerService, never()).authorize(any(), any(), any(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("ví người nhận không tồn tại thì từ chối")
        void nguoiNhanKhongTonTai() {
            when(accountRepository.findWalletByCustomerId(RECEIVER)).thenReturn(Optional.empty());

            AuthorizeResult result = paymentService.authorize(p2p(500_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.ACCOUNT_NOT_FOUND);
        }

        @Test
        @DisplayName("ví người nhận bị khoá thì từ chối")
        void nguoiNhanBiKhoa() {
            receiverWallet("LOCKED", "VND");

            AuthorizeResult result = paymentService.authorize(p2p(500_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.ACCOUNT_INACTIVE);
        }

        @Test
        @DisplayName("R-P2P-05: hai ví khác loại tiền thì từ chối")
        void khacLoaiTien() {
            receiverWallet("ACTIVE", "USD");

            AuthorizeResult result = paymentService.authorize(p2p(500_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.CURRENCY_MISMATCH);
        }

        @Test
        @DisplayName("R-BALANCE-01: số dư không đủ cho cả tiền lẫn phí thì từ chối")
        void soDuKhongDu() {
            receiverWallet("ACTIVE", "VND");
            when(feePolicy.calculate(PaymentType.P2P, 3_000_000L)).thenReturn(2_200L);
            when(ledgerService.balanceOf(WALLET_ID)).thenReturn(3_000_000L);   // thiếu đúng phần phí

            AuthorizeResult result = paymentService.authorize(p2p(3_000_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.INSUFFICIENT_FUNDS);
            verify(ledgerService, never()).authorize(any(), any(), any(), anyLong(), anyLong(), any());
            verify(eventPublisher).publishFailed(any());
        }
    }

    @Nested
    @DisplayName("authorize — kiểm tra chung trước khi ghi sổ")
    class AuthorizeGuards {

        @Test
        @DisplayName("loại giao dịch không hợp lệ thì từ chối ngay, không đụng tài khoản")
        void loaiGiaoDichKhongHopLe() {
            AuthorizeCommand cmd = new AuthorizeCommand(orderId, CUSTOMER, null, 100_000L,
                    "VND", null, null, null, "idem");

            AuthorizeResult result = paymentService.authorize(cmd);

            assertThat(result.status()).isEqualTo(AuthorizeResult.REJECTED);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.INVALID_PAYMENT_TYPE);
            assertThat(result.txnId()).isNull();
            verify(accountRepository, never()).findWalletByCustomerId(anyString());
        }

        @Test
        @DisplayName("R-ACCOUNT-01: không tìm thấy ví nguồn thì từ chối")
        void khongCoViNguon() {
            when(accountRepository.findWalletByCustomerId(CUSTOMER)).thenReturn(Optional.empty());

            AuthorizeResult result = paymentService.authorize(p2p(500_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.ACCOUNT_NOT_FOUND);
        }

        @Test
        @DisplayName("R-ACCOUNT-02: ví nguồn bị khoá thì từ chối")
        void viNguonBiKhoa() {
            when(accountRepository.findWalletByCustomerId(CUSTOMER))
                    .thenReturn(Optional.of(account(WALLET_ID, CUSTOMER, "WALLET", "VND", "LOCKED")));

            AuthorizeResult result = paymentService.authorize(p2p(500_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.ACCOUNT_INACTIVE);
        }

        @Test
        @DisplayName("R-CURRENCY-01: không có tỉ giá thì từ chối CURRENCY_NOT_SUPPORTED")
        void khongCoTiGia() {
            when(currencyConverter.toVnd(anyLong(), any()))
                    .thenThrow(new CurrencyNotSupportedException("JPY"));

            AuthorizeResult result = paymentService.authorize(p2p(500_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.CURRENCY_NOT_SUPPORTED);
        }

        @Test
        @DisplayName("R-AMOUNT: vi phạm trần/sàn thì trả đúng mã của LimitPolicy")
        void viPhamTranSan() {
            when(limitPolicy.checkAmount(any(), anyLong())).thenReturn(ReasonCodes.AMOUNT_TOO_LARGE);

            AuthorizeResult result = paymentService.authorize(p2p(90_000_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.AMOUNT_TOO_LARGE);
            verify(limitPolicy, never()).checkDailyLimit(anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("R-LIMIT-01: hạn mức ngày được tính trên phần đã dùng trong ngày")
        void vuotHanMucNgay() {
            when(dailyUsageRepository.findTotalAmount(CUSTOMER, LocalDate.now())).thenReturn(40_000_000L);
            when(limitPolicy.checkDailyLimit(CUSTOMER, 40_000_000L, 20_000_000L))
                    .thenReturn(ReasonCodes.LIMIT_EXCEEDED);

            AuthorizeResult result = paymentService.authorize(p2p(20_000_000L));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
            verify(ledgerService, never()).authorize(any(), any(), any(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("chưa phát sinh giao dịch nào trong ngày thì phần đã dùng tính là 0")
        void chuaPhatSinhTrongNgay() {
            receiverWallet("ACTIVE", "VND");
            when(dailyUsageRepository.findTotalAmount(CUSTOMER, LocalDate.now())).thenReturn(null);

            paymentService.authorize(p2p(500_000L));

            verify(limitPolicy).checkDailyLimit(CUSTOMER, 0L, 500_000L);
        }

        @Test
        @DisplayName("giao dịch bị từ chối vẫn để lại dấu vết REJECTED và phát PaymentFailed")
        void tuChoiVanCoDauVet() {
            when(limitPolicy.checkAmount(any(), anyLong())).thenReturn(ReasonCodes.AMOUNT_TOO_SMALL);

            paymentService.authorize(p2p(1_000L));

            PaymentTransaction txn = savedTransaction();
            assertThat(txn.getStatus()).isEqualTo(PaymentTransaction.REJECTED);
            assertThat(txn.getReasonCode()).isEqualTo(ReasonCodes.AMOUNT_TOO_SMALL);
            verify(eventPublisher).publishFailed(txn);
            verify(ledgerService, never()).authorize(any(), any(), any(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("R-IDEM-02: gọi lại cùng orderId trả nguyên kết quả cũ, không tạo giao dịch mới")
        void gapLaiCungOrderId() {
            PaymentTransaction existing = new PaymentTransaction();
            existing.setTxnId(UUID.randomUUID());
            existing.setStatus(PaymentTransaction.AUTHORIZED);
            existing.setReasonCode(ReasonCodes.OK);
            existing.setFee(2_200L);
            existing.setAmountVnd(3_000_000L);
            when(transactionRepository.findFirstByOrderIdAndReversedTxnIdIsNullOrderByCreatedAtAsc(orderId))
                    .thenReturn(Optional.of(existing));

            AuthorizeResult result = paymentService.authorize(p2p(3_000_000L));

            assertThat(result.status()).isEqualTo(AuthorizeResult.AUTHORIZED);
            assertThat(result.txnId()).isEqualTo(existing.getTxnId());
            assertThat(result.fee()).isEqualTo(2_200L);
            verify(transactionRepository, never()).save(any());
            verify(ledgerService, never()).authorize(any(), any(), any(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("R-IDEM-02: gọi lại giao dịch đã bị từ chối thì vẫn trả REJECTED")
        void gapLaiGiaoDichDaTuChoi() {
            PaymentTransaction existing = new PaymentTransaction();
            existing.setTxnId(UUID.randomUUID());
            existing.setStatus(PaymentTransaction.REJECTED);
            existing.setReasonCode(ReasonCodes.INSUFFICIENT_FUNDS);
            when(transactionRepository.findFirstByOrderIdAndReversedTxnIdIsNullOrderByCreatedAtAsc(orderId))
                    .thenReturn(Optional.of(existing));

            AuthorizeResult result = paymentService.authorize(p2p(3_000_000L));

            assertThat(result.status()).isEqualTo(AuthorizeResult.REJECTED);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.INSUFFICIENT_FUNDS);
        }
    }

    @Nested
    @DisplayName("authorize — thanh toán hoá đơn (F2)")
    class AuthorizeBill {

        @Test
        @DisplayName("hợp lệ: tiền đi từ ví khách sang tài khoản quyết toán của đối tác")
        void hoaDonHopLe() {
            when(accountRepository.findSystemAccount("PARTNER_SETTLE", "EVN"))
                    .thenReturn(Optional.of(account(SETTLE_ID, "EVN", "PARTNER_SETTLE", "VND", "ACTIVE")));
            when(feePolicy.calculate(PaymentType.BILL, 1_000_000L)).thenReturn(5_000L);

            AuthorizeResult result = paymentService.authorize(bill(1_000_000L, "EVN"));

            assertThat(result.status()).isEqualTo(AuthorizeResult.AUTHORIZED);
            assertThat(result.fee()).isEqualTo(5_000L);

            PaymentTransaction txn = savedTransaction();
            assertThat(txn.getSourceAccountId()).isEqualTo(WALLET_ID);
            assertThat(txn.getDestAccountId()).isEqualTo(SETTLE_ID);
            assertThat(txn.getPartnerCode()).isEqualTo("EVN");
            assertThat(txn.getCounterpartyCustomerId()).isNull();
        }

        @Test
        @DisplayName("thiếu mã đối tác thì từ chối PARTNER_DECLINED")
        void thieuMaDoiTac() {
            AuthorizeResult result = paymentService.authorize(bill(1_000_000L, null));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.PARTNER_DECLINED);
        }

        @Test
        @DisplayName("đối tác chưa có tài khoản quyết toán thì từ chối")
        void doiTacChuaCoTaiKhoanQuyetToan() {
            when(accountRepository.findSystemAccount("PARTNER_SETTLE", "KHONG_CO"))
                    .thenReturn(Optional.empty());

            AuthorizeResult result = paymentService.authorize(bill(1_000_000L, "KHONG_CO"));

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.PARTNER_DECLINED);
        }
    }

    @Nested
    @DisplayName("authorize — nhánh treo chờ duyệt (R-REVIEW-01)")
    class AuthorizeHeld {

        /** Giao dịch treo là giao dịch lớn nên ví phải đủ tiền, nếu không sẽ rơi vào nhánh thiếu số dư. */
        @BeforeEach
        void viDuTien() {
            lenient().when(ledgerService.balanceOf(WALLET_ID)).thenReturn(50_000_000L);
        }

        @Test
        @DisplayName("giao dịch lớn trả HELD với lý do MANUAL_REVIEW")
        void giaoDichLonThiTreo() {
            receiverWallet("ACTIVE", "VND");
            when(reviewPolicy.requiresManualReview(25_000_000L)).thenReturn(true);

            AuthorizeResult result = paymentService.authorize(p2p(25_000_000L));

            assertThat(result.status()).isEqualTo(AuthorizeResult.HELD);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.MANUAL_REVIEW);
            assertThat(result.txnId()).isNotNull();
        }

        @Test
        @DisplayName("tiền vẫn được giữ và hạn mức ngày vẫn cộng trước khi treo")
        void treoNhungVanGiuTien() {
            receiverWallet("ACTIVE", "VND");
            when(reviewPolicy.requiresManualReview(25_000_000L)).thenReturn(true);

            paymentService.authorize(p2p(25_000_000L));

            verify(ledgerService).authorize(any(), eq(WALLET_ID), eq(RECEIVER_WALLET_ID),
                    eq(25_000_000L), eq(0L), eq(FEE_ID));
            verify(dailyUsageRepository).addUsage(CUSTOMER, LocalDate.now(), 25_000_000L);
        }

        @Test
        @DisplayName("giao dịch treo được chạy qua bước rà soát danh sách nội bộ")
        void chayQuaBuocRaSoat() {
            receiverWallet("ACTIVE", "VND");
            when(reviewPolicy.requiresManualReview(25_000_000L)).thenReturn(true);

            paymentService.authorize(p2p(25_000_000L));

            verify(reviewPolicy).runManualReviewScreening(CUSTOMER, 25_000_000L);
        }

        @Test
        @DisplayName("trạng thái cuối ghi vào DB là HELD")
        void trangThaiCuoiLaHeld() {
            receiverWallet("ACTIVE", "VND");
            when(reviewPolicy.requiresManualReview(25_000_000L)).thenReturn(true);

            paymentService.authorize(p2p(25_000_000L));

            ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
            verify(transactionRepository, times(2)).save(captor.capture());
            PaymentTransaction last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getStatus()).isEqualTo(PaymentTransaction.HELD);
            assertThat(last.getReasonCode()).isEqualTo(ReasonCodes.MANUAL_REVIEW);
        }
    }

    // =====================================================================================
    // S3 — EXECUTE PARTNER
    // =====================================================================================

    @Nested
    @DisplayName("executePartner — gọi đối tác qua third-party")
    class ExecutePartner {

        private final UUID txnId = UUID.randomUUID();

        private PaymentTransaction txn(String status) {
            PaymentTransaction t = new PaymentTransaction();
            t.setTxnId(txnId);
            t.setOrderId(orderId);
            t.setStatus(status);
            return t;
        }

        @Test
        @DisplayName("đối tác trả thành công thì lưu lại mã tham chiếu")
        void doiTacThanhCong() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn(PaymentTransaction.AUTHORIZED)));
            when(thirdPartyClient.execute(orderId, "EVN", "BILL", 1_000_000L, "VND", "0901", "PD01"))
                    .thenReturn(new PartnerExecutionResult(PartnerExecutionResult.SUCCESS,
                            ReasonCodes.OK, "PRT-9911", 120L));

            PartnerExecutionResult result = paymentService.executePartner(
                    orderId, txnId, "EVN", "BILL", 1_000_000L, "VND", "0901", "PD01");

            assertThat(result.isSuccess()).isTrue();
            assertThat(savedTransaction().getPartnerRef()).isEqualTo("PRT-9911");
        }

        @Test
        @DisplayName("đối tác từ chối thì không ghi gì vào giao dịch")
        void doiTacTuChoi() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn(PaymentTransaction.AUTHORIZED)));
            when(thirdPartyClient.execute(any(), any(), any(), anyLong(), any(), any(), any()))
                    .thenReturn(new PartnerExecutionResult(PartnerExecutionResult.DECLINED,
                            ReasonCodes.PARTNER_DECLINED, "", 80L));

            PartnerExecutionResult result = paymentService.executePartner(
                    orderId, txnId, "EVN", "BILL", 1_000_000L, "VND", "0901", "PD01");

            assertThat(result.status()).isEqualTo(PartnerExecutionResult.DECLINED);
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("không tìm thấy giao dịch thì trả TXN_NOT_FOUND, không gọi đối tác")
        void khongTimThayGiaoDich() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.empty());

            PartnerExecutionResult result = paymentService.executePartner(
                    orderId, txnId, "EVN", "BILL", 1_000_000L, "VND", "0901", "PD01");

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.TXN_NOT_FOUND);
            verify(thirdPartyClient, never()).execute(any(), any(), any(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("R-TOPUP-06: giao dịch đã chốt sổ thì không gọi đối tác lần nữa")
        void daChotSoThiKhongGoiLai() {
            PaymentTransaction captured = txn(PaymentTransaction.CAPTURED);
            captured.setPartnerRef("PRT-CU");
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(captured));

            PartnerExecutionResult result = paymentService.executePartner(
                    orderId, txnId, "EVN", "BILL", 1_000_000L, "VND", "0901", "PD01");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.partnerRef()).isEqualTo("PRT-CU");
            verify(thirdPartyClient, never()).execute(any(), any(), any(), anyLong(), any(), any(), any());
        }
    }

    // =====================================================================================
    // S4 — CONFIRM
    // =====================================================================================

    @Nested
    @DisplayName("confirm — chốt sổ")
    class Confirm {

        private final UUID txnId = UUID.randomUUID();

        private PaymentTransaction txn(String status) {
            PaymentTransaction t = new PaymentTransaction();
            t.setTxnId(txnId);
            t.setOrderId(orderId);
            t.setCustomerId(CUSTOMER);
            t.setStatus(status);
            return t;
        }

        @Test
        @DisplayName("giao dịch đang giữ tiền thì chuyển CAPTURED và phát PaymentCompleted")
        void chotSoThanhCong() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn(PaymentTransaction.AUTHORIZED)));
            when(ledgerService.balanceOf(WALLET_ID)).thenReturn(7_000_000L);

            ConfirmResult result = paymentService.confirm(orderId, txnId, "PRT-9911");

            assertThat(result.status()).isEqualTo(ConfirmResult.CAPTURED);
            assertThat(result.balanceAfter()).isEqualTo(7_000_000L);

            verify(ledgerService).capture(txnId);
            PaymentTransaction saved = savedTransaction();
            assertThat(saved.getStatus()).isEqualTo(PaymentTransaction.CAPTURED);
            assertThat(saved.getPartnerRef()).isEqualTo("PRT-9911");
            verify(eventPublisher).publishCompleted(saved);
        }

        @Test
        @DisplayName("partnerRef rỗng thì giữ nguyên mã đã lưu từ bước gọi đối tác")
        void partnerRefRongThiGiuNguyen() {
            PaymentTransaction t = txn(PaymentTransaction.AUTHORIZED);
            t.setPartnerRef("PRT-CU");
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

            paymentService.confirm(orderId, txnId, "  ");

            assertThat(savedTransaction().getPartnerRef()).isEqualTo("PRT-CU");
        }

        @Test
        @DisplayName("gọi chốt sổ lần hai trả về CAPTURED nhưng không chốt sổ lại")
        void chotSoHaiLan() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn(PaymentTransaction.CAPTURED)));

            ConfirmResult result = paymentService.confirm(orderId, txnId, "PRT-9911");

            assertThat(result.status()).isEqualTo(ConfirmResult.CAPTURED);
            verify(ledgerService, never()).capture(any());
            verify(eventPublisher, never()).publishCompleted(any());
        }

        @Test
        @DisplayName("không tìm thấy giao dịch thì trả TXN_NOT_FOUND")
        void khongTimThay() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.empty());

            ConfirmResult result = paymentService.confirm(orderId, txnId, null);

            assertThat(result.status()).isEqualTo(ConfirmResult.ERROR);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.TXN_NOT_FOUND);
        }

        @Test
        @DisplayName("giao dịch đã bị từ chối thì không cho chốt sổ")
        void saiTrangThai() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn(PaymentTransaction.REJECTED)));

            ConfirmResult result = paymentService.confirm(orderId, txnId, null);

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.INVALID_STATE);
            verify(ledgerService, never()).capture(any());
        }
    }

    // =====================================================================================
    // S3' — REVERSE
    // =====================================================================================

    @Nested
    @DisplayName("reverse — bù trừ (F4)")
    class Reverse {

        private final UUID txnId = UUID.randomUUID();
        private final UUID refundTxnId = UUID.randomUUID();

        private PaymentTransaction original(String status) {
            PaymentTransaction t = new PaymentTransaction();
            t.setTxnId(txnId);
            t.setOrderId(orderId);
            t.setCustomerId(CUSTOMER);
            t.setPaymentType(PaymentType.BILL.name());
            t.setAmount(1_000_000L);
            t.setAmountVnd(1_000_000L);
            t.setCurrency("VND");
            t.setSourceAccountId(WALLET_ID);
            t.setDestAccountId(SETTLE_ID);
            t.setPartnerCode("EVN");
            t.setStatus(status);
            t.setCreatedAt(OffsetDateTime.now().minusDays(1));
            return t;
        }

        @Test
        @DisplayName("giao dịch đã chốt sổ: sinh giao dịch hoàn tiền đảo chiều hai đầu bút toán")
        void buTruGiaoDichDaChotSo() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(original(PaymentTransaction.CAPTURED)));
            when(ledgerService.reverse(txnId)).thenReturn(refundTxnId);
            when(ledgerService.balanceOf(WALLET_ID)).thenReturn(9_000_000L);

            ReverseResult result = paymentService.reverse(orderId, txnId, ReasonCodes.PARTNER_DECLINED);

            assertThat(result.status()).isEqualTo(ReverseResult.REVERSED);
            assertThat(result.refundTxnId()).isEqualTo(refundTxnId);
            assertThat(result.balanceAfter()).isEqualTo(9_000_000L);

            ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
            verify(transactionRepository, times(2)).save(captor.capture());

            PaymentTransaction refund = captor.getAllValues().get(0);
            assertThat(refund.getTxnId()).isEqualTo(refundTxnId);
            assertThat(refund.getPaymentType()).isEqualTo(PaymentType.REFUND.name());
            assertThat(refund.getStatus()).isEqualTo(PaymentTransaction.CAPTURED);
            assertThat(refund.getReversedTxnId()).isEqualTo(txnId);
            assertThat(refund.getFee()).isZero();                       // R-FEE-05
            assertThat(refund.getSourceAccountId()).isEqualTo(SETTLE_ID);
            assertThat(refund.getDestAccountId()).isEqualTo(WALLET_ID);

            PaymentTransaction updated = captor.getAllValues().get(1);
            assertThat(updated.getTxnId()).isEqualTo(txnId);
            assertThat(updated.getStatus()).isEqualTo(PaymentTransaction.REVERSED);

            verify(eventPublisher).publishRefunded(refund);
        }

        @Test
        @DisplayName("R-USAGE-02: hạn mức được trả lại đúng ngày của giao dịch gốc")
        void traLaiHanMucDungNgay() {
            PaymentTransaction orig = original(PaymentTransaction.CAPTURED);
            LocalDate ngayGoc = orig.getCreatedAt().toLocalDate();
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(orig));
            when(ledgerService.reverse(txnId)).thenReturn(refundTxnId);

            paymentService.reverse(orderId, txnId, ReasonCodes.PARTNER_TIMEOUT);

            verify(dailyUsageRepository).addUsage(CUSTOMER, ngayGoc, -1_000_000L);
        }

        @Test
        @DisplayName("giao dịch đang treo chờ duyệt cũng bù trừ được")
        void buTruGiaoDichDangTreo() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(original(PaymentTransaction.HELD)));
            when(ledgerService.reverse(txnId)).thenReturn(refundTxnId);

            ReverseResult result = paymentService.reverse(orderId, txnId, ReasonCodes.MANUAL_REVIEW);

            assertThat(result.status()).isEqualTo(ReverseResult.REVERSED);
        }

        @Test
        @DisplayName("R-COMP-05: gọi bù trừ lần hai trả về ALREADY_REVERSED, không ghi sổ thêm")
        void buTruHaiLanIdempotent() {
            PaymentTransaction reversed = original(PaymentTransaction.REVERSED);
            PaymentTransaction refund = original(PaymentTransaction.CAPTURED);
            refund.setTxnId(refundTxnId);
            refund.setReversedTxnId(txnId);
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(reversed));
            when(transactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId))
                    .thenReturn(List.of(reversed, refund));

            ReverseResult result = paymentService.reverse(orderId, txnId, ReasonCodes.PARTNER_DECLINED);

            assertThat(result.status()).isEqualTo(ReverseResult.REVERSED);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.ALREADY_REVERSED);
            assertThat(result.refundTxnId()).isEqualTo(refundTxnId);
            verify(ledgerService, never()).reverse(any());
            verify(transactionRepository, never()).save(any());
            verify(eventPublisher, never()).publishRefunded(any());
        }

        @Test
        @DisplayName("không tìm thấy giao dịch thì trả TXN_NOT_FOUND")
        void khongTimThay() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.empty());

            ReverseResult result = paymentService.reverse(orderId, txnId, ReasonCodes.PARTNER_DECLINED);

            assertThat(result.status()).isEqualTo(ReverseResult.ERROR);
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.TXN_NOT_FOUND);
        }

        @Test
        @DisplayName("giao dịch đã bị từ chối thì không có gì để bù trừ")
        void saiTrangThai() {
            when(transactionRepository.findById(txnId)).thenReturn(Optional.of(original(PaymentTransaction.REJECTED)));

            ReverseResult result = paymentService.reverse(orderId, txnId, ReasonCodes.PARTNER_DECLINED);

            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.INVALID_STATE);
            verify(ledgerService, never()).reverse(any());
        }
    }

    // =====================================================================================
    // S0 — INQUIRE BILL
    // =====================================================================================

    @Test
    @DisplayName("inquireBill chuyển thẳng câu hỏi xuống third-party")
    void traCuuHoaDon() {
        BillInquiryResult expected = new BillInquiryResult(BillInquiryResult.FOUND,
                "PD0123456", "Nguyen Van A", "2026-08", 450_000L, "VND", "UNPAID");
        when(thirdPartyClient.inquireBill("EVN", "PD0123456")).thenReturn(expected);

        assertThat(paymentService.inquireBill("EVN", "PD0123456")).isEqualTo(expected);
    }
}
