package com.ewallet.thirdparty.partner;

import com.ewallet.thirdparty.client.PartnerSimClient;
import com.ewallet.thirdparty.entity.PartnerConfig;
import org.springframework.stereotype.Component;

/** Thanh toán hoá đơn dịch vụ (F2). Bắt buộc có mã hoá đơn — R-BILL-01. */
@Component
public class BillAdapter implements PartnerAdapter {

    private final PartnerSimClient client;

    public BillAdapter(PartnerSimClient client) {
        this.client = client;
    }

    @Override
    public String serviceType() {
        return PartnerConfig.BILL;
    }

    @Override
    public String validate(ExecuteCommand c) {
        if (c.billCode() == null || c.billCode().isBlank()) {
            return "MISSING_BILL_CODE";
        }
        return null;
    }

    @Override
    public PartnerSimClient.Result execute(ExecuteCommand c) {
        return client.execute(c.partnerCode(), c.orderId(), c.amount(), c.currency(),
                null, c.billCode());
    }
}
