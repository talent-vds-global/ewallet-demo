# Máy trạng thái & vòng đời dữ liệu

> Bổ sung cho [`../specs/00-domain-and-conventions.md`](../specs/00-domain-and-conventions.md) §7.

---

## 1. Trạng thái Order — `payment_orders.status` (sở hữu bởi `payment-order`)

```mermaid
stateDiagram-v2
    [*] --> CREATED : S1 INSERT payment_orders

    CREATED --> AUTHORIZING : S2 goi gRPC AuthorizePayment
    AUTHORIZING --> REJECTED : business tra REJECTED
    AUTHORIZING --> HELD : business tra HELD
    AUTHORIZING --> AUTHORIZED : business tra AUTHORIZED

    AUTHORIZED --> EXECUTING : S3 co partnerCode
    AUTHORIZED --> CONFIRMING : S4 khong co partnerCode - F3

    EXECUTING --> CONFIRMING : doi tac SUCCESS
    EXECUTING --> COMPENSATING : doi tac DECLINED hoac TIMEOUT

    CONFIRMING --> COMPLETED : ConfirmPayment OK
    CONFIRMING --> COMPENSATING : ConfirmPayment loi sau 3 lan thu

    COMPLETED --> COMPENSATING : Ops goi refund - F4d

    COMPENSATING --> REFUNDED : ReversePayment OK
    COMPENSATING --> FAILED : ReversePayment loi - COMPENSATION_FAILED

    REJECTED --> [*]
    HELD --> [*]
    COMPLETED --> [*]
    REFUNDED --> [*]
    FAILED --> [*]

    note right of HELD
        Terminal tam - tien van bi giu
        Cho duyet thu cong
        R-COMP-07 cam tu dong bu tru
    end note

    note right of FAILED
        Can nguoi xu ly
        Log muc ERROR
    end note
```

---

## 2. Trạng thái Transaction — `payment_transactions.status` (sở hữu bởi `payment-business`)

```mermaid
stateDiagram-v2
    [*] --> REJECTED : rule vi pham - khong ghi ledger
    [*] --> HELD : R-REVIEW-01 - giu tien
    [*] --> AUTHORIZED : hop le - ledger PENDING

    AUTHORIZED --> CAPTURED : ConfirmPayment - ledger POSTED
    AUTHORIZED --> REVERSED : ReversePayment - but toan nguoc
    CAPTURED --> REVERSED : hoan tien chu dong F4d
    HELD --> AUTHORIZED : Ops duyet - ngoai pham vi demo
    HELD --> REVERSED : Ops tu choi - ngoai pham vi demo

    REJECTED --> [*]
    CAPTURED --> [*]
    REVERSED --> [*]
```

Mỗi lần `REVERSED` sinh **một transaction mới** `payment_type = REFUND` với `reversed_txn_id`
trỏ về transaction gốc. Transaction gốc không bị xoá.

---

## 3. Trạng thái Ledger entry — `ledger_entries.status`

```mermaid
stateDiagram-v2
    [*] --> PENDING : AuthorizePayment giu tien
    PENDING --> POSTED : ConfirmPayment chot so
    PENDING --> REVERSED : ReversePayment truoc khi chot
    POSTED --> REVERSED : hoan tien sau khi da chot
    POSTED --> [*]
    REVERSED --> [*]
```

Sổ cái **append-only**: bù trừ không sửa dòng cũ mà thêm dòng ngược chiều với `txn_id` mới.

---

## 4. Ánh xạ event → trạng thái (consumer `order-status-cg`)

```mermaid
flowchart LR
    E1[PaymentCompleted] --> S1[COMPLETED]
    E2[PaymentFailed] --> S2[FAILED]
    E3[PaymentHeld] --> S3[HELD]
    E4[PaymentRefunded] --> S4[REFUNDED]

    S1 -.R-ORDST-02 khong ghi de.-> X[("trang thai terminal khac<br/>chi ghi order_steps va log WARN")]
    S2 -.-> X
    S3 -.-> X
    S4 -.-> X
```

---

## 5. Trạng thái Partner transaction — `partner_transactions.status`

```mermaid
stateDiagram-v2
    [*] --> PENDING : third-party ghi truoc khi goi doi tac
    PENDING --> SUCCESS : doi tac tra SUCCESS
    PENDING --> FAILED : DECLINED hoac TIMEOUT
    SUCCESS --> SUCCESS : nhan frame SETTLEMENT qua WebSocket - dien settled_at
    FAILED --> [*]
    SUCCESS --> [*]
```

`settled_at` được điền **sau** khi HTTP đã trả — bằng chứng cho kết nối dài WebSocket có ích thật,
không phải gắn vào cho có.

---

## 6. Trạng thái Notification — `notification_outbox.status`

```mermaid
stateDiagram-v2
    [*] --> PENDING : consumer ghi outbox theo tung kenh
    PENDING --> SENT : NotificationSender thanh cong
    PENDING --> RETRYING : that bai - x-attempt tang
    RETRYING --> SENT : lan thu sau thanh cong
    RETRYING --> DEAD_LETTER : het 3 lan - chuyen sang topic DLT
    SENT --> [*]
    DEAD_LETTER --> [*]
```

---

## 7. Vòng đời một giao dịch qua bốn DB

```mermaid
sequenceDiagram
    autonumber
    participant ODB as orderdb
    participant PDB as paymentdb
    participant TDB as thirdpartydb
    participant NDB as notifdb

    note over ODB: S1
    ODB->>ODB: INSERT payment_orders CREATED
    ODB->>ODB: INSERT order_steps CREATE_ORDER DONE

    note over PDB: S2 AUTHORIZE
    PDB->>PDB: SELECT accounts va account_balances
    PDB->>PDB: SELECT limit_config va daily_usage
    PDB->>PDB: INSERT payment_transactions AUTHORIZED
    PDB->>PDB: INSERT ledger_entries PENDING
    PDB->>PDB: UPSERT daily_usage
    ODB->>ODB: UPDATE payment_orders AUTHORIZED

    note over TDB: S3 PARTNER_EXECUTE
    TDB->>TDB: SELECT partner_config
    TDB->>TDB: INSERT partner_transactions PENDING
    TDB->>TDB: UPDATE partner_transactions SUCCESS

    note over PDB: S4 CONFIRM
    PDB->>PDB: UPDATE ledger_entries POSTED
    PDB->>PDB: UPDATE account_balances
    PDB->>PDB: UPDATE payment_transactions CAPTURED
    ODB->>ODB: UPDATE payment_orders COMPLETED

    note over NDB: duoi bat dong bo
    NDB->>NDB: INSERT notification_outbox
    NDB->>NDB: INSERT notification_sent_log SENT
    ODB->>ODB: INSERT order_steps EVENT_APPLIED
    TDB->>TDB: UPDATE partner_transactions settled_at
```

Mười chín thao tác DB trên bốn database cho một giao dịch F1 — đây là lượng `db.statement`
mà `database-quality-library` và OTel agent phải thu được.
