package com.surprising.marketmaker.provider.exception;

/** 内存设置的乐观版本冲突；HTTP响应由Controller处理。 */
public final class MarketMakerSettingsConflictException extends RuntimeException {
    public MarketMakerSettingsConflictException(String message) { super(message); }
}
