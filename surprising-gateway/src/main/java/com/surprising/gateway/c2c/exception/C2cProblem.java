package com.surprising.gateway.c2c.exception;

import org.springframework.http.HttpStatus;

/** HTTP 业务边界使用明确状态码；不把数据库、密文或验证码异常返回给用户。 */
public final class C2cProblem extends IllegalStateException {
    final HttpStatus status;
    final String code;

    public C2cProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public boolean retryable() {return status.is5xxServerError();}

    public static C2cProblem forbidden(String message) {
        return new C2cProblem(HttpStatus.FORBIDDEN, "C2C_FORBIDDEN", message);
    }
    public static C2cProblem unavailable(String message) {
        return new C2cProblem(HttpStatus.SERVICE_UNAVAILABLE, "C2C_UNAVAILABLE", message);
    }
}
