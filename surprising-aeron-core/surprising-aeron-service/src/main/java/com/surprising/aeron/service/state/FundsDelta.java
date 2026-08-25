package com.surprising.aeron.service.state;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
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
        TreeMap<FundsPosting, Long> coalesced = new TreeMap<>(POSTING_ORDER);
        TreeSet<String> assets = new TreeSet<>();
        for (FundsPosting posting : source) {
            if (posting == null) {
                throw new IllegalArgumentException("funds posting is required");
            }
            assets.add(posting.asset());
            coalesced.merge(posting, posting.units(), Math::addExact);
        }
        coalesced.entrySet().removeIf(entry -> entry.getValue() == 0);

        ArrayList<FundsPosting> normalized = new ArrayList<>(coalesced.size());
        TreeMap<String, Long> totals = new TreeMap<>();
        assets.forEach(asset -> totals.put(asset, 0L));
        coalesced.forEach((posting, units) -> {
            FundsPosting normalizedPosting = new FundsPosting(posting.asset(), posting.ownerKind(),
                    posting.ownerId(), posting.subledger(), units);
            normalized.add(normalizedPosting);
            totals.put(posting.asset(), Math.addExact(totals.get(posting.asset()), units));
        });
        totals.forEach((asset, units) -> {
            if (units != 0) {
                throw new IllegalArgumentException("Funds delta is not conserved for asset " + asset);
            }
        });
        postings = List.copyOf(normalized);
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
        return postings.stream().map(posting -> new com.surprising.aeron.protocol.CoreFundsPostingView(
                posting.asset(), ownerKind(posting.ownerKind()), posting.ownerId(),
                subledger(posting.subledger()), posting.units())).toList();
    }

    public static FundsDelta between(TradingCoreState before, TradingCoreState after,
                                     Set<Long> changedUserIds, boolean externalAdjustment) {
        if (before == null || after == null || before.productLine() != after.productLine()
                || changedUserIds == null) {
            throw new IllegalArgumentException("invalid funds delta transition");
        }
        ArrayList<FundsPosting> result = new ArrayList<>();
        for (Long userId : new TreeSet<>(changedUserIds)) {
            if (userId == null) continue;
            CoreUserState previous = before.user(userId);
            CoreUserState current = after.user(userId);
            TreeSet<String> assets = new TreeSet<>();
            if (previous != null) assets.addAll(previous.balances().keySet());
            if (current != null) assets.addAll(current.balances().keySet());
            for (String asset : assets) {
                AssetBalance previousBalance = previous == null ? null : previous.balances().get(asset);
                AssetBalance currentBalance = current == null ? null : current.balances().get(asset);
                add(result, asset, FundsPosting.OwnerKind.USER, userId, FundsPosting.Subledger.AVAILABLE,
                        Math.subtractExact(currentBalance == null ? 0 : currentBalance.availableUnits(),
                                previousBalance == null ? 0 : previousBalance.availableUnits()));
                add(result, asset, FundsPosting.OwnerKind.USER, userId, FundsPosting.Subledger.LOCKED,
                        Math.subtractExact(currentBalance == null ? 0 : currentBalance.lockedUnits(),
                                previousBalance == null ? 0 : previousBalance.lockedUnits()));
            }
        }
        treasury(result, before.treasuryState().feeBalances(), after.treasuryState().feeBalances(),
                FundsPosting.Subledger.FEE, false);
        treasury(result, before.treasuryState().insuranceBalances(), after.treasuryState().insuranceBalances(),
                FundsPosting.Subledger.INSURANCE, false);
        treasury(result, before.treasuryState().insuranceDeficits(), after.treasuryState().insuranceDeficits(),
                FundsPosting.Subledger.DEFICIT, true);
        treasury(result, before.treasuryState().liquidationFeeBalances(),
                after.treasuryState().liquidationFeeBalances(), FundsPosting.Subledger.LIQUIDATION_FEE, false);
        treasury(result, before.treasuryState().fundingResidualBalances(),
                after.treasuryState().fundingResidualBalances(), FundsPosting.Subledger.FUNDING_RESIDUAL, false);
        treasury(result, before.treasuryState().roundingResidualBalances(),
                after.treasuryState().roundingResidualBalances(), FundsPosting.Subledger.ROUNDING_RESIDUAL, false);
        treasury(result, before.treasuryState().clearingPnlBalances(),
                after.treasuryState().clearingPnlBalances(), FundsPosting.Subledger.CLEARING_PNL, false);
        if (externalAdjustment) {
            TreeMap<String, Long> totals = totals(result);
            totals.forEach((asset, units) -> add(result, asset, FundsPosting.OwnerKind.EXTERNAL, 0,
                    FundsPosting.Subledger.EXTERNAL_ADJUSTMENT, Math.negateExact(units)));
        }
        return new FundsDelta(result);
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
                                 FundsPosting.Subledger subledger, boolean negate) {
        TreeSet<String> assets = new TreeSet<>(before.keySet());
        assets.addAll(after.keySet());
        for (String asset : assets) {
            long delta = Math.subtractExact(after.getOrDefault(asset, 0L), before.getOrDefault(asset, 0L));
            add(target, asset, FundsPosting.OwnerKind.TREASURY, 0, subledger,
                    negate ? Math.negateExact(delta) : delta);
        }
    }

    private static TreeMap<String, Long> totals(List<FundsPosting> postings) {
        TreeMap<String, Long> totals = new TreeMap<>();
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
