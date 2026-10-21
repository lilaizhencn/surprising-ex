package com.surprising.gateway.c2c.exception;

/** C2C原生业务失败；不依赖HTTP，响应映射和脱敏由Controller承担。 */
public final class C2cProblem extends IllegalStateException {
    public enum Reason { AUTHENTICATION_REQUIRED, ACCESS_DENIED, NOT_FOUND, RATE_LIMITED, CONFLICT, UNAVAILABLE }
    private final Reason reason;
    private final String code;
    public C2cProblem(Reason reason, String code, String message) {
        super(message); this.reason = reason; this.code = code;
    }
    public Reason reason() { return reason; }
    public String code() { return code; }
    public boolean retryable() { return reason == Reason.UNAVAILABLE; }
    public static C2cProblem forbidden(String message) { return new C2cProblem(Reason.ACCESS_DENIED, "C2C_FORBIDDEN", message); }
    public static C2cProblem unavailable(String message) { return new C2cProblem(Reason.UNAVAILABLE, "C2C_UNAVAILABLE", message); }
    public static C2cProblem conflict(String message) { return new C2cProblem(Reason.CONFLICT, "C2C_CONFLICT", message); }
}
