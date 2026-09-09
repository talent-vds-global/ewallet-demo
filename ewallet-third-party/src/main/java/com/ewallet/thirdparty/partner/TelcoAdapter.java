package com.ewallet.thirdparty.partner;

import com.ewallet.thirdparty.client.PartnerSimClient;
import com.ewallet.thirdparty.entity.PartnerConfig;
import org.springframework.stereotype.Component;

/** Nạp tiền điện thoại (F2 biến thể TELCO). accountRef là số thuê bao — R-TELCO-01. */
@Component
public class TelcoAdapter implements PartnerAdapter {

    private final PartnerSimClient client;

    public TelcoAdapter(PartnerSimClient client) {
        this.client = client;
    }

    @Override
    public String serviceType() {
        return PartnerConfig.TELCO;
    }

    @Override
    public String validate(ExecuteCommand c) {
        if (c.accountRef() == null || !c.accountRef().matches("\\d{10}")) {
            return "INVALID_PHONE_NUMBER";
        }
        return null;
    }

    @Override
    public PartnerSimClient.Result execute(ExecuteCommand c) {
        return client.execute(c.partnerCode(), c.orderId(), c.amount(), c.currency(),
                c.accountRef(), null);
    }
}
