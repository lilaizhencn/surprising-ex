package com.surprising.gateway.provider.exception;

/** 划转估值/安全验证的原生失败；HTTP状态由网关协议入口映射。 */
public final class ProductTransferSecurityException extends RuntimeException {
    public enum Reason { VALUATION_UNAVAILABLE, VERIFICATION_REQUIRED }
    private final Reason reason;
    public ProductTransferSecurityException(Reason reason, String message) { this(reason, message, null); }
    public ProductTransferSecurityException(Reason reason, String message, Throwable cause) { super(message, cause); this.reason = reason; }
    public Reason reason() { return reason; }
}
