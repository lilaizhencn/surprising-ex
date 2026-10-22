package com.surprising.gateway.provider.exception;

/** SPOT命令结果未确认，交由原意图/幂等引用继续对账。 */
public class SpotAccountUnknownException extends IllegalStateException {
    public SpotAccountUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
