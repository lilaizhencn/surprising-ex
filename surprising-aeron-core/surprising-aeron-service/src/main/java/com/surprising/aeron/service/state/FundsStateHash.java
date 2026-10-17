package com.surprising.aeron.service.state;

import java.util.Map;

/** Full funds hash calculated only at snapshot, recovery and explicit audit boundaries. */
public final class FundsStateHash {
    private static final long HASH_TAG = 0xd6e8feb86659fd93L;

    private FundsStateHash() { }

    public static long compute(TradingCoreState state) {
        if (state == null) throw new IllegalArgumentException("funds state is required");
        Aggregate users = new Aggregate();
        Aggregate balances = new Aggregate();
        for (Map.Entry<Long, CoreUserState> userEntry : state.users().entrySet()) {
            balances.clear();
            long userId = userEntry.getKey();
            CoreUserState user = userEntry.getValue();
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
        long hash = CoreStateHash.mix(CoreStateHash.start(), com.surprising.aeron.service.state.snapshot.SnapshotEnumCodes.encode(state.productLine()));
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

    private static long mix(long hash, String name, Aggregate aggregate) {
        hash = CoreStateHash.mix(hash, name);
        hash = CoreStateHash.mix(hash, aggregate.count);
        hash = CoreStateHash.mix(hash, aggregate.sum);
        return CoreStateHash.mix(hash, aggregate.xor);
    }

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
