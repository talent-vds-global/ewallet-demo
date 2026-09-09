package com.ewallet.order.web;

import com.ewallet.order.dto.OrderDtos;
import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.entity.PaymentOrder;
import java.util.List;
import org.springframework.stereotype.Component;

/** Đổi entity sang DTO trả ra ngoài. */
@Component
public class OrderMapper {

    public OrderDtos.OrderResponse toResponse(PaymentOrder o, List<OrderStep> steps) {
        List<OrderDtos.StepView> stepViews = steps == null ? List.of() : steps.stream()
                .map(s -> new OrderDtos.StepView(
                        s.getStepName(), s.getStepStatus(), s.getDetail(),
                        s.getAttempt(), s.getDurationMs(), String.valueOf(s.getCreatedAt())))
                .toList();

        return new OrderDtos.OrderResponse(
                o.getId().toString(),
                o.getStatus(),
                o.getReasonCode(),
                o.getTxnId() == null ? null : o.getTxnId().toString(),
                o.getCustomerId(),
                o.getPaymentType(),
                o.getAmount(),
                o.getFee(),
                o.getCurrency(),
                o.getAmountVnd(),
                o.getPartnerCode(),
                o.getPartnerRef(),
                o.getBillCode(),
                o.getDestCustomerId(),
                PaymentOrder.REFUNDED.equals(o.getStatus()),
                String.valueOf(o.getCreatedAt()),
                stepViews);
    }
}
