package com.ewallet.thirdparty.partner;

import com.ewallet.thirdparty.client.PartnerSimClient;

/**
 * Mỗi loại dịch vụ đối tác có cách kiểm tra và cách đóng gói lệnh khác nhau.
 * Chọn adapter theo {@code partner_config.service_type} (R-TOPUP-03).
 */
public interface PartnerAdapter {

    /** TOPUP | BILL | TELCO — khớp với partner_config.service_type. */
    String serviceType();

    /** Trả mã lý do nếu lệnh không hợp lệ với loại dịch vụ này, null nếu hợp lệ. */
    String validate(ExecuteCommand command);

    PartnerSimClient.Result execute(ExecuteCommand command);
}
