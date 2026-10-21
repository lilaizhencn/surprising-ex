package com.surprising.instrument.provider.exception;

/** 原生合约查询或状态冲突；HTTP状态由Controller选择。 */
public final class InstrumentOperationException extends RuntimeException {
    public enum Reason { NOT_FOUND, CONFLICT }
    private final Reason reason;
    public InstrumentOperationException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
