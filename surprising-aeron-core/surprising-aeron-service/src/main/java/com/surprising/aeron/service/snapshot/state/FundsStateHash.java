package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.account.UserAccountSnapshot;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 只在快照、恢复和明确审计边界计算的全量资金哈希。覆盖产品线、每个用户的资产可用/冻结额及七类财库金额，按计数、加和与异或聚合使 Map 遍历顺序不影响结果。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FundsStateHash {
    /** 条目哈希的固定区分标记，避免把原键值直接当作最终分组哈希。 */
    private static final long HASH_TAG = 0xd6e8feb86659fd93L;

    /** 逐用户汇总资产余额哈希，再加入手续费、保险、亏空、强平费、资金费剩余、舍入剩余和清算盈亏各表。 */
    public static long compute(TradingCoreState state) {
        if (state == null) throw new IllegalArgumentException("funds state is required");
        Aggregate users = new Aggregate();
        Aggregate balances = new Aggregate();
        for (Map.Entry<Long, UserAccountSnapshot> userEntry : state.users().entrySet()) {
            balances.clear();
            long userId = userEntry.getKey();
            UserAccountSnapshot user = userEntry.getValue();
            for (var balanceEntry : user.balances().entrySet()) {
                String asset = balanceEntry.getKey();
                var balance = balanceEntry.getValue();
                long value = CoreStateHash.mix(CoreStateHash.start(), asset);
                value = CoreStateHash.mix(value, balance.availableUnits());
                value = CoreStateHash.mix(value, balance.lockedUnits());
                balances.add(entryHash(asset, value));
            }
            long userHash = CoreStateHash.mix(CoreStateHash.start(), userId);
            users.add(entryHash(userId, mix(userHash, "balances", balances)));
        }

        CoreTreasuryState treasury = state.treasuryState();
        long hash = CoreStateHash.mix(CoreStateHash.start(), com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(state.productLine()));
        hash = mix(hash, "users", users);
        hash = mix(hash, "fee", aggregate(treasury.feeBalances(), balances));
        hash = mix(hash, "insurance", aggregate(treasury.insuranceBalances(), balances));
        hash = mix(hash, "deficit", aggregate(treasury.insuranceDeficits(), balances));
        hash = mix(hash, "liquidationFee", aggregate(treasury.liquidationFeeBalances(), balances));
        hash = mix(hash, "fundingResidual", aggregate(treasury.fundingResidualBalances(), balances));
        hash = mix(hash, "roundingResidual", aggregate(treasury.roundingResidualBalances(), balances));
        return mix(hash, "clearingPnl", aggregate(treasury.clearingPnlBalances(), balances));
    }

    private static Aggregate aggregate(Map<String, Long> values, Aggregate aggregate) {
        aggregate.clear();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            aggregate.add(entryHash(entry.getKey(), entry.getValue()));
        }
        return aggregate;
    }

    private static long entryHash(String key, long value) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, key);
        return CoreStateHash.mix(hash, value);
    }

    private static long entryHash(long key, long value) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, key);
        return CoreStateHash.mix(hash, value);
    }

    /** 先混入当前分组名称，再混入条目数、哈希加和和异或，区分业务分组并避免依赖 Map 遍历顺序。 */
    private static long mix(long hash, String name, Aggregate aggregate) {
        hash = CoreStateHash.mix(hash, name);
        hash = CoreStateHash.mix(hash, aggregate.count);
        hash = CoreStateHash.mix(hash, aggregate.sum);
        return CoreStateHash.mix(hash, aggregate.xor);
    }

    /** 一次计算内部使用的顺序无关哈希累加器。 */
    private static final class Aggregate {
        private long count;
        private long sum;
        private long xor;

        private void clear() {
            count = 0;
            sum = 0;
            xor = 0;
        }

        private void add(long value) {
            count++;
            sum += value;
            xor ^= value;
        }
    }
}
