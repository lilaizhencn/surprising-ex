package com.surprising.aeron.service.state.model;

import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;

/**
 * 一份倒计时撤单状态，保存适用范围、触发时刻、已撤数量和修订号；实际撤单由业务流程执行，这份记录用来恢复和展示进度。
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param symbolScope 定时撤单适用的币对范围标识，由调用方确定并与定时器键保持一致
 * @param countdownMillis 最近一次配置的撤单倒计时时长，单位为毫秒
 * @param status 定时撤单当前处于启用、关闭或触发后的哪种协议状态
 * @param triggerAtEpochMillis 倒计时应触发撤单的 Unix 毫秒时间戳，启用状态下必须非零
 * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
 * @param canceledOrders 这次定时撤单记录的普通订单撤销数量
 * @param canceledTriggerOrders 这次定时撤单记录的条件单撤销数量
 * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
 */
public record CoreCancelAllAfterState(
        long userId,
        String symbolScope,
        long countdownMillis,
        CoreCancelAllAfterStatus status,
        long triggerAtEpochMillis,
        long updatedAtEpochMillis,
        int canceledOrders,
        int canceledTriggerOrders,
        long revision) {

    /**
     * 复用对外视图的字段校验，再要求启用中的定时器必须有具体触发时刻。
     */
    public CoreCancelAllAfterState {
        new CoreCancelAllAfterView(userId, symbolScope, countdownMillis, status, triggerAtEpochMillis,
                updatedAtEpochMillis, canceledOrders, canceledTriggerOrders, revision);
        if (status == CoreCancelAllAfterStatus.ACTIVE && triggerAtEpochMillis == 0) {
            throw new IllegalArgumentException("active cancel-all-after timer requires trigger time");
        }
    }

    /**
     * 取用户和币对范围组成查找键，供定时器状态索引定位。
     */
    public CoreCancelAllAfterKey key() {
        return new CoreCancelAllAfterKey(userId, symbolScope);
    }

    /**
     * 转换成协议层只读视图，字段原样保留供查询或事件发布使用。
     */
    public CoreCancelAllAfterView view() {
        return new CoreCancelAllAfterView(userId, symbolScope, countdownMillis, status, triggerAtEpochMillis,
                updatedAtEpochMillis, canceledOrders, canceledTriggerOrders, revision);
    }
}
