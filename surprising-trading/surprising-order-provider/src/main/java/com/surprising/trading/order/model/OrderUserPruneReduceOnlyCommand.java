package com.surprising.trading.order.model;

import com.surprising.account.api.model.PositionUpdatedEvent;

/** 持仓变化触发只减仓清理的命令载荷。 */
public record OrderUserPruneReduceOnlyCommand(PositionUpdatedEvent position, String reason) {
    public OrderUserPruneReduceOnlyCommand {
        if (position == null || position.userId() <= 0L) {
            throw new IllegalArgumentException("只减仓清理持仓载荷无效");
        }
        reason = reason == null || reason.isBlank() ? "REDUCE_ONLY_POSITION_REDUCED" : reason.trim();
    }
}
