package com.surprising.aeron.service.replay;

import com.surprising.aeron.protocol.export.CommittedPortfolioSnapshot;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 独立 Archive 回放中的只读权益物化辅助。 */
public final class CommittedPortfolioSnapshotCapture {
    private CommittedPortfolioSnapshotCapture() {}

    public static CommittedPortfolioSnapshot capture(TradingCoreState state, long sourceTimestamp,
                                                     long asOf, long maximumMarkAgeMillis, Set<Long> pendingUsers) {
        if (state == null || pendingUsers == null || sourceTimestamp < 0 || asOf <= 0
                || sourceTimestamp > asOf || maximumMarkAgeMillis <= 0)
            throw new IllegalArgumentException("invalid committed portfolio boundary");
        var users = new TreeMap<Long, CommittedPortfolioSnapshot.UserEquity>();
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
                    complete = false;
                }
            }
            var exact = new TreeMap<String, String>();
            units.forEach((asset, amount) -> exact.put(asset, amount.toString()));
            users.put(userId, new CommittedPortfolioSnapshot.UserEquity(complete, exact));
        });
        pendingUsers.forEach(userId -> users.putIfAbsent(userId,
                new CommittedPortfolioSnapshot.UserEquity(false, Map.of())));
        return new CommittedPortfolioSnapshot(state.productLine(), asOf, sourceTimestamp, users);
    }
}
