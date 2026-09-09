package com.ewallet.partnersim.domain;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hành vi giả lập của đối tác.
 *
 * <p>Ưu tiên <b>tất định theo số tiền</b> để demo lặp lại được (docs/specs/00-domain-and-conventions.md §11.5):
 * đuôi 999 thì từ chối, đuôi 888 thì treo quá hạn. Các tham số ngẫu nhiên trong
 * {@code application.yml} chỉ dùng khi cần bơm nhiễu để dựng baseline.</p>
 */
@Component
public class PartnerBehavior {

    private static final Logger log = LoggerFactory.getLogger(PartnerBehavior.class);

    /** Số tiền có đuôi này thì đối tác từ chối. */
    private static final long DECLINE_SUFFIX = 999L;
    /** Số tiền có đuôi này thì đối tác trả lời chậm quá hạn của third-party (3000ms). */
    private static final long TIMEOUT_SUFFIX = 888L;
    private static final long TIMEOUT_SLEEP_MS = 5_000L;

    private static final int NORMAL_LATENCY_MIN_MS = 50;
    private static final int NORMAL_LATENCY_MAX_MS = 250;

    private final long extraLatencyMs;
    private final double failRate;
    private final double timeoutRate;

    public PartnerBehavior(@Value("${partner-sim.latency-ms:0}") long extraLatencyMs,
                           @Value("${partner-sim.fail-rate:0.0}") double failRate,
                           @Value("${partner-sim.timeout-rate:0.0}") double timeoutRate) {
        this.extraLatencyMs = extraLatencyMs;
        this.failRate = failRate;
        this.timeoutRate = timeoutRate;
    }

    public enum Outcome { SUCCESS, DECLINED }

    /**
     * Quyết định kết quả và ngủ đúng khoảng thời gian tương ứng.
     * Ca "timeout" được mô phỏng bằng cách ngủ lâu hơn hạn của bên gọi — đúng như đối tác thật
     * treo request, chứ không phải trả về mã lỗi timeout.
     */
    public Outcome decide(long amount) {
        long tail = Math.floorMod(amount, 1000L);

        if (tail == TIMEOUT_SUFFIX) {
            log.info("gia lap doi tac treo qua han amount={}", amount);
            sleep(TIMEOUT_SLEEP_MS);
            return Outcome.SUCCESS;   // bên gọi đã bỏ cuộc từ lâu
        }
        if (tail == DECLINE_SUFFIX) {
            log.info("gia lap doi tac tu choi amount={}", amount);
            sleep(normalLatency());
            return Outcome.DECLINED;
        }

        if (timeoutRate > 0 && ThreadLocalRandom.current().nextDouble() < timeoutRate) {
            log.info("bom nhieu: treo qua han amount={}", amount);
            sleep(TIMEOUT_SLEEP_MS);
            return Outcome.SUCCESS;
        }
        if (failRate > 0 && ThreadLocalRandom.current().nextDouble() < failRate) {
            log.info("bom nhieu: tu choi amount={}", amount);
            sleep(normalLatency());
            return Outcome.DECLINED;
        }

        sleep(normalLatency());
        return Outcome.SUCCESS;
    }

    private long normalLatency() {
        return extraLatencyMs
                + ThreadLocalRandom.current().nextInt(NORMAL_LATENCY_MIN_MS, NORMAL_LATENCY_MAX_MS + 1);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
