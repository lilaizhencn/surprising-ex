package com.surprising.realtime.provider.option.exception;

/** 期权只读视图的业务失败；HTTP状态由Controller决定。 */
public final class OptionQuoteException extends RuntimeException {
    public enum Reason { INSTRUMENT_NOT_FOUND, REFERENCE_UNAVAILABLE }
    private final Reason reason;

    private OptionQuoteException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static OptionQuoteException notFound() {
        return new OptionQuoteException(Reason.INSTRUMENT_NOT_FOUND, "option instrument not found");
    }
    public static OptionQuoteException unavailable() {
        return new OptionQuoteException(Reason.REFERENCE_UNAVAILABLE, "OPTION_REFERENCE_UNAVAILABLE");
    }
}
