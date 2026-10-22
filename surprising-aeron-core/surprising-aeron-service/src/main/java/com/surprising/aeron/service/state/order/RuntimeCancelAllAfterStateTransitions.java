package com.surprising.aeron.service.state.order;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;

/** 撤单倒计时的账户状态机。SET 设置或禁用，CLAIM 到期后认领，COMPLETE 记录撤单数量，RETRY 重新开放；这里只写计时状态和版本，不代替后续真实撤单。 构造约定：只保留原计时状态更新的静态入口，不创建系统时间驱动线程。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeCancelAllAfterStateTransitions {


    /**
     * 核对用户后按用户及币对范围读旧计时器；SET 新建或重置并清统计，其他动作须匹配原版本及到期或认领状态，成功写入下一状态并推进命令版本。
     * @param runtime 提供原计时器与版本写入入口的所属运行时
     * @param userId 命令头所指定的计时器用户，必须与载荷一致
     * @param command 复制命令中的动作、范围、业务时间、预期版本和撤单统计
     */
    public static void update(TradingRuntimeState runtime, long userId, CoreCancelAllAfterCommand command) {
        if (runtime == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime cancel-all-after update");
        }
        runtime.assertOwner();
        if (command.userId() != userId) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_OWNER_MISMATCH",
                    "cancel-all-after timer belongs to another user");
        }
        // 当前用户加币对范围形成的计时器业务键。
        CoreCancelAllAfterKey key = new CoreCancelAllAfterKey(userId, command.symbolScope());
        // 该范围当前保存的计时状态，SET 可从无记录开始。
        CoreCancelAllAfterState current = runtime.cancelAllAfterTimer(key);
        // 本次动作合法完成后要写回的新计时状态。
        CoreCancelAllAfterState next;
        switch (command.action()) {
            case SET -> {
                // SET 的目标状态，倒计时为 0 则禁用，否则激活且触发时间须晚于命令业务时间。
                CoreCancelAllAfterStatus status = command.countdownMillis() == 0
                        ? CoreCancelAllAfterStatus.DISABLED : CoreCancelAllAfterStatus.ACTIVE;
                if (status == CoreCancelAllAfterStatus.ACTIVE
                        && command.triggerAtEpochMillis() <= command.updatedAtEpochMillis()) {
                    throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_TRIGGER",
                            "active cancel-all-after timer must trigger in the future");
                }
                next = new CoreCancelAllAfterState(userId, command.symbolScope(), command.countdownMillis(), status,
                        status == CoreCancelAllAfterStatus.ACTIVE ? command.triggerAtEpochMillis() : 0,
                        command.updatedAtEpochMillis(), 0, 0,
                        current == null ? 1 : Math.incrementExact(current.revision()));
            }
            case CLAIM -> {
                requireRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.ACTIVE
                        || current.triggerAtEpochMillis() > command.updatedAtEpochMillis()) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_DUE", "timer is not due");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.TRIGGERING, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), current.canceledOrders(), current.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            case COMPLETE -> {
                requireRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.TRIGGERED, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), command.canceledOrders(), command.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            case RETRY -> {
                requireRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.ACTIVE, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), current.canceledOrders(), current.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            default -> throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_ACTION", "unsupported action");
        }
        runtime.requireRevisionCapacity();
        runtime.putCancelAllAfterTimer(key, next);
        runtime.incrementCommandRevision();
    }

    /**
     * CLAIM、COMPLETE 和 RETRY 要求计时器存在且命令预期版本等于当前版本；SET 使用自身的新建或重置规则。
     * @param current 按用户及范围查得的旧计时状态，没有记录时为 null
     * @param command 复制命令中的动作、范围、业务时间、预期版本和撤单统计
     */
    private static void requireRevision(CoreCancelAllAfterState current, CoreCancelAllAfterCommand command) {
        if (current == null) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_FOUND", "timer not found");
        }
        if (command.expectedRevision() != current.revision()) {
            throw new CoreStateRejectedException("STALE_CANCEL_ALL_AFTER_REVISION", "timer revision is stale");
        }
    }
}
