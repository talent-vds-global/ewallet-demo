package com.ewallet.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Nhật ký từng lần thử gửi. Giữ cả lần hỏng để đo tỉ lệ retry và DLT. */
@Entity
@Table(name = "notification_sent_log")
public class NotificationSentLog {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String DEAD_LETTER = "DEAD_LETTER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "outbox_id", nullable = false)
    private UUID outboxId;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false)
    private String result;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    public static NotificationSentLog of(UUID outboxId, int attempt, String result) {
        NotificationSentLog l = new NotificationSentLog();
        l.outboxId = outboxId;
        l.attempt = attempt;
        l.result = result;
        l.sentAt = OffsetDateTime.now();
        return l;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getOutboxId() { return outboxId; }
    public void setOutboxId(UUID outboxId) { this.outboxId = outboxId; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }
}
