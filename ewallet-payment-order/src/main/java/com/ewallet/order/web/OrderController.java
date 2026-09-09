package com.ewallet.order.web;

import com.ewallet.order.dto.OrderDtos;
import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.entity.PaymentOrder;
import com.ewallet.order.grpc.PaymentBusinessClient;
import com.ewallet.order.history.OrderHistoryService;
import com.ewallet.order.repo.OrderStepRepository;
import com.ewallet.order.repo.PaymentOrderRepository;
import com.ewallet.order.saga.DuplicateRequestException;
import com.ewallet.order.saga.PaymentSagaOrchestrator;
import com.ewallet.payment.contract.grpc.InquireBillRequest;
import com.ewallet.payment.contract.grpc.InquireBillResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API của payment-order. Hợp đồng: docs/specs/01-api-contracts.md §4.
 * Không client-facing — đi qua BFF hoặc route /orders/** của gateway.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final PaymentSagaOrchestrator saga;
    private final OrderHistoryService historyService;
    private final PaymentOrderRepository orderRepository;
    private final OrderStepRepository stepRepository;
    private final PaymentBusinessClient businessClient;
    private final OrderMapper mapper;

    public OrderController(PaymentSagaOrchestrator saga,
                           OrderHistoryService historyService,
                           PaymentOrderRepository orderRepository,
                           OrderStepRepository stepRepository,
                           PaymentBusinessClient businessClient,
                           OrderMapper mapper) {
        this.saga = saga;
        this.historyService = historyService;
        this.orderRepository = orderRepository;
        this.stepRepository = stepRepository;
        this.businessClient = businessClient;
        this.mapper = mapper;
    }

    @GetMapping("/ping")
    @Operation(summary = "Smoke test - service va orderdb con song khong")
    public Map<String, Object> ping() {
        return Map.of("service", "ewallet-payment-order", "status", "UP",
                "orderCount", orderRepository.count());
    }

    /** Tạo đơn và chạy saga (F1–F4). */
    @PostMapping
    @Operation(summary = "Tao don va chay saga bon buoc")
    public ResponseEntity<?> create(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderDtos.CreateOrderRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {       // R-IDEM-01
            return ResponseEntity.badRequest().body(new OrderDtos.ErrorResponse(
                    "MISSING_IDEMPOTENCY_KEY", "Thieu header X-Idempotency-Key", null));
        }

        PaymentOrder order = saga.run(request, idempotencyKey);
        List<OrderStep> steps = stepRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        return ResponseEntity.status(httpStatusOf(order))
                .body(mapper.toResponse(order, steps));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Tra cuu mot don")
    public ResponseEntity<?> get(@PathVariable String orderId) {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            return badRequest("orderId khong hop le");
        }
        Optional<PaymentOrder> order = orderRepository.findById(id);
        if (order.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new OrderDtos.ErrorResponse(
                    "ORDER_NOT_FOUND", "Khong tim thay don " + orderId, null));
        }
        List<OrderStep> steps = stepRepository.findByOrderIdOrderByCreatedAtAsc(id);
        return ResponseEntity.ok(mapper.toResponse(order.get(), steps));
    }

    /** F6 — chứa lỗi có chủ đích #4 (N+1), xem OrderHistoryService. */
    @GetMapping("/history")
    @Operation(summary = "Lich su giao dich cua mot khach")
    public ResponseEntity<?> history(@RequestParam(required = false) String customerId,
                                     @RequestParam(required = false) Integer limit) {
        if (customerId == null || customerId.isBlank()) {
            return badRequest("Thieu tham so customerId");
        }
        return ResponseEntity.ok(historyService.history(customerId, limit));
    }

    /** F2 bước S0 — tra cứu hoá đơn, chỉ đọc, không tạo đơn. */
    @GetMapping("/bill-inquiry")
    @Operation(summary = "Tra cuu hoa don qua doi tac")
    public ResponseEntity<?> billInquiry(@RequestParam String partnerCode,
                                         @RequestParam String billCode,
                                         @RequestParam(required = false) String customerId) {
        InquireBillResponse r = businessClient.inquireBill(InquireBillRequest.newBuilder()
                .setPartnerCode(partnerCode)
                .setBillCode(billCode)
                .setCustomerId(customerId == null ? "" : customerId)
                .build());

        OrderDtos.BillInquiryResponse body = new OrderDtos.BillInquiryResponse(
                r.getStatus(), partnerCode, r.getBillCode(), r.getCustomerName(),
                r.getPeriod(), r.getAmount(), r.getCurrency(), r.getBillStatus());

        HttpStatus status = switch (r.getStatus()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ALREADY_PAID" -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(body);
    }

    /** F4d — Ops hoàn tiền chủ động cho đơn đã COMPLETED. */
    @PostMapping("/{orderId}/refund")
    @Operation(summary = "Hoan tien chu dong cho mot don da hoan tat")
    public ResponseEntity<?> refund(@PathVariable String orderId,
                                    @RequestBody(required = false) OrderDtos.RefundRequest body) {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            return badRequest("orderId khong hop le");
        }

        PaymentOrder order = saga.refund(id, body == null ? null : body.reason());
        List<OrderStep> steps = stepRepository.findByOrderIdOrderByCreatedAtAsc(id);
        return ResponseEntity.ok(mapper.toResponse(order, steps));
    }

    // =====================================================================================
    // Ánh xạ trạng thái đơn sang HTTP status (docs/specs/00-domain-and-conventions.md §6)
    // =====================================================================================

    private HttpStatus httpStatusOf(PaymentOrder order) {
        return switch (order.getStatus()) {
            case PaymentOrder.COMPLETED -> HttpStatus.OK;
            case PaymentOrder.HELD -> HttpStatus.ACCEPTED;
            case PaymentOrder.REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PaymentOrder.REFUNDED, PaymentOrder.FAILED ->
                    "PARTNER_TIMEOUT".equals(order.getReasonCode())
                            ? HttpStatus.GATEWAY_TIMEOUT      // R-COMP-08
                            : HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.OK;
        };
    }

    // =====================================================================================
    // Xử lý lỗi
    // =====================================================================================

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<OrderDtos.ErrorResponse> onDuplicate(DuplicateRequestException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new OrderDtos.ErrorResponse("DUPLICATE_REQUEST", e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<OrderDtos.ErrorResponse> onInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("Du lieu khong hop le");
        return ResponseEntity.badRequest()
                .body(new OrderDtos.ErrorResponse("BAD_REQUEST", detail, null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<OrderDtos.ErrorResponse> onNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new OrderDtos.ErrorResponse("ORDER_NOT_FOUND", e.getMessage(), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<OrderDtos.ErrorResponse> onConflict(IllegalStateException e) {
        log.warn("yeu cau khong hop le: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new OrderDtos.ErrorResponse(e.getMessage(), "Trang thai don khong cho phep", null));
    }

    private ResponseEntity<OrderDtos.ErrorResponse> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(new OrderDtos.ErrorResponse("BAD_REQUEST", message, null));
    }
}
