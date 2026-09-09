package com.ewallet.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Cấu hình hạn mức (docs/specs/00-domain-and-conventions.md §9). */
@Entity
@Table(name = "limit_config")
public class LimitConfig {

    @Id
    private String id;

    @Column(name = "limit_value", nullable = false)
    private long limitValue;

    @Column(nullable = false)
    private String currency;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getLimitValue() { return limitValue; }
    public void setLimitValue(long limitValue) { this.limitValue = limitValue; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
