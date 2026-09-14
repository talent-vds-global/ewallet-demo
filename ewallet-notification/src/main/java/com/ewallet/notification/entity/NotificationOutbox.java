package com.ewallet.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Một thông báo cần gửi, tách theo kênh. Máy trạng thái: docs/diagrams/01-state-machines.md §6.
 * Ghi vào đây trước rồi mới gửi, để thông báo không biến mất nếu việc gửi hỏng.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String RETRYING = "RETRYING";
    public static final String DEAD_LETTER = "DEAD_LETTER";

    public static final String PUSH = "PUSH";
    public static final String SMS = "SMS";
    public static final String EMAIL = "EMAIL";

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String status;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
