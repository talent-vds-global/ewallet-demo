# Sơ đồ tổng thể — container, giao thức, bản đồ flow

> Mermaid. Xem trực tiếp trên GitLab/GitHub, VS Code (extension Markdown Preview Mermaid) hoặc <https://mermaid.live>.
> Chi tiết nghiệp vụ: [`../specs/README.md`](../specs/README.md).

---

## 1. Container diagram

```mermaid
flowchart TB
    subgraph client[Client]
        APP[Mobile App]
    end

    subgraph platform[Demo app - ewallet]
        GW["ewallet-gateway<br/>8080 · HTTP<br/>khong co control flow"]
        BFF["ewallet-business-customer-mobileapp<br/>8081 · HTTP BFF<br/>control flow nhe"]
        ORD["ewallet-payment-order<br/>8082 · HTTP + gRPC client + Kafka<br/>control flow nang - saga"]
        BIZ["ewallet-payment-business<br/>8083 HTTP · 9091 gRPC + Kafka producer<br/>control flow nang - business rule"]
        TP["ewallet-third-party<br/>8084 · HTTP + WebSocket<br/>control flow vua"]
        NTF["ewallet-notification<br/>8085 · Kafka consumer + SSE<br/>control flow nhe"]
        PS["partner-sim<br/>8090 · test double"]
    end

    subgraph infra[Ha tang]
        PG[("PostgreSQL 16<br/>orderdb · paymentdb<br/>thirdpartydb · notifdb")]
        K{{"Kafka 3.8.1<br/>ewallet.payment.events<br/>+ .DLT"}}
        OTEL["otel-collector<br/>4317 OTLP"]
        JG["Jaeger UI<br/>16686"]
    end

    APP -->|HTTP| GW
    GW -->|HTTP| BFF
    GW -->|HTTP /orders| ORD
    GW -->|SSE| NTF
    BFF -->|HTTP| ORD
    ORD -->|gRPC| BIZ
    BIZ -->|HTTP| TP
    TP -->|HTTP| PS
    TP -->|WebSocket ket noi dai| PS
    BIZ -->|produce| K
    K -->|notification-cg| NTF
    K -->|order-status-cg| ORD
    NTF -->|SSE ket noi dai| APP

    ORD -.JDBC.-> PG
    BIZ -.JDBC.-> PG
    TP -.JDBC.-> PG
    NTF -.JDBC.-> PG

    GW -.OTLP.-> OTEL
    BFF -.OTLP.-> OTEL
    ORD -.OTLP.-> OTEL
    BIZ -.OTLP.-> OTEL
    TP -.OTLP.-> OTEL
    NTF -.OTLP.-> OTEL
    PS -.OTLP.-> OTEL
    OTEL --> JG
    OTEL -->|traces.jsonl| FILE[/"file cho Trace Analyzer"/]
```

---

## 2. Bản đồ flow trên cùng một topology

Mỗi flow là một tập con của sơ đồ trên. Đặt cạnh nhau để thấy phần dùng chung và phần riêng.

```mermaid
flowchart LR
    subgraph F1F2["F1 nap tien - F2 hoa don telco"]
        direction LR
        a1[gateway] --> a2[mobileapp] --> a3[order] --> a4[business] --> a5[third-party] --> a6[partner-sim]
        a4 --> a7{{Kafka}} --> a8[notification]
        a7 --> a3
    end

    subgraph F3["F3 chuyen tien P2P"]
        direction LR
        b1[gateway] --> b2[mobileapp] --> b3[order] --> b4[business]
        b4 --> b7{{Kafka}} --> b8[notification]
        b7 --> b3
    end

    subgraph F4["F4 loi va hoan tien"]
        direction LR
        c3[order] --> c4[business] --> c5[third-party] --> c6[partner-sim]
        c6 -.DECLINED.-> c5 -.-> c4 -.-> c3
        c3 ==>|ReversePayment| c4
        c4 --> c7{{Kafka}} --> c8[notification]
    end

    subgraph F6["F6 lich su giao dich"]
        direction LR
        d1[gateway] --> d2[mobileapp] --> d3[order] --> d9[(orderdb)]
    end
```

**Đọc ra được gì:**
- `order → business` xuất hiện ở F1, F2, F3, F4 → là **xương sống**. Sửa `business` chạm 5/6 flow.
- `third-party → partner-sim` chỉ ở F1, F2, F4 → sửa `third-party` **không** chạm F3, F6.
- F6 không chạm service nào ngoài `order` → lỗi ở F6 (N+1) không ảnh hưởng giao dịch, chỉ ảnh hưởng trải nghiệm đọc.

---

## 3. Phủ giao thức

