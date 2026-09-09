package com.ewallet.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Một bước của saga. Ghi lại để dựng được timeline khi điều tra sự cố. */
@Entity
@Table(name = "order_steps")
public class OrderStep {

    public static final String CREATE_ORDER = "CREATE_ORDER";
    public static final String AUTHORIZE = "AUTHORIZE";
    public static final String PARTNER_EXECUTE = "PARTNER_EXECUTE";
    public static final String CONFIRM = "CONFIRM";
    public static final String COMPENSATE = "COMPENSATE";
    public static final String EVENT_APPLIED = "EVENT_APPLIED";

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String COMPENSATED = "COMPENSATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(name = "step_status", nullable = false)
    private String stepStatus;

    @Column
    private String detail;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public static OrderStep of(UUID orderId, String stepName, String stepStatus,
                               String detail, int attempt, Long durationMs) {
        OrderStep s = new OrderStep();
        s.orderId = orderId;
        s.stepName = stepName;
        s.stepStatus = stepStatus;
        s.detail = detail;
        s.attempt = attempt;
        s.durationMs = durationMs;
        s.createdAt = OffsetDateTime.now();
        return s;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    public String getStepStatus() { return stepStatus; }
    public void setStepStatus(String stepStatus) { this.stepStatus = stepStatus; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
