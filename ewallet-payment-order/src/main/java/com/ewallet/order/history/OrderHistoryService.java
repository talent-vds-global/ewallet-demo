package com.ewallet.order.history;

import com.ewallet.order.dto.OrderDtos;
import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.entity.PaymentOrder;
import com.ewallet.order.repo.OrderStepRepository;
import com.ewallet.order.repo.PaymentOrderRepository;
import com.ewallet.order.web.OrderMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra cứu lịch sử giao dịch (F6).
 *
 * <p>Spec: docs/specs/F6-transaction-history.md.</p>
 */
@Service
public class OrderHistoryService {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryService.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;      // R-HIST-03

    private final PaymentOrderRepository orderRepository;
    private final OrderStepRepository stepRepository;
    private final OrderMapper mapper;

    public OrderHistoryService(PaymentOrderRepository orderRepository,
                               OrderStepRepository stepRepository,
                               OrderMapper mapper) {
        this.orderRepository = orderRepository;
        this.stepRepository = stepRepository;
        this.mapper = mapper;
    }

    /**
     * Danh sách đơn của một khách, mới nhất trước, kèm các bước xử lý.
     *
     * <p>-------------------------------------------------------------------------------
     * LỖI CÓ CHỦ ĐÍCH #4 — N+1 QUERY. KHÔNG SỬA (hợp đồng nghiệm thu, architecture.md §6).
     *
     * R-HIST-06 yêu cầu lấy các bước của nhiều đơn bằng MỘT truy vấn
     * ({@code stepRepository.findByOrderIdInOrderByCreatedAtAsc}), và NFR-DB-01 giới hạn
     * mỗi lần gọi API tối đa 2 câu SQL.
     *
     * Vòng lặp dưới đây gọi một câu SELECT cho TỪNG đơn: 20 đơn thành 21 câu SQL.
     * Cột {@code order_steps.order_id} lại cố ý không có index nên mỗi câu là một seq scan.
     *
     * Hệ quả đo được: database-quality-library báo N_PLUS_ONE + MISSING_INDEX với
     * calledFrom trỏ đúng vào lớp này; trace có ~N+1 span db thay vì 2.
     * -------------------------------------------------------------------------------</p>
     */
    @Transactional(readOnly = true)
    public OrderDtos.HistoryResponse history(String customerId, Integer limit) {
        int size = normalizeLimit(limit);

        List<PaymentOrder> orders = orderRepository.findHistory(customerId, PageRequest.of(0, size));

        List<OrderDtos.OrderResponse> items = new ArrayList<>(orders.size());
        for (PaymentOrder order : orders) {
            List<OrderStep> steps = stepRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
            items.add(mapper.toResponse(order, steps));
        }

        log.debug("lich su customerId={} tra ve {} don", customerId, items.size());
        return new OrderDtos.HistoryResponse(customerId, items.size(), items);
    }

    /** R-HIST-03: mặc định 20, kẹp trần ở 100. */
    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
