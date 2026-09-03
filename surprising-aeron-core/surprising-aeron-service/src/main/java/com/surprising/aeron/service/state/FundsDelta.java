package com.surprising.aeron.service.state;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FundsDelta {

    private static final int CANONICAL_VERSION = 1;
    private static final Comparator<FundsPosting> POSTING_ORDER = Comparator
            .comparing(FundsPosting::asset)
            .thenComparingInt(posting -> posting.ownerKind().wireCode())
            .thenComparingLong(FundsPosting::ownerId)
            .thenComparingInt(posting -> posting.subledger().wireCode());

    private final List<FundsPosting> postings;
    private final Map<String, Long> unitsByAsset;
    private final byte[] canonicalBytes;

    public FundsDelta(List<FundsPosting> source) {
        if (source == null) {
            throw new IllegalArgumentException("funds postings are required");
        }
        FundsPosting[] ordered = source.toArray(FundsPosting[]::new);
        for (FundsPosting posting : ordered) {
            if (posting == null) {
                throw new IllegalArgumentException("funds posting is required");
            }
        }
        java.util.Arrays.sort(ordered, POSTING_ORDER);
        ArrayList<FundsPosting> normalized = new ArrayList<>(ordered.length);
        HashMap<String, Long> totals = new HashMap<>();
        for (int index = 0; index < ordered.length;) {
            FundsPosting first = ordered[index++];
            long units = first.units();
            while (index < ordered.length && POSTING_ORDER.compare(first, ordered[index]) == 0) {
                units = Math.addExact(units, ordered[index++].units());
            }
            totals.putIfAbsent(first.asset(), 0L);
            if (units == 0) continue;
            normalized.add(new FundsPosting(first.asset(), first.ownerKind(),
                    first.ownerId(), first.subledger(), units));
            totals.put(first.asset(), Math.addExact(totals.get(first.asset()), units));
        }
        totals.forEach((asset, units) -> {
            if (units != 0) {
                throw new IllegalArgumentException("Funds delta is not conserved for asset " + asset);
            }
        });
        postings = normalized.isEmpty() ? List.of() : Collections.unmodifiableList(normalized);
        unitsByAsset = Collections.unmodifiableMap(totals);
        canonicalBytes = encodeCanonical(postings);
    }

    public List<FundsPosting> postings() {
        return postings;
    }

    public Map<String, Long> unitsByAsset() {
        return unitsByAsset;
    }

    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    public List<com.surprising.aeron.protocol.CoreFundsPostingView> views() {
        ArrayList<com.surprising.aeron.protocol.CoreFundsPostingView> result = new ArrayList<>(postings.size());
        for (FundsPosting posting : postings) {
            result.add(new com.surprising.aeron.protocol.CoreFundsPostingView(
                    posting.asset(), ownerKind(posting.ownerKind()), posting.ownerId(),
                    subledger(posting.subledger()), posting.units()));
        }
        return result.isEmpty() ? List.of() : Collections.unmodifiableList(result);
    }

    public static FundsDelta between(TradingCoreState before, TradingCoreState after,
                                     Set<Long> changedUserIds, boolean externalAdjustment) {
        HashSet<String> changedTreasuryAssets = new HashSet<>();
        addTreasuryAssets(changedTreasuryAssets, before.treasuryState());
        addTreasuryAssets(changedTreasuryAssets, after.treasuryState());
        return between(before, after, changedUserIds, changedTreasuryAssets, externalAdjustment);
    }

    public static FundsDelta between(TradingCoreState before, TradingCoreState after,
                                     Set<Long> changedUserIds, Set<String> changedTreasuryAssets,
                                     boolean externalAdjustment) {
        if (before == null || after == null || before.productLine() != after.productLine()
                || changedUserIds == null || changedTreasuryAssets == null) {
            throw new IllegalArgumentException("invalid funds delta transition");
        }
        int estimatedPostings = Math.addExact(
                Math.multiplyExact(changedUserIds.size(), 2),
                Math.multiplyExact(changedTreasuryAssets.size(), 7));
        ArrayList<FundsPosting> result = new ArrayList<>(estimatedPostings);
        for (Long userId : changedUserIds) {
            if (userId == null) continue;
            CoreUserState previous = before.user(userId);
            CoreUserState current = after.user(userId);
            if (previous != null) {
                for (String asset : previous.balances().keySet()) {
                    addUserBalanceDelta(result, previous, current, userId, asset);
                }
            }
            if (current != null) {
                for (String asset : current.balances().keySet()) {
                    if (previous == null || !previous.balances().containsKey(asset)) {
                        addUserBalanceDelta(result, previous, current, userId, asset);
                    }
                }
            }
        }
        treasury(result, before.treasuryState().feeBalances(), after.treasuryState().feeBalances(),
                changedTreasuryAssets, FundsPosting.Subledger.FEE, false);
        treasury(result, before.treasuryState().insuranceBalances(), after.treasuryState().insuranceBalances(),
                changedTreasuryAssets, FundsPosting.Subledger.INSURANCE, false);
        treasury(result, before.treasuryState().insuranceDeficits(), after.treasuryState().insuranceDeficits(),
                changedTreasuryAssets, FundsPosting.Subledger.DEFICIT, true);
        treasury(result, before.treasuryState().liquidationFeeBalances(),
                after.treasuryState().liquidationFeeBalances(), changedTreasuryAssets,
                FundsPosting.Subledger.LIQUIDATION_FEE, false);
        treasury(result, before.treasuryState().fundingResidualBalances(),
                after.treasuryState().fundingResidualBalances(), changedTreasuryAssets,
                FundsPosting.Subledger.FUNDING_RESIDUAL, false);
        treasury(result, before.treasuryState().roundingResidualBalances(),
                after.treasuryState().roundingResidualBalances(), changedTreasuryAssets,
                FundsPosting.Subledger.ROUNDING_RESIDUAL, false);
        treasury(result, before.treasuryState().clearingPnlBalances(),
                after.treasuryState().clearingPnlBalances(), changedTreasuryAssets,
                FundsPosting.Subledger.CLEARING_PNL, false);
        if (externalAdjustment) {
            HashMap<String, Long> totals = totals(result);
            totals.forEach((asset, units) -> add(result, asset, FundsPosting.OwnerKind.EXTERNAL, 0,
                    FundsPosting.Subledger.EXTERNAL_ADJUSTMENT, Math.negateExact(units)));
        }
        return new FundsDelta(result);
    }

    private static void addUserBalanceDelta(List<FundsPosting> result,
                                             CoreUserState previous,
                                             CoreUserState current,
                                             long userId,
                                             String asset) {
        AssetBalance previousBalance = previous == null ? null : previous.balances().get(asset);
        AssetBalance currentBalance = current == null ? null : current.balances().get(asset);
        add(result, asset, FundsPosting.OwnerKind.USER, userId, FundsPosting.Subledger.AVAILABLE,
                Math.subtractExact(currentBalance == null ? 0 : currentBalance.availableUnits(),
                        previousBalance == null ? 0 : previousBalance.availableUnits()));
        add(result, asset, FundsPosting.OwnerKind.USER, userId, FundsPosting.Subledger.LOCKED,
                Math.subtractExact(currentBalance == null ? 0 : currentBalance.lockedUnits(),
                        previousBalance == null ? 0 : previousBalance.lockedUnits()));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof FundsDelta other && postings.equals(other.postings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(postings);
    }

    @Override
    public String toString() {
        return "FundsDelta" + postings;
    }

    private static byte[] encodeCanonical(List<FundsPosting> postings) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(CANONICAL_VERSION);
            output.writeInt(postings.size());
            for (FundsPosting posting : postings) {
                byte[] asset = posting.asset().getBytes(StandardCharsets.UTF_8);
                output.writeShort(asset.length);
                output.write(asset);
                output.writeByte(posting.ownerKind().wireCode());
                output.writeLong(posting.ownerId());
                output.writeByte(posting.subledger().wireCode());
                output.writeLong(posting.units());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("failed to encode funds delta", impossible);
        }
    }

    private static void treasury(List<FundsPosting> target, Map<String, Long> before, Map<String, Long> after,
                                 Set<String> changedAssets,
                                 FundsPosting.Subledger subledger, boolean negate) {
        for (String asset : changedAssets) {
            long delta = Math.subtractExact(after.getOrDefault(asset, 0L), before.getOrDefault(asset, 0L));
            add(target, asset, FundsPosting.OwnerKind.TREASURY, 0, subledger,
                    negate ? Math.negateExact(delta) : delta);
        }
    }

    private static void addTreasuryAssets(Set<String> target, CoreTreasuryState treasury) {
        target.addAll(treasury.feeBalances().keySet());
        target.addAll(treasury.insuranceBalances().keySet());
        target.addAll(treasury.insuranceDeficits().keySet());
        target.addAll(treasury.liquidationFeeBalances().keySet());
        target.addAll(treasury.fundingResidualBalances().keySet());
        target.addAll(treasury.roundingResidualBalances().keySet());
        target.addAll(treasury.clearingPnlBalances().keySet());
    }

    private static HashMap<String, Long> totals(List<FundsPosting> postings) {
        HashMap<String, Long> totals = new HashMap<>();
        for (FundsPosting posting : postings) {
            totals.merge(posting.asset(), posting.units(), Math::addExact);
        }
        return totals;
    }

    private static void add(List<FundsPosting> target, String asset, FundsPosting.OwnerKind ownerKind,
                            long ownerId, FundsPosting.Subledger subledger, long units) {
        if (units != 0) target.add(new FundsPosting(asset, ownerKind, ownerId, subledger, units));
    }

    private static com.surprising.aeron.protocol.CoreFundsPostingView.OwnerKind ownerKind(
            FundsPosting.OwnerKind value) {
        return switch (value) {
            case USER -> com.surprising.aeron.protocol.CoreFundsPostingView.OwnerKind.USER;
            case MAKER -> com.surprising.aeron.protocol.CoreFundsPostingView.OwnerKind.MAKER;
            case TREASURY -> com.surprising.aeron.protocol.CoreFundsPostingView.OwnerKind.TREASURY;
            case EXTERNAL -> com.surprising.aeron.protocol.CoreFundsPostingView.OwnerKind.EXTERNAL;
        };
    }

    private static com.surprising.aeron.protocol.CoreFundsPostingView.Subledger subledger(
            FundsPosting.Subledger value) {
        return switch (value) {
            case AVAILABLE -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.AVAILABLE;
            case LOCKED -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.LOCKED;
            case RESERVATION -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.RESERVATION;
            case POSITION_MARGIN -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.POSITION_MARGIN;
            case FEE -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.FEE;
            case INSURANCE -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.INSURANCE;
            case LIQUIDATION_FEE -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.LIQUIDATION_FEE;
            case FUNDING_RESIDUAL -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.FUNDING_RESIDUAL;
            case ROUNDING_RESIDUAL -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.ROUNDING_RESIDUAL;
            case CLEARING_PNL -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.CLEARING_PNL;
            case DEFICIT -> com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.DEFICIT;
            case EXTERNAL_ADJUSTMENT ->
                    com.surprising.aeron.protocol.CoreFundsPostingView.Subledger.EXTERNAL_ADJUSTMENT;
        };
    }
}
