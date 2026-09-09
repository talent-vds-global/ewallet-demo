package com.ewallet.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** Tổng giá trị giao dịch trong ngày của một khách (R-USAGE-01, R-USAGE-02). */
@Entity
@Table(name = "daily_usage")
@IdClass(DailyUsageId.class)
public class DailyUsage {

    @Id
    @Column(name = "customer_id")
    private String customerId;

    @Id
    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public LocalDate getUsageDate() { return usageDate; }
    public void setUsageDate(LocalDate usageDate) { this.usageDate = usageDate; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
}
