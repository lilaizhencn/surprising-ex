package com.surprising.aeron.service.state;

import java.util.Map;

/** Full funds hash calculated only at snapshot, recovery and explicit audit boundaries. */
public final class FundsStateHash {
    private static final long HASH_TAG = 0xd6e8feb86659fd93L;

    private FundsStateHash() { }

    public static long compute(TradingCoreState state) {
        if (state == null) throw new IllegalArgumentException("funds state is required");
        Aggregate users = new Aggregate();
        state.users().forEach((userId, user) -> {
            Aggregate balances = new Aggregate();
            user.balances().forEach((asset, balance) -> {
                long value = CoreStateHash.mix(CoreStateHash.start(), asset);
                value = CoreStateHash.mix(value, balance.availableUnits());
                value = CoreStateHash.mix(value, balance.lockedUnits());
                balances.add(entryHash(asset, value));
            });
            long userHash = CoreStateHash.mix(CoreStateHash.start(), userId.longValue());
            users.add(entryHash(userId.longValue(), mix(userHash, "balances", balances)));
        });

        CoreTreasuryState treasury = state.treasuryState();
        long hash = CoreStateHash.mix(CoreStateHash.start(), state.productLine().ordinal());
        hash = mix(hash, "users", users);
        hash = mix(hash, "fee", aggregate(treasury.feeBalances()));
        hash = mix(hash, "insurance", aggregate(treasury.insuranceBalances()));
        hash = mix(hash, "deficit", aggregate(treasury.insuranceDeficits()));
        hash = mix(hash, "liquidationFee", aggregate(treasury.liquidationFeeBalances()));
        hash = mix(hash, "fundingResidual", aggregate(treasury.fundingResidualBalances()));
        hash = mix(hash, "roundingResidual", aggregate(treasury.roundingResidualBalances()));
        return mix(hash, "clearingPnl", aggregate(treasury.clearingPnlBalances()));
    }

    private static Aggregate aggregate(Map<String, Long> values) {
        Aggregate aggregate = new Aggregate();
        values.forEach((key, value) -> aggregate.add(entryHash(key, value)));
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

        private void add(long value) {
            count++;
            sum += value;
            xor ^= value;
        }
    }
}
