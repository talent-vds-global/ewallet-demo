package com.ewallet.notification.kafka;

import com.ewallet.notification.entity.NotificationOutbox;
import com.ewallet.notification.entity.NotificationSentLog;
import com.ewallet.notification.repo.NotificationOutboxRepository;
import com.ewallet.notification.repo.NotificationSentLogRepository;
import com.ewallet.notification.service.NotificationRouter;
import com.ewallet.notification.service.NotificationSender;
import com.ewallet.notification.service.SseHub;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumer group thứ nhất trên {@code ewallet.payment.events} — sinh thông báo cho khách.
 *
 * <p>Cùng một event, group này gửi thông báo còn {@code order-status-cg} chốt trạng thái đơn.
 * Hai group độc lập offset: bên này hỏng không chặn bên kia.</p>
 *
 * <p>Retry theo spec (F5 §2.2): gửi hỏng thì <b>republish lại chính topic gốc</b> với
 * {@code x-attempt} tăng dần, hết lượt thì đẩy sang {@code .DLT}. Cách này giữ đúng dữ liệu
 * mà Trace Analyzer cần để phân loại {@code first_attempt / retry / dead_letter},
 * thay vì sinh ra một chuỗi topic retry riêng.</p>
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private static final String HEADER_ATTEMPT = "x-attempt";
    private static final String HEADER_EVENT_TYPE = "eventType";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentEventView(String eventId, String eventType, String orderId, String customerId,
                            String counterpartyCustomerId, long amount, long amountVnd,
                            String reasonCode) { }

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationSentLogRepository sentLogRepository;
    private final NotificationRouter router;
    private final NotificationSender sender;
    private final SseHub sseHub;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private final String topic;
    private final String dltTopic;
    private final int maxAttempts;
    private final long backoffMs;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "notif-retry");
                t.setDaemon(true);
                return t;
            });

    public PaymentEventListener(NotificationOutboxRepository outboxRepository,
                                NotificationSentLogRepository sentLogRepository,
                                NotificationRouter router,
                                NotificationSender sender,
                                SseHub sseHub,
                                KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper,
                                @Value("${app.kafka.payment-events-topic}") String topic,
                                @Value("${notification.retry.max-attempts:3}") int maxAttempts,
                                @Value("${notification.retry.backoff-ms:1000}") long backoffMs) {
        this.outboxRepository = outboxRepository;
        this.sentLogRepository = sentLogRepository;
        this.router = router;
        this.sender = sender;
        this.sseHub = sseHub;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.dltTopic = topic + ".DLT";
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = backoffMs;
    }

    @KafkaListener(topics = "${app.kafka.payment-events-topic}", groupId = "notification-cg")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        int attempt = attemptOf(record);

        PaymentEventView event;
        try {
            event = objectMapper.readValue(record.value(), PaymentEventView.class);
        } catch (Exception e) {
            // D6: JSON hỏng là lỗi vĩnh viễn, thử lại vô ích — vào thẳng DLT.
            log.error("message khong doc duoc offset={} — day sang DLT", record.offset(), e);
            toDeadLetter(record, attempt);
            return;
        }

        UUID eventId = parseUuid(event.eventId());
        if (eventId == null) {
            log.error("eventId khong hop le: {}", event.eventId());
            return;
        }

        List<NotificationRouter.Target> targets = router.route(
                event.eventType(), event.customerId(), event.counterpartyCustomerId(),
                event.amount(), event.amountVnd(), event.reasonCode());

        if (targets.isEmpty()) {
            log.warn("eventType khong co kenh nao: {}", event.eventType());
            return;
        }

        boolean anyFailed = false;
        for (NotificationRouter.Target target : targets) {
            anyFailed |= !deliver(event, eventId, target, attempt);
        }

        if (anyFailed) {
            scheduleRetryOrDeadLetter(record, attempt);
        }
    }

    /** @return true nếu gửi thành công */
    private boolean deliver(PaymentEventView event, UUID eventId,
                            NotificationRouter.Target target, int attempt) {
        // R-NOTIF-05: một event + một kênh + một khách hàng chỉ có đúng một bản ghi outbox.
        // Lần thử lại dùng lại chính bản ghi đó thay vì tạo mới, nếu không unique index sẽ chặn.
        NotificationOutbox outbox = outboxRepository
                .findByEventIdAndChannelAndCustomerId(eventId, target.channel(), target.customerId())
                .orElse(null);

        if (outbox != null && NotificationOutbox.SENT.equals(outbox.getStatus())) {
            log.debug("da gui truoc do eventId={} channel={} customerId={}, bo qua",
                    eventId, target.channel(), target.customerId());
            return true;
        }

        if (outbox == null) {
            outbox = new NotificationOutbox();
            outbox.setId(UUID.randomUUID());
            outbox.setEventId(eventId);
            outbox.setEventType(event.eventType());
            outbox.setCustomerId(target.customerId());
            outbox.setChannel(target.channel());
            outbox.setPayload(target.message());
            outbox.setOrderId(parseUuid(event.orderId()));
            outbox.setCreatedAt(OffsetDateTime.now());
        }
        outbox.setAttempt(attempt);
        outbox.setStatus(NotificationOutbox.PENDING);
        outboxRepository.save(outbox);

        try {
            sender.send(target.customerId(), target.channel(), target.message());

            outbox.setStatus(NotificationOutbox.SENT);
            outboxRepository.save(outbox);
            sentLogRepository.save(
                    NotificationSentLog.of(outbox.getId(), attempt, NotificationSentLog.SENT));

            sseHub.push(target.customerId(), sseJson(event, target));
            return true;

        } catch (RuntimeException e) {
            log.warn("gui that bai lan {} customerId={} channel={}: {}",
                    attempt, target.customerId(), target.channel(), e.getMessage());
            outbox.setStatus(attempt >= maxAttempts
                    ? NotificationOutbox.DEAD_LETTER : NotificationOutbox.RETRYING);
            outboxRepository.save(outbox);
            sentLogRepository.save(NotificationSentLog.of(outbox.getId(), attempt,
                    attempt >= maxAttempts
                            ? NotificationSentLog.DEAD_LETTER : NotificationSentLog.FAILED));
            return false;
        }
    }

    /** R-NOTIF-03 và R-NOTIF-04: thử lại tối đa 3 lần rồi mới sang DLT, không nuốt lỗi. */
    private void scheduleRetryOrDeadLetter(ConsumerRecord<String, String> record, int attempt) {
        if (attempt >= maxAttempts) {
            log.error("het {} lan thu, day sang {}", maxAttempts, dltTopic);
            toDeadLetter(record, attempt);
            return;
        }

        int next = attempt + 1;
        long delay = backoffMs * (1L << (attempt - 1));   // 1s, 2s, 4s...
        log.info("se thu lai lan {} sau {}ms", next, delay);

        // Không ngủ trong listener: ngủ ở đây sẽ chặn cả partition.
        scheduler.schedule(() -> {
            ProducerRecord<String, String> retry =
                    new ProducerRecord<>(topic, record.key(), record.value());
            copyHeaderExcept(record, retry, HEADER_ATTEMPT);
            retry.headers().add(new RecordHeader(HEADER_ATTEMPT,
                    String.valueOf(next).getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(retry);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void toDeadLetter(ConsumerRecord<String, String> record, int attempt) {
        ProducerRecord<String, String> dlt =
                new ProducerRecord<>(dltTopic, record.key(), record.value());
        copyHeaderExcept(record, dlt, HEADER_ATTEMPT);
        dlt.headers().add(new RecordHeader(HEADER_ATTEMPT,
                String.valueOf(attempt).getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(dlt);
    }

    private void copyHeaderExcept(ConsumerRecord<String, String> from,
                                  ProducerRecord<String, String> to, String skip) {
        for (Header h : from.headers()) {
            if (!skip.equals(h.key())) {
                to.headers().add(h);
            }
        }
        if (from.headers().lastHeader(HEADER_EVENT_TYPE) == null) {
            to.headers().add(new RecordHeader(HEADER_EVENT_TYPE, "unknown".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private String sseJson(PaymentEventView event, NotificationRouter.Target target) {
        return String.format(
                "{\"eventType\":\"%s\",\"orderId\":\"%s\",\"customerId\":\"%s\",\"channel\":\"%s\","
                        + "\"amount\":%d,\"message\":\"%s\"}",
                event.eventType(), event.orderId(), target.customerId(), target.channel(),
                event.amount(), target.message());
    }

    private int attemptOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(HEADER_ATTEMPT);
        if (header == null) {
            return 1;
        }
        try {
            return Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
