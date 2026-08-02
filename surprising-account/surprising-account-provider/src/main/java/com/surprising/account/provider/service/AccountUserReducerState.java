package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import java.util.List;

/** 持久化在用户分区状态库中的账户快照、预占明细和结算幂等索引。 */
public record AccountUserReducerState(
        PerpetualAccountStateUpdatedEvent snapshot,
        List<Reservation> reservations,
        List<Long> settledTradeIds,
        List<Long> settledFundingPaymentIds) {

    public AccountUserReducerState {
        if (snapshot == null || reservations == null) {
            throw new IllegalArgumentException("账户 reducer 状态不能为空");
        }
        reservations = List.copyOf(reservations);
        settledTradeIds = settledTradeIds == null ? List.of() : List.copyOf(settledTradeIds);
        settledFundingPaymentIds = settledFundingPaymentIds == null ? List.of() : List.copyOf(settledFundingPaymentIds);
    }

    public AccountUserReducerState(PerpetualAccountStateUpdatedEvent snapshot,
                                   List<Reservation> reservations) {
        this(snapshot, reservations, List.of(), List.of());
    }

    public AccountUserReducerState(PerpetualAccountStateUpdatedEvent snapshot,
                                   List<Reservation> reservations,
                                   List<Long> settledTradeIds) {
        this(snapshot, reservations, settledTradeIds, List.of());
    }

    public record Reservation(
            long orderId,
            AccountType accountType,
            String asset,
            long reservedUnits,
            long releasedUnits,
            long consumedUnits,
            long orderQuantitySteps) {

        public Reservation {
            if (orderId <= 0L || accountType == null || asset == null || asset.isBlank()
                    || reservedUnits <= 0L || releasedUnits < 0L || releasedUnits > reservedUnits
                    || consumedUnits < 0L
                    || Math.addExact(releasedUnits, consumedUnits) > reservedUnits
                    || orderQuantitySteps <= 0L) {
                throw new IllegalArgumentException("账户预占状态无效");
            }
            asset = asset.trim().toUpperCase(java.util.Locale.ROOT);
        }

        public Reservation(long orderId,
                           AccountType accountType,
                           String asset,
                           long reservedUnits,
                           long releasedUnits,
                           long orderQuantitySteps) {
            this(orderId, accountType, asset, reservedUnits, releasedUnits, 0L, orderQuantitySteps);
        }
    }
}
