package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import java.util.List;

/** 持久化在用户分区状态库中的账户快照和订单资金预占明细。 */
public record AccountUserReducerState(
        PerpetualAccountStateUpdatedEvent snapshot,
        List<Reservation> reservations) {

    public AccountUserReducerState {
        if (snapshot == null || reservations == null) {
            throw new IllegalArgumentException("账户 reducer 状态不能为空");
        }
        reservations = List.copyOf(reservations);
    }

    public record Reservation(
            long orderId,
            AccountType accountType,
            String asset,
            long reservedUnits,
            long releasedUnits,
            long orderQuantitySteps) {

        public Reservation {
            if (orderId <= 0L || accountType == null || asset == null || asset.isBlank()
                    || reservedUnits <= 0L || releasedUnits < 0L || releasedUnits > reservedUnits
                    || orderQuantitySteps <= 0L) {
                throw new IllegalArgumentException("账户预占状态无效");
            }
            asset = asset.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }
}
