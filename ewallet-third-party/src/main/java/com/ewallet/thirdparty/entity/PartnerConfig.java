package com.ewallet.thirdparty.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cấu hình đối tác. Quyết định adapter nào xử lý giao dịch (R-TOPUP-03). */
@Entity
@Table(name = "partner_config")
public class PartnerConfig {

    public static final String TOPUP = "TOPUP";
    public static final String BILL = "BILL";
    public static final String TELCO = "TELCO";

    @Id
    @Column(name = "partner_code")
    private String partnerCode;

    @Column(name = "partner_name", nullable = false)
    private String partnerName;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(nullable = false)
    private boolean enabled;

    public String getPartnerCode() { return partnerCode; }
    public void setPartnerCode(String partnerCode) { this.partnerCode = partnerCode; }
    public String getPartnerName() { return partnerName; }
    public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
