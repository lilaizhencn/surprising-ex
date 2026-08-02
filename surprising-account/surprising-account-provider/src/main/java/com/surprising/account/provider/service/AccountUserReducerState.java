package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        requireUniquePositive(settledTradeIds, "成交幂等索引");
        requireUniquePositive(settledFundingPaymentIds, "资金费幂等索引");
        Set<Long> orderIds = new HashSet<>();
        for (Reservation reservation : reservations) {
            if (!orderIds.add(reservation.orderId())) {
                throw new IllegalArgumentException("账户预占不能重复: " + reservation.orderId());
            }
        }
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
        String symbol,
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
            symbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(java.util.Locale.ROOT);
            asset = asset.trim().toUpperCase(java.util.Locale.ROOT);
        }

        public Reservation(long orderId,
                           String symbol,
                           AccountType accountType,
                           String asset,
                           long reservedUnits,
                           long releasedUnits,
                           long orderQuantitySteps) {
            this(orderId, symbol, accountType, asset, reservedUnits, releasedUnits, 0L, orderQuantitySteps);
        }

        /** 兼容已存在的本地状态快照；新命令必须携带交易对。 */
        public Reservation(long orderId,
                           AccountType accountType,
                           String asset,
                           long reservedUnits,
                           long releasedUnits,
                           long orderQuantitySteps) {
            this(orderId, null, accountType, asset, reservedUnits, releasedUnits, 0L, orderQuantitySteps);
        }
    }

    /** 本地状态中的结算索引必须是正数且唯一，避免重启后重复扣减或重复入账。 */
    private static void requireUniquePositive(List<Long> values, String field) {
        Set<Long> unique = new HashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0L || !unique.add(value)) {
                throw new IllegalArgumentException(field + "无效");
            }
        }
    }
}
