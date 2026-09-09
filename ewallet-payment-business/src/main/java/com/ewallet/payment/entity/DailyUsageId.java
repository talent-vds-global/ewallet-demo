package com.ewallet.payment.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** Khoá kép của daily_usage (customer_id, usage_date). */
public class DailyUsageId implements Serializable {

    private String customerId;
    private LocalDate usageDate;

    public DailyUsageId() { }

    public DailyUsageId(String customerId, LocalDate usageDate) {
        this.customerId = customerId;
        this.usageDate = usageDate;
    }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public LocalDate getUsageDate() { return usageDate; }
    public void setUsageDate(LocalDate usageDate) { this.usageDate = usageDate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DailyUsageId other)) return false;
        return Objects.equals(customerId, other.customerId) && Objects.equals(usageDate, other.usageDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerId, usageDate);
    }
}
