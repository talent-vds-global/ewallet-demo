package com.ewallet.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Đơn giao dịch — do payment-order sở hữu, có vòng đời saga.
 * Máy trạng thái: docs/diagrams/01-state-machines.md §1.
 */
@Entity
@Table(name = "payment_orders")
public class PaymentOrder {

    public static final String CREATED = "CREATED";
    public static final String AUTHORIZING = "AUTHORIZING";
    public static final String AUTHORIZED = "AUTHORIZED";
    public static final String EXECUTING = "EXECUTING";
    public static final String CONFIRMING = "CONFIRMING";
    public static final String COMPLETED = "COMPLETED";
    public static final String HELD = "HELD";
    public static final String REJECTED = "REJECTED";
    public static final String COMPENSATING = "COMPENSATING";
    public static final String REFUNDED = "REFUNDED";
    public static final String FAILED = "FAILED";

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "payment_type", nullable = false)
    private String paymentType;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "partner_code")
    private String partnerCode;

    @Column(nullable = false)
    private String status;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "dest_customer_id")
    private String destCustomerId;

    @Column(name = "bill_code")
    private String billCode;

    @Column(name = "account_ref")
    private String accountRef;

    @Column(nullable = false)
    private long fee;

    @Column(name = "amount_vnd")
    private Long amountVnd;

    @Column(name = "txn_id")
    private UUID txnId;

    @Column(name = "partner_ref")
    private String partnerRef;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** Trạng thái kết thúc — không được ghi đè bởi event Kafka đến sau (R-ORDST-02). */
    public boolean isTerminal() {
        return COMPLETED.equals(status) || REFUNDED.equals(status)
                || FAILED.equals(status) || REJECTED.equals(status);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getPaymentType() { return paymentType; }
    public void setPaymentType(String paymentType) { this.paymentType = paymentType; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPartnerCode() { return partnerCode; }
    public void setPartnerCode(String partnerCode) { this.partnerCode = partnerCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getDestCustomerId() { return destCustomerId; }
    public void setDestCustomerId(String destCustomerId) { this.destCustomerId = destCustomerId; }
    public String getBillCode() { return billCode; }
    public void setBillCode(String billCode) { this.billCode = billCode; }
    public String getAccountRef() { return accountRef; }
    public void setAccountRef(String accountRef) { this.accountRef = accountRef; }
    public long getFee() { return fee; }
    public void setFee(long fee) { this.fee = fee; }
    public Long getAmountVnd() { return amountVnd; }
    public void setAmountVnd(Long amountVnd) { this.amountVnd = amountVnd; }
    public UUID getTxnId() { return txnId; }
    public void setTxnId(UUID txnId) { this.txnId = txnId; }
    public String getPartnerRef() { return partnerRef; }
    public void setPartnerRef(String partnerRef) { this.partnerRef = partnerRef; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
