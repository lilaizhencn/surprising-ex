package com.surprising.aeron.service.matcher;

/**
 * 一项订单撤销操作的撮合结果，账户结算据此决定是否更新订单和释放剩余预留。
 * @param orderId 本项尝试撤销的正数订单编号
 * @param accepted 撮合侧是否接受该项撤单，不表示整个批次已经提交
 */
public record CoreCancellationResult(long orderId, boolean accepted) {

    /** 拒绝没有订单身份的撤单回执，防止结算阶段无法判断该处理哪一笔订单。 */
    public CoreCancellationResult {
        if (orderId <= 0) {
            throw new IllegalArgumentException("invalid cancellation result");
        }
    }
}
