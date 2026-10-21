package com.surprising.gateway.provider.exception;

/** 安全操作尚未通过所需验证；入口将其映射为验证前置条件不足。 */
public class SecurityVerificationRequiredException extends RuntimeException {
    public SecurityVerificationRequiredException() {
        super("security verification is required or invalid");
    }
}
