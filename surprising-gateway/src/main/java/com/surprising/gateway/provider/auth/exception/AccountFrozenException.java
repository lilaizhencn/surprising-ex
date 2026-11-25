package com.surprising.gateway.provider.auth.exception;

import java.time.Instant;

/** 账户处于封禁/冻结状态时拒绝登录，携带封禁理由与封禁截止时间。 */
public final class AccountFrozenException extends IllegalStateException {

    private final String reason;
    private final Instant frozenUntil;

    public AccountFrozenException(String reason, Instant frozenUntil) {
        super("user is not active");
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        this.frozenUntil = frozenUntil;
    }

    public String reason() {
        return reason;
    }

    public Instant frozenUntil() {
        return frozenUntil;
    }
}
