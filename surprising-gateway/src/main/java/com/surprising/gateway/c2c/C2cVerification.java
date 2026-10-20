package com.surprising.gateway.c2c;

/** 所有 C2C 表单共用的安全验证输入；不进入请求指纹、审计正文或资金意图。 */
public record C2cVerification(String emailCode, String totpCode) {
    public C2cVerification {
        emailCode = emailCode == null ? "" : emailCode.trim();
        totpCode = totpCode == null ? "" : totpCode.trim();
        if (emailCode.length() > 12 || totpCode.length() > 12) throw new IllegalArgumentException("安全验证码格式无效");
    }
    static C2cVerification orEmpty(C2cVerification value) {
        return value == null ? new C2cVerification("", "") : value;
    }
}
