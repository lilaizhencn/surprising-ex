package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 只在快照、恢复和明确审计边界计算的全量资金哈希。覆盖产品线、每个用户的资产可用/冻结额及七类财库金额，按计数、加和与异或聚合使 Map 遍历顺序不影响结果；不包含持仓、预留明细或结算进度，也不等于资金守恒证明。 构造约定：工具类不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FundsStateHash {
    /** 条目哈希的固定区分标记，避免把原键值直接当作最终分组哈希。 */
    private static final long HASH_TAG = 0xd6e8feb86659fd93L;

    /**
     * 逐用户汇总资产余额哈希，再加入手续费、保险、亏空、强平费、资金费剩余、舍入剩余和清算盈亏各表；每次复用临时聚合器，最后返回当前产品的完整资金表示哈希。
     * @param state 当前快照或审计边界已经稳定的交易业务值
     */
    public static long compute(TradingCoreState state) {
        if (state == null) throw new IllegalArgumentException("funds state is required");
        // 所有用户余额表示的顺序无关聚合结果。
        Aggregate users = new Aggregate();
        // 逐用户或逐财库表清空并复用的余额条目聚合器。
        Aggregate balances = new Aggregate();
        for (Map.Entry<Long, CoreUserState> /* 当前用户编号及其只读状态。 */ userEntry : state.users().entrySet()) {
            balances.clear();
            // 正在加入哈希的用户编号。
            long userId = userEntry.getKey();
            // 当前用户状态，只读取其资产余额表。
            CoreUserState user = userEntry.getValue();
            for (var /* 当前用户的一条资产余额。 */ balanceEntry : user.balances().entrySet()) {
                // 当前余额的业务资产名称。
                String asset = balanceEntry.getKey();
                // 当前资产的可用额及冻结额。
                var balance = balanceEntry.getValue();
                // 当前资产名、可用额和冻结额逐项混入后的内容哈希。
                long value = CoreStateHash.mix(CoreStateHash.start(), asset);
                value = CoreStateHash.mix(value, balance.availableUnits());
                value = CoreStateHash.mix(value, balance.lockedUnits());
                balances.add(entryHash(asset, value));
            }
            // 已混入用户编号，接着要混入其余额分组的哈希。
            long userHash = CoreStateHash.mix(CoreStateHash.start(), userId);
            users.add(entryHash(userId, mix(userHash, "balances", balances)));
        }

        // 需要依次加入七类资产金额表的财库业务值。
        CoreTreasuryState treasury = state.treasuryState();
        // 外层最终资金哈希，或当前键值条目内部的累积哈希。
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

    /**
     * 清空调用方提供的临时聚合器，把每个资产名称和金额合成条目哈希后累积，并返回同一个聚合器。
     * @param values 某个财库子账按资产名保存的金额表
     * @param aggregate 当前分组可清空复用的哈希聚合器
     */
    private static Aggregate aggregate(Map<String, Long> values, Aggregate aggregate) {
        aggregate.clear();
        for (Map.Entry<String, Long> /* 当前财库表中的资产名称和金额条目。 */ entry : values.entrySet()) {
            aggregate.add(entryHash(entry.getKey(), entry.getValue()));
        }
        return aggregate;
    }

    /**
     * 把固定条目标记、字符串键和数值依次混入，得到资产条目或带名称数据的哈希。
     * @param key 当前条目的业务字符串键
     * @param value 当前条目要混入的金额、内容哈希或预先计算的条目哈希
     */
    private static long entryHash(String key, long value) {
        // 外层最终资金哈希，或当前键值条目内部的累积哈希。
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, key);
        return CoreStateHash.mix(hash, value);
    }

    /**
     * 把固定条目标记、数值键和数值依次混入，得到用户等数值身份条目的哈希。
     * @param key 当前条目的数值身份键
     * @param value 当前条目要混入的金额、内容哈希或预先计算的条目哈希
     */
    private static long entryHash(long key, long value) {
        // 外层最终资金哈希，或当前键值条目内部的累积哈希。
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, key);
        return CoreStateHash.mix(hash, value);
    }

    /**
     * 先混入当前分组名称，再混入条目数、哈希加和和异或，区分业务分组并避免依赖 Map 遍历顺序。
     * @param hash 加入当前分组之前的外层哈希
     * @param name 当前余额或财库分组的固定名称
     * @param aggregate 当前分组可清空复用的哈希聚合器
     */
    private static long mix(long hash, String name, Aggregate aggregate) {
        hash = CoreStateHash.mix(hash, name);
        hash = CoreStateHash.mix(hash, aggregate.count);
        hash = CoreStateHash.mix(hash, aggregate.sum);
        return CoreStateHash.mix(hash, aggregate.xor);
    }

    /**
     * 一次计算内部使用的顺序无关哈希累加器。只保存计数、加和及异或，不持有用户或金额 Map，不是另一份资金权威数据。
     */
    private static final class Aggregate {
        /** 当前分组已加入的条目数，不执行键去重。 */
        private long count;
        /** 当前分组各条目哈希的六十四位回绕加和。 */
        private long sum;
        /** 当前分组各条目哈希的逐位异或。 */
        private long xor;

        /**
         * 把计数、加和和异或归零，供下一个用户或财库表复用。
         */
        private void clear() {
            count = 0;
            sum = 0;
            xor = 0;
        }

        /**
         * 累计一个条目的数量、哈希加和及异或；使用普通 long 回绕运算，不是财务金额精确加法。
         * @param value 当前条目要混入的金额、内容哈希或预先计算的条目哈希
         */
        private void add(long value) {
            count++;
            sum += value;
            xor ^= value;
        }
    }
}
