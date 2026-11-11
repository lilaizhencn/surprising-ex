package com.surprising.aeron.exception;

import lombok.Getter;
import lombok.experimental.Accessors;
/** 表示当前账户、订单或产品状态不允许执行这条命令。它与撮合状态损坏等需要恢复的致命故障不同。 */
public final class CoreStateRejectedException extends RuntimeException {

    /** 供协议结果识别的业务错误码，不能只依赖可变化的错误文字。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String code;

    /** 同时保存机器可识别的错误码和人能读懂的原因，空错误码会被直接拒绝。 */
    public CoreStateRejectedException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("rejection code is required");
        }
        this.code = code;
    }

}