```mermaid
flowchart LR
    subgraph http[HTTP REST]
        h1[client to gateway]
        h2[gateway to mobileapp]
        h3[mobileapp to order]
        h4[business to third-party]
        h5[third-party to partner-sim]
    end
    subgraph grpc[gRPC]
        g1["order to business<br/>5 method<br/>Authorize · ExecutePartner<br/>Confirm · Reverse · InquireBill"]
    end
    subgraph kafka[Kafka async]
        k1["business produce<br/>ewallet.payment.events"]
        k2["notification-cg consume"]
        k3["order-status-cg consume"]
        k4["ewallet.payment.events.DLT"]
    end
    subgraph long[Ket noi dai]
        l1["third-party ws partner-sim"]
        l2["notification SSE client"]
    end
    subgraph jdbc[JDBC]
        j1[order to orderdb]
        j2[business to paymentdb]
        j3[third-party to thirdpartydb]
        j4[notification to notifdb]
    end
```

Mục đích: chứng minh collector thu được context **ngoài HTTP** — `rpc.*`, `messaging.*`, `db.*`,
và span của kết nối dài.

---

## 4. Saga của `payment-order` — bốn bước và nhánh bù trừ

```mermaid
flowchart TD
    START([POST /api/orders]) --> S1[S1 CREATE_ORDER<br/>INSERT payment_orders CREATED]
    S1 --> S2[S2 AUTHORIZE<br/>gRPC AuthorizePayment]

    S2 -->|REJECTED| R1[order REJECTED<br/>khong giu tien<br/>business publish PaymentFailed]
    S2 -->|HELD| R2[order HELD<br/>giu tien - dung saga<br/>cho duyet thu cong]
    S2 -->|AUTHORIZED| Q{partnerCode co khong}

    Q -->|co - F1 F2| S3[S3 PARTNER_EXECUTE<br/>gRPC ExecutePartnerPayment]
    Q -->|khong - F3| S4

    S3 -->|SUCCESS| S4[S4 CONFIRM<br/>gRPC ConfirmPayment]
    S3 -->|DECLINED hoac TIMEOUT| C1[order COMPENSATING]

    S4 -->|OK| DONE[order COMPLETED<br/>business publish PaymentCompleted]
    S4 -->|loi sau 3 lan thu| C1

    C1 --> C2[gRPC ReversePayment]
    C2 -->|OK| REF[order REFUNDED<br/>business publish PaymentRefunded]
    C2 -->|loi| FAIL[order FAILED<br/>reason COMPENSATION_FAILED<br/>log ERROR - can nguoi xu ly]

    DONE --> END([tra ket qua cho client])
    R1 --> END
    R2 --> END
    REF --> END
    FAIL --> END
```

---

## 5. Fan-out Kafka — một topic, hai consumer group

```mermaid
flowchart LR
    BIZ["ewallet-payment-business<br/>PaymentEventPublisher"] -->|produce key=orderId| T{{"topic ewallet.payment.events"}}
    T -->|group notification-cg| NTF["ewallet-notification<br/>sinh thong bao cho khach"]
    T -->|group order-status-cg| ORD["ewallet-payment-order<br/>chot trang thai don"]
    NTF -->|that bai 3 lan| DLT{{"ewallet.payment.events.DLT"}}
    NTF -->|SSE| APP[Mobile App]
    ORD --> ODB[(orderdb)]
    NTF --> NDB[(notifdb)]
```

Đổi schema event → **cả hai** group bị ảnh hưởng nhưng theo cách khác nhau:
`notification` hỏng phần nội dung thông báo, `order` hỏng phần ánh xạ trạng thái.
Đây là ca kiểm chứng impact analysis qua message queue.

---

## 6. Sáu lỗi có chủ đích nằm ở đâu

```mermaid
flowchart TB
    subgraph ORD["ewallet-payment-order"]
        E4["LOI 4<br/>OrderHistoryService<br/>N+1 query + thieu index<br/>F6"]
    end
    subgraph BIZ["ewallet-payment-business"]
        E1["LOI 1<br/>LimitPolicy<br/>hang so 100tr vs spec 50tr<br/>F1 F2 F3"]
        E2["LOI 2<br/>ReviewPolicy nhanh HELD<br/>khong publish PaymentHeld<br/>F1 F2 F3"]
        E5["LOI 5<br/>ReviewPolicy nhanh HELD<br/>Thread.sleep 700ms vs NFR 500ms<br/>F1 F2 F3"]
        E6["LOI 6<br/>gRPC handler<br/>bo qua field currency<br/>F2"]
    end
    subgraph TP["ewallet-third-party"]
        E3["LOI 3<br/>TopupAdapter duong TOP_UP<br/>chay that nhung 0 test<br/>F1"]
    end

    E2 -.cung mot nhanh.-> E5
```

| Lỗi | Loại | Chỉ lộ ra khi đối chiếu |
|---|---|---|
| #1 | Spec drift | spec ↔ code ↔ DB |
| #2 | Nhánh quên event | spec ↔ trace |
| #3 | Test gap | coverage ↔ trace |
| #4 | DB anti-pattern | db-quality ↔ spec/NFR |
| #5 | Vi phạm NFR | trace ↔ NFR |
| #6 | Drift qua gRPC | attribute span gRPC ↔ spec |

Không lỗi nào phát hiện được chỉ bằng **một** nguồn dữ liệu — đó là luận điểm của đề tài.
