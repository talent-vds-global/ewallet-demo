package com.ewallet.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Bút toán kép. R-LEDGER-01: mỗi transaction sinh đúng 2 dòng (1 DEBIT + 1 CREDIT) cùng txn_id,
 * cộng thêm 2 dòng nữa nếu có phí. Sổ cái append-only — bù trừ ghi dòng ngược, không sửa dòng cũ.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    public static final String DEBIT = "DEBIT";
    public static final String CREDIT = "CREDIT";

    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_FEE = "FEE";
    public static final String TYPE_REFUND = "REFUND";

    public static final String PENDING = "PENDING";
    public static final String POSTED = "POSTED";
    public static final String REVERSED = "REVERSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_id", nullable = false)
    private UUID txnId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String direction;

    @Column(nullable = false)
    private long amount;

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public static LedgerEntry of(UUID txnId, UUID accountId, String direction, long amount, String entryType) {
        LedgerEntry e = new LedgerEntry();
        e.txnId = txnId;
        e.accountId = accountId;
        e.direction = direction;
        e.amount = amount;
        e.entryType = entryType;
        e.status = PENDING;
        e.createdAt = OffsetDateTime.now();
        return e;
    }

    /** Dấu của bút toán khi cộng vào số dư: DEBIT là trừ, CREDIT là cộng. */
    public long signedAmount() {
        return DEBIT.equals(direction) ? -amount : amount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getTxnId() { return txnId; }
    public void setTxnId(UUID txnId) { this.txnId = txnId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
