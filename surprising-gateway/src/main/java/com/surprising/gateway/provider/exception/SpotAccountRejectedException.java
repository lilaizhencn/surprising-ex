package com.surprising.gateway.provider.exception;

/** SPOT命令被明确拒绝；提现业务可据此记录拒绝，不能按未知结果重试扣款。 */
public class SpotAccountRejectedException extends IllegalStateException {
    public SpotAccountRejectedException(String message) {
        super(message);
    }
}
