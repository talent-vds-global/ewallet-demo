package com.ewallet.thirdparty.service;

import com.ewallet.thirdparty.client.PartnerSimClient;
import com.ewallet.thirdparty.entity.PartnerConfig;
import com.ewallet.thirdparty.entity.PartnerTransaction;
import com.ewallet.thirdparty.partner.ExecuteCommand;
import com.ewallet.thirdparty.partner.PartnerAdapter;
import com.ewallet.thirdparty.repo.PartnerConfigRepository;
import com.ewallet.thirdparty.repo.PartnerTransactionRepository;
import com.ewallet.thirdparty.ws.PartnerWsClient;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Điều phối một lệnh ra đối tác: chọn adapter, ghi dấu vết, gọi, phân loại kết quả.
 *
 * <p>Không tự bù trừ khi thất bại — việc đó do payment-order quyết định (R-COMP-01).
 * Ở đây chỉ báo lên trên là DECLINED hay TIMEOUT, hai thứ đó khác nhau về nghĩa.</p>
 */
@Service
public class PartnerExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PartnerExecutionService.class);

    public record ExecutionOutcome(String status, String reasonCode, String partnerRef, long elapsedMs) { }

    private final PartnerConfigRepository configRepository;
    private final PartnerTransactionRepository transactionRepository;
    private final PartnerSimClient partnerSimClient;
    private final PartnerWsClient wsClient;
    private final Map<String, PartnerAdapter> adaptersByServiceType = new HashMap<>();
    private final int maxAttempts;

    public PartnerExecutionService(PartnerConfigRepository configRepository,
                                   PartnerTransactionRepository transactionRepository,
                                   PartnerSimClient partnerSimClient,
                                   PartnerWsClient wsClient,
                                   List<PartnerAdapter> adapters,
                                   @Value("${partner.max-attempts:2}") int maxAttempts) {
        this.configRepository = configRepository;
        this.transactionRepository = transactionRepository;
        this.partnerSimClient = partnerSimClient;
        this.wsClient = wsClient;
        this.maxAttempts = Math.max(1, maxAttempts);
        for (PartnerAdapter adapter : adapters) {
            adaptersByServiceType.put(adapter.serviceType(), adapter);
        }
        log.info("da nap {} adapter doi tac: {}", adapters.size(), adaptersByServiceType.keySet());
    }

    public ExecutionOutcome execute(ExecuteCommand command) {
        long started = System.currentTimeMillis();

        Optional<PartnerConfig> configOpt = configRepository.findById(command.partnerCode());
        if (configOpt.isEmpty()) {
            return fail("PARTNER_NOT_FOUND", started);              // R-TOPUP-02
        }
        PartnerConfig config = configOpt.get();
        if (!config.isEnabled()) {
            return fail("PARTNER_DISABLED", started);
        }

        PartnerAdapter adapter = adaptersByServiceType.get(config.getServiceType());
        if (adapter == null) {
            return fail("SERVICE_TYPE_NOT_SUPPORTED", started);     // R-TOPUP-03
        }

        String violation = adapter.validate(command);
        if (violation != null) {
            log.info("lenh khong hop le voi adapter {}: {}", adapter.serviceType(), violation);
            return fail(violation, started);
        }

        UUID orderId = UUID.fromString(command.orderId());
        PartnerTransaction txn = newTransaction(command, config, orderId);
        transactionRepository.save(txn);

        PartnerSimClient.Result result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            txn.setAttempt(attempt);
            result = adapter.execute(command);

            // Chỉ thử lại khi đối tác không trả lời. Bị từ chối là quyết định của họ,
            // gọi lại chỉ tốn thêm một lần và có nguy cơ ghi nợ hai lần.
            if (!PartnerSimClient.Result.TIMEOUT.equals(result.status())) {
                break;
            }
            log.warn("doi tac qua han lan {} orderId={}", attempt, command.orderId());
        }

        long elapsed = System.currentTimeMillis() - started;

        if (result != null && result.isSuccess()) {
            txn.setStatus(PartnerTransaction.SUCCESS);
            txn.setPartnerRef(result.partnerRef());
            transactionRepository.save(txn);

            // Báo cho đối tác biết cần đẩy xác nhận quyết toán về kênh WebSocket đang mở.
            wsClient.watch(command.orderId(), result.partnerRef());

            log.info("doi tac chap nhan orderId={} partnerRef={} trong {}ms",
                    command.orderId(), result.partnerRef(), elapsed);
            return new ExecutionOutcome("SUCCESS", "OK", result.partnerRef(), elapsed);
        }

        String reason = result == null ? "PARTNER_DECLINED" : result.reasonCode();
        String status = (result != null && PartnerSimClient.Result.TIMEOUT.equals(result.status()))
                ? "TIMEOUT" : "DECLINED";

        txn.setStatus(PartnerTransaction.FAILED);
        txn.setFailReason(reason);
        transactionRepository.save(txn);

        log.info("doi tac khong thuc hien duoc orderId={} status={} reason={} trong {}ms",
                command.orderId(), status, reason, elapsed);
        return new ExecutionOutcome(status, reason, null, elapsed);
    }

    public Map<String, Object> billInquiry(String partnerCode, String billCode) {
        Optional<PartnerConfig> config = configRepository.findById(partnerCode);
        if (config.isEmpty() || !config.get().isEnabled()) {
            return Map.of("status", "NOT_FOUND", "billCode", billCode == null ? "" : billCode);
        }
        return partnerSimClient.billInquiry(partnerCode, billCode);
    }

    public List<PartnerTransaction> byOrder(UUID orderId) {
        return transactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    // ---------------------------------------------------------------- helper

    private PartnerTransaction newTransaction(ExecuteCommand c, PartnerConfig config, UUID orderId) {
        PartnerTransaction txn = new PartnerTransaction();
        txn.setId(UUID.randomUUID());
        txn.setPartnerCode(c.partnerCode());
        txn.setOrderId(orderId);
        txn.setAmount(c.amount());
        txn.setStatus(PartnerTransaction.PENDING);
        txn.setServiceType(config.getServiceType());
        txn.setBillCode(c.billCode());
        txn.setAttempt(1);
        txn.setCreatedAt(OffsetDateTime.now());
        return txn;
    }

    private ExecutionOutcome fail(String reasonCode, long started) {
        return new ExecutionOutcome("DECLINED", reasonCode, null, System.currentTimeMillis() - started);
    }
}
