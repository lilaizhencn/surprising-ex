package com.surprising.aeron.service.replay;

import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 独立 Archive 回放中的只读权益物化结果，不属于在线 Matcher 或 Account Lane。本类只校验时间参数，不能自行证明前缀完整。 */
public record CommittedPortfolioSnapshot(ProductLine productLine, long asOfEpochMillis,
                                         long sourceTimestamp, Map<Long, UserEquity> users) {
    public CommittedPortfolioSnapshot {
        if (productLine == null || asOfEpochMillis <= 0 || sourceTimestamp < 0 || sourceTimestamp > asOfEpochMillis)
            throw new IllegalArgumentException("invalid committed portfolio time");
        users = Map.copyOf(users);
    }

    /** 从当前已提交状态按用户聚合可用加冻结余额，用 BigInteger 保留合计精度，再按产品线计算各非零持仓的 long 权益增量。缺少品种/标记、价格过期/来自未来或单持仓算术溢出时标为不完整。仍可能保留部分数值，调用方必须看 complete。 */
    static CommittedPortfolioSnapshot capture(TradingCoreState state, long sourceTimestamp,
                                              long asOf, long maximumMarkAgeMillis, Set<Long> pendingUsers) {
        if (state == null || pendingUsers == null || sourceTimestamp < 0 || asOf <= 0
                || sourceTimestamp > asOf || maximumMarkAgeMillis <= 0)
            throw new IllegalArgumentException("invalid committed portfolio boundary");
        var users = new TreeMap<Long, UserEquity>();
        state.users().forEach((userId, user) -> {
            var units = new TreeMap<String, BigInteger>();
            user.balances().forEach((asset, balance) -> units.put(asset,
                    BigInteger.valueOf(balance.availableUnits()).add(BigInteger.valueOf(balance.lockedUnits()))));
            boolean complete = !pendingUsers.contains(userId);
            for (var position : user.positions().values()) {
                if (position.signedQuantitySteps() == 0) continue;
                var instrument = state.instruments().get(position.instrumentId());
                var mark = state.riskState().markPrices().get(position.instrumentId());
                if (instrument == null || mark == null || mark.generatedAtEpochMillis() > asOf
                        || asOf - mark.generatedAtEpochMillis() > maximumMarkAgeMillis) {
                    complete = false;
                    continue;
                }
                try {
                    long equityDelta = instrument.contractType().isOption()
                            ? OptionContractMath.optionMarketValueUnits(instrument,
                                    position.signedQuantitySteps(), mark.markPriceTicks())
                            : CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                                    position.entryPriceTicks(), mark.markPriceTicks());
                    units.merge(position.marginAsset(), BigInteger.valueOf(equityDelta), BigInteger::add);
                } catch (ArithmeticException unrepresentablePosition) {
                    // 这类单持仓算术溢出只使该用户权益不可用。
                    // 保留的部分值不能冒充完整结果。
                    complete = false;
                }
            }
            var exact = new TreeMap<String, String>();
            units.forEach((asset, amount) -> exact.put(asset, amount.toString()));
            users.put(userId, new UserEquity(complete, exact));
        });
        // 划转未完成时，不能把缺少账户当作零权益。
        pendingUsers.forEach(userId -> users.putIfAbsent(userId, new UserEquity(false, Map.of())));
        return new CommittedPortfolioSnapshot(state.productLine(), asOf, sourceTimestamp, users);
    }

    /** 一个用户的完整性标记及单资产金额文本。Map 固定不可修改，完整性为 false 时可能含余额或部分持仓值，不能据此发布完整净权益。 */
    public record UserEquity(boolean complete, Map<String, String> netAssetUnits) {
        /** complete 为 false 时这些值不能解释为完整净权益。 */
        public UserEquity { netAssetUnits = Map.copyOf(netAssetUnits); }
    }
}
