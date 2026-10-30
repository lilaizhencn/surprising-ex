package com.surprising.gateway.provider.legal.model;

/** UI language does not establish residency, product eligibility or regulatory authorization. */
public enum LegalDocumentType {
    USER_AGREEMENT("user-agreement",true), PRIVACY_POLICY("privacy-policy",true),
    COOKIE_POLICY("cookies",false), TRADING_RISK("trading-risk",false),
    DERIVATIVES_RISK("derivatives-risk",false), FUNDS_AND_WITHDRAWALS("funds-and-withdrawals",false),
    FEES("fees",false), AML_KYC("identity-and-compliance",false),
    COMPLAINTS("complaints",false), SECURITY_NOTICE("security",false);
    private final String slug;
    private final boolean registrationRequired;
    LegalDocumentType(String slug,boolean registrationRequired) { this.slug=slug;this.registrationRequired=registrationRequired; }
    public String slug() { return slug; }
    public boolean registrationRequired() { return registrationRequired; }
}
