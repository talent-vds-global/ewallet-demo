package com.ewallet.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Tỉ giá quy đổi về VND (R-CURRENCY-01, R-FX-01). */
@Entity
@Table(name = "fx_rates")
public class FxRate {

    @Id
    private String currency;

    @Column(name = "rate_to_vnd", nullable = false)
    private long rateToVnd;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getRateToVnd() { return rateToVnd; }
    public void setRateToVnd(long rateToVnd) { this.rateToVnd = rateToVnd; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
