package com.surprising.gateway.c2c.model;

/** 后台审核的线格式请求与原生版本命令；审批仍绑定原始正文。 */
public final class C2cAdminReviewModels {
    private C2cAdminReviewModels() { }

    public record MerchantRequest(String revision,String decision,String tier,String reason) { }
    public record PaymentRequest(String revision,String decision,boolean identityChecked,String reason) { }
    public record AdRequest(String revision,String action,String reason) { }
    public record AppealDecision(String decision,String reason) { }
    public record ReportDecision(String decision,String reason) { }
    public record MerchantDecision(long revision,String decision,String tier,String reason) { }
    public record PaymentDecision(long revision,String decision,boolean identityChecked,String reason) { }
    public record AdDecision(long revision,String action,String reason) { }
}
