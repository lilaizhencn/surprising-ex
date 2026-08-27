package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class RuntimeCommitLedger {
    private final ProductLine productLine;
    private final Map<UserAssetKey, RuntimeMutationDelta.BalanceValue> balances = new HashMap<>();
    private final Map<Integer, RuntimeMutationDelta.AssetLedger> treasury = new HashMap<>();
    private long sequence;
    private long revision;

    public RuntimeCommitLedger(TradingCoreState initial, RuntimeIdentityRegistry identities) {
        if (initial == null || identities == null) {
            throw new IllegalArgumentException("initial commit state and identities are required");
        }
        productLine = initial.productLine();
        restore(initial, identities);
    }

    public void restore(TradingCoreState initial, RuntimeIdentityRegistry identities) {
        if (initial == null || identities == null || initial.productLine() != productLine) {
            throw new IllegalArgumentException("invalid commit ledger restore state");
        }
        revision = initial.revision();
        balances.clear();
        treasury.clear();
        initial.users().forEach((userId, user) -> user.balances().forEach((asset, balance) ->
                balances.put(new UserAssetKey(userId, identities.assetId(asset)),
                        new RuntimeMutationDelta.BalanceValue(
                                balance.availableUnits(), balance.lockedUnits(), 0))));
        CoreTreasuryState state = initial.treasuryState();
        java.util.TreeSet<String> assets = new java.util.TreeSet<>();
        assets.addAll(state.feeBalances().keySet());
        assets.addAll(state.insuranceBalances().keySet());
        assets.addAll(state.insuranceDeficits().keySet());
        assets.addAll(state.liquidationFeeBalances().keySet());
        assets.addAll(state.fundingResidualBalances().keySet());
        assets.addAll(state.roundingResidualBalances().keySet());
        assets.addAll(state.clearingPnlBalances().keySet());
        for (String asset : assets) {
            treasury.put(identities.assetId(asset), new RuntimeMutationDelta.AssetLedger(
                    state.feeBalances().getOrDefault(asset, 0L),
                    state.insuranceBalances().getOrDefault(asset, 0L),
                    state.insuranceDeficits().getOrDefault(asset, 0L),
                    state.liquidationFeeBalances().getOrDefault(asset, 0L),
                    state.fundingResidualBalances().getOrDefault(asset, 0L),
                    state.roundingResidualBalances().getOrDefault(asset, 0L),
                    state.clearingPnlBalances().getOrDefault(asset, 0L)));
        }
    }

    public RuntimeCommitEntry capture(long sequence, RuntimeMutationDelta mutation,
                                      RuntimeIdentityRegistry identities, TradingCoreState previous) {
        if (sequence != Math.incrementExact(this.sequence) || mutation == null || identities == null
                || previous == null || mutation.productLine() != productLine
                || previous.productLine() != productLine || previous.revision() < revision) {
            throw new IllegalArgumentException("invalid runtime commit capture");
        }
        RuntimeFundsDelta fundsDelta = fundsDelta(mutation);
        return new RuntimeCommitEntry(sequence, mutation, identities, previous.revision(), fundsDelta);
    }

    public void commit(RuntimeCommitEntry entry) {
        if (entry == null || entry.productLine() != productLine
                || entry.sequence() != Math.incrementExact(sequence) || entry.revision() < revision) {
            throw new IllegalStateException("runtime commit sequence gap");
        }
        sequence = entry.sequence();
        revision = entry.revision();
    }

    private RuntimeFundsDelta fundsDelta(RuntimeMutationDelta mutation) {
        ArrayList<RuntimeFundsDelta.Posting> postings = new ArrayList<>();
        mutation.users().currentValues().forEach((userId, user) ->
                user.balances().changedKeys().forEach(assetId -> {
                    UserAssetKey key = new UserAssetKey(userId, assetId);
                    RuntimeMutationDelta.BalanceValue before = balances.get(key);
                    RuntimeMutationDelta.BalanceValue after = user.balances().currentValues().get(assetId);
                    add(postings, assetId, FundsPosting.OwnerKind.USER, userId,
                            FundsPosting.Subledger.AVAILABLE,
                            Math.subtractExact(units(after, true), units(before, true)));
                    add(postings, assetId, FundsPosting.OwnerKind.USER, userId,
                            FundsPosting.Subledger.LOCKED,
                            Math.subtractExact(units(after, false), units(before, false)));
                    if (after == null) balances.remove(key); else balances.put(key, after);
                }));
        mutation.treasury().assets().changedKeys().forEach(assetId -> {
            RuntimeMutationDelta.AssetLedger before = treasury.get(assetId);
            RuntimeMutationDelta.AssetLedger after = mutation.treasury().assets().currentValues().get(assetId);
            addTreasury(postings, assetId, FundsPosting.Subledger.FEE,
                    Math.subtractExact(fee(after), fee(before)));
            addTreasury(postings, assetId, FundsPosting.Subledger.INSURANCE,
                    Math.subtractExact(insurance(after), insurance(before)));
            addTreasury(postings, assetId, FundsPosting.Subledger.DEFICIT,
                    Math.negateExact(Math.subtractExact(deficit(after), deficit(before))));
            addTreasury(postings, assetId, FundsPosting.Subledger.LIQUIDATION_FEE,
                    Math.subtractExact(liquidationFee(after), liquidationFee(before)));
            addTreasury(postings, assetId, FundsPosting.Subledger.FUNDING_RESIDUAL,
                    Math.subtractExact(fundingResidual(after), fundingResidual(before)));
            addTreasury(postings, assetId, FundsPosting.Subledger.ROUNDING_RESIDUAL,
                    Math.subtractExact(roundingResidual(after), roundingResidual(before)));
            addTreasury(postings, assetId, FundsPosting.Subledger.CLEARING_PNL,
                    Math.subtractExact(clearingPnl(after), clearingPnl(before)));
            if (after == null) treasury.remove(assetId); else treasury.put(assetId, after);
        });
        return new RuntimeFundsDelta(postings);
    }

    private static long units(RuntimeMutationDelta.BalanceValue value, boolean available) {
        return value == null ? 0 : available ? value.availableUnits() : value.lockedUnits();
    }

    private static long fee(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.fee(); }
    private static long insurance(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.insurance(); }
    private static long deficit(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.deficit(); }
    private static long liquidationFee(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.liquidationFee(); }
    private static long fundingResidual(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.fundingResidual(); }
    private static long roundingResidual(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.roundingResidual(); }
    private static long clearingPnl(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.clearingPnl(); }

    private static void addTreasury(ArrayList<RuntimeFundsDelta.Posting> postings, int assetId,
                                    FundsPosting.Subledger subledger, long units) {
        add(postings, assetId, FundsPosting.OwnerKind.TREASURY, 0, subledger, units);
    }

    private static void add(ArrayList<RuntimeFundsDelta.Posting> postings, int assetId,
                            FundsPosting.OwnerKind ownerKind, long ownerId,
                            FundsPosting.Subledger subledger, long units) {
        if (units != 0) postings.add(new RuntimeFundsDelta.Posting(assetId, ownerKind, ownerId, subledger, units));
    }

    private record UserAssetKey(long userId, int assetId) {
    }
}
