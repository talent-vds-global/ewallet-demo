package com.ewallet.thirdparty.partner;

import com.ewallet.thirdparty.client.PartnerSimClient;
import com.ewallet.thirdparty.entity.PartnerConfig;
import org.springframework.stereotype.Component;

/**
 * Nạp tiền vào ví từ tài khoản/thẻ ở đối tác (F1).
 *
 * <p>LƯU Ý STAGE E: đường này cố ý KHÔNG có test — đó là lỗi có chủ đích #3.
 * Flow F1 vẫn chạy thật và sinh trace, nên platform phải phát hiện được
 * "có runtime nhưng coverage bằng 0". Không viết test cho lớp này.</p>
 */
@Component
public class TopupAdapter implements PartnerAdapter {

    private final PartnerSimClient client;

    public TopupAdapter(PartnerSimClient client) {
        this.client = client;
    }

    @Override
    public String serviceType() {
        return PartnerConfig.TOPUP;
    }

    @Override
    public String validate(ExecuteCommand c) {
        if (c.accountRef() == null || c.accountRef().isBlank()) {
            return "MISSING_ACCOUNT_REF";
        }
        if (c.billCode() != null && !c.billCode().isBlank()) {
            return "BILL_CODE_NOT_ALLOWED";
        }
        return null;
    }

    @Override
    public PartnerSimClient.Result execute(ExecuteCommand c) {
        return client.execute(c.partnerCode(), c.orderId(), c.amount(), c.currency(),
                c.accountRef(), null);
    }
}
