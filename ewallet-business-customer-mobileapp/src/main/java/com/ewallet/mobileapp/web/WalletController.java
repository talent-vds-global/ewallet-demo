package com.ewallet.mobileapp.web;

import com.ewallet.mobileapp.client.OrderClient;
import com.ewallet.mobileapp.dto.WalletDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
 * BFF client-facing — nơi khởi tạo giao dịch (docs/specs/01-api-contracts.md §3).
 *
 * <p>Control flow nhẹ: validate cú pháp, đổi ngôn ngữ app khách hàng (nạp tiền / chuyển tiền /
 * hoá đơn) sang ngôn ngữ đơn hàng, rồi chuyển tiếp xuống payment-order. Không chạm DB,
 * không gọi thẳng payment-business.</p>
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private static final String DEFAULT_CURRENCY = "VND";
    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    /** R-TELCO-02: mệnh giá nạp điện thoại hợp lệ. */
    private static final Set<Long> TELCO_DENOMINATIONS =
            Set.of(10_000L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L);

    private final OrderClient orderClient;

    public WalletController(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    // ---------------------------------------------------------------- F1
    @PostMapping("/topup")
    @Operation(summary = "Nap tien vao vi qua doi tac")
    public ResponseEntity<String> topup(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody WalletDtos.TopupRequest request) {

        ResponseEntity<String> missing = requireIdempotencyKey(idempotencyKey);
        if (missing != null) {
            return missing;
        }

        return orderClient.createOrder(new WalletDtos.CreateOrderCommand(
                request.customerId(), "TOP_UP", request.amount(),
                currencyOf(request.currency()), request.partnerCode(),
                null, null, request.partnerAccountRef()), idempotencyKey);
    }

    // ---------------------------------------------------------------- F2 tra cứu
    @GetMapping("/bill")
    @Operation(summary = "Tra cuu hoa don truoc khi thanh toan")
    public ResponseEntity<String> billInquiry(@RequestParam String partnerCode,
                                              @RequestParam String billCode,
                                              @RequestParam(required = false) String customerId) {
        String path = "/api/orders/bill-inquiry?partnerCode=" + encode(partnerCode)
                + "&billCode=" + encode(billCode)
                + (customerId == null ? "" : "&customerId=" + encode(customerId));
        return orderClient.get(path);
    }

    // ---------------------------------------------------------------- F2 thanh toán
    @PostMapping("/bill/pay")
    @Operation(summary = "Thanh toan hoa don")
    public ResponseEntity<String> payBill(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody WalletDtos.BillPaymentRequest request) {

        ResponseEntity<String> missing = requireIdempotencyKey(idempotencyKey);
        if (missing != null) {
            return missing;
        }

        return orderClient.createOrder(new WalletDtos.CreateOrderCommand(
                request.customerId(), "BILL", request.amount(),
                currencyOf(request.currency()), request.partnerCode(),
                null, request.billCode(), null), idempotencyKey);
    }

    // ---------------------------------------------------------------- F2 telco
    @PostMapping("/telco/topup")
    @Operation(summary = "Nap tien dien thoai")
    public ResponseEntity<String> telcoTopup(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody WalletDtos.TelcoTopupRequest request) {

        ResponseEntity<String> missing = requireIdempotencyKey(idempotencyKey);
        if (missing != null) {
            return missing;
        }
        // R-TELCO-01: số thuê bao 10 chữ số.
        if (!request.phoneNumber().matches("\\d{10}")) {
            return error(400, "INVALID_PHONE_NUMBER", "So thue bao phai gom 10 chu so");
        }
        // R-TELCO-02: chỉ nhận mệnh giá trong danh sách.
        if (!TELCO_DENOMINATIONS.contains(request.amount())) {
            return error(422, "INVALID_DENOMINATION",
                    "Menh gia khong hop le. Cho phep: 10.000, 20.000, 50.000, 100.000, 200.000, 500.000");
        }

        return orderClient.createOrder(new WalletDtos.CreateOrderCommand(
                request.customerId(), "TELCO", request.amount(),
                currencyOf(request.currency()), request.partnerCode(),
                null, null, request.phoneNumber()), idempotencyKey);
    }

    // ---------------------------------------------------------------- F3
    @PostMapping("/transfer")
    @Operation(summary = "Chuyen tien cho vi khac")
    public ResponseEntity<String> transfer(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody WalletDtos.TransferRequest request) {

        ResponseEntity<String> missing = requireIdempotencyKey(idempotencyKey);
        if (missing != null) {
            return missing;
        }
        // R-P2P-01: chặn sớm ngay ở BFF cho đỡ tốn một vòng xuống dưới.
        if (request.customerId().equalsIgnoreCase(request.destCustomerId())) {
            return error(422, "SELF_TRANSFER_NOT_ALLOWED", "Khong the chuyen tien cho chinh minh");
        }

        return orderClient.createOrder(new WalletDtos.CreateOrderCommand(
                request.customerId(), "P2P", request.amount(),
                currencyOf(request.currency()), null,
                request.destCustomerId(), null, null), idempotencyKey);
    }

    // ---------------------------------------------------------------- F6
    @GetMapping("/transactions")
    @Operation(summary = "Lich su giao dich")
    public ResponseEntity<String> transactions(@RequestParam String customerId,
                                               @RequestParam(required = false) Integer limit) {
        String path = "/api/orders/history?customerId=" + encode(customerId)
                + (limit == null ? "" : "&limit=" + limit);
        return orderClient.get(path);
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Tra cuu mot giao dich")
    public ResponseEntity<String> order(@PathVariable String orderId) {
        return orderClient.get("/api/orders/" + encode(orderId));
    }

    // ---------------------------------------------------------------- helper
    private ResponseEntity<String> requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {         // R-IDEM-01
            return error(400, "MISSING_IDEMPOTENCY_KEY", "Thieu header X-Idempotency-Key");
        }
        return null;
    }

    private String currencyOf(String currency) {
        return (currency == null || currency.isBlank()) ? DEFAULT_CURRENCY : currency.toUpperCase();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> error(int status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"traceId\":null}");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> onInvalid(MethodArgumentNotValidException e) {
        List<String> problems = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .toList();
        log.debug("request khong hop le: {}", problems);
        return error(400, "BAD_REQUEST",
                problems.isEmpty() ? "Du lieu khong hop le" : problems.get(0));
    }
}
