package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.math.OptionContractMath;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 独立 Archive 回放进程的权益物化边界，不属于在线撮合或 Account Lane 状态。
 * 只保存每币种净权益最小单位；期权使用市值，其他衍生品使用未实现盈亏。
 * 调用者必须先证明回放前缀包含目标时刻前的全部命令，才能持久化日初或分钟切点。
 */
public record CommittedPortfolioSnapshot(ProductLine productLine, long asOfEpochMillis,
                                         long sourceTimestamp, Map<Long, UserEquity> users) {
    public CommittedPortfolioSnapshot {
        if (productLine == null || asOfEpochMillis <= 0 || sourceTimestamp < 0 || sourceTimestamp > asOfEpochMillis)
            throw new IllegalArgumentException("invalid committed portfolio time");
        users = Map.copyOf(users);
    }

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
                    // A read model must report this user unavailable, never publish a
                    // partial balance as full equity or stop authoritative trade replay.
                    complete = false;
                }
            }
            var exact = new TreeMap<String, String>();
            units.forEach((asset, amount) -> exact.put(asset, amount.toString()));
            users.put(userId, new UserEquity(complete, exact));
        });
        // A recipient may not yet have any account in the sender's product.
        // Its absence cannot certify zero while that transfer is unresolved.
        pendingUsers.forEach(userId -> users.putIfAbsent(userId, new UserEquity(false, Map.of())));
        return new CommittedPortfolioSnapshot(state.productLine(), asOf, sourceTimestamp, users);
    }

    /** JSON uses strings so units above JavaScript's safe integer range survive transport. */
    public record UserEquity(boolean complete, Map<String, String> netAssetUnits) {
        public UserEquity { netAssetUnits = Map.copyOf(netAssetUnits); }
    }
}
