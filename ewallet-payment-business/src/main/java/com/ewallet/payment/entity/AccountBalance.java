package com.ewallet.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Số dư ví. Cập nhật khi ConfirmPayment chốt sổ hoặc khi bù trừ. */
@Entity
@Table(name = "account_balances")
public class AccountBalance {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(nullable = false)
    private long balance;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
