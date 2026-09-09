package com.ewallet.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Giao dịch nghiệp vụ do payment-business sở hữu. Hai pha:
 * AUTHORIZED (giữ tiền, ledger PENDING) -> CAPTURED (chốt sổ) hoặc -> REVERSED (bù trừ).
 * Xem docs/diagrams/01-state-machines.md §2.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    public static final String AUTHORIZED = "AUTHORIZED";
    public static final String CAPTURED = "CAPTURED";
    public static final String REVERSED = "REVERSED";
    public static final String REJECTED = "REJECTED";
    public static final String HELD = "HELD";

    @Id
    @Column(name = "txn_id")
    private UUID txnId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "payment_type", nullable = false)
    private String paymentType;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long fee;

    @Column(nullable = false)
    private String currency;

    @Column(name = "amount_vnd", nullable = false)
    private long amountVnd;

    @Column(name = "source_account_id")
    private UUID sourceAccountId;

    @Column(name = "dest_account_id")
    private UUID destAccountId;

    @Column(nullable = false)
    private String status;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "partner_ref")
    private String partnerRef;

    @Column(name = "partner_code")
    private String partnerCode;

    @Column(name = "counterparty_customer_id")
    private String counterpartyCustomerId;

    @Column(name = "reversed_txn_id")
    private UUID reversedTxnId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getTxnId() { return txnId; }
    public void setTxnId(UUID txnId) { this.txnId = txnId; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getPaymentType() { return paymentType; }
    public void setPaymentType(String paymentType) { this.paymentType = paymentType; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public long getFee() { return fee; }
    public void setFee(long fee) { this.fee = fee; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getAmountVnd() { return amountVnd; }
    public void setAmountVnd(long amountVnd) { this.amountVnd = amountVnd; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public void setSourceAccountId(UUID sourceAccountId) { this.sourceAccountId = sourceAccountId; }
    public UUID getDestAccountId() { return destAccountId; }
    public void setDestAccountId(UUID destAccountId) { this.destAccountId = destAccountId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getPartnerRef() { return partnerRef; }
    public void setPartnerRef(String partnerRef) { this.partnerRef = partnerRef; }
    public String getPartnerCode() { return partnerCode; }
    public void setPartnerCode(String partnerCode) { this.partnerCode = partnerCode; }
    public String getCounterpartyCustomerId() { return counterpartyCustomerId; }
    public void setCounterpartyCustomerId(String counterpartyCustomerId) { this.counterpartyCustomerId = counterpartyCustomerId; }
    public UUID getReversedTxnId() { return reversedTxnId; }
    public void setReversedTxnId(UUID reversedTxnId) { this.reversedTxnId = reversedTxnId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
