package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.settlement.FundsPosting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

public final class RuntimeFundsDelta {

    private static final RuntimeFundsDelta EMPTY = new RuntimeFundsDelta(List.of(), false, true);
    private final List<Posting> postings;
    private final IntLongHashMap unitsByAsset;
    private final int[] assetIds;

    RuntimeFundsDelta(List<Posting> postings) {
        this(postings, true, true);
    }

    private RuntimeFundsDelta(List<Posting> postings,
                              boolean normalize, boolean trusted) {
        if (postings == null) throw new IllegalArgumentException("runtime funds postings are required");
        ArrayList<Posting> normalizedPostings;
        if (normalize) {
            normalizedPostings = new ArrayList<>(postings.size());
            for (Posting posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
                int existingIndex = -1;
                for (int index = 0; index < normalizedPostings.size(); index++) {
                    if (samePostingKey(normalizedPostings.get(index), posting)) {
                        existingIndex = index;
                        break;
                    }
                }
                if (existingIndex < 0) {
                    normalizedPostings.add(posting);
                    continue;
                }
                Posting existing = normalizedPostings.get(existingIndex);
                long units = Math.addExact(existing.units(), posting.units());
                if (units == 0) normalizedPostings.remove(existingIndex);
                else normalizedPostings.set(existingIndex, new Posting(
                        existing.assetId(), existing.ownerKind(), existing.ownerId(), existing.subledger(), units));
            }
        } else if (trusted) {
            for (Posting posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
            }
            normalizedPostings = null;
        } else {
            normalizedPostings = new ArrayList<>(postings.size());
            for (Posting posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
                normalizedPostings.add(posting);
            }
        }
        IntLongHashMap totals = new IntLongHashMap();
        int[] touchedAssets = new int[Math.max(1, trusted ? postings.size() : normalizedPostings.size())];
        int touchedAssetCount = 0;
        List<Posting> source = trusted ? postings : normalizedPostings;
        for (Posting posting : source) {
            if (!totals.containsKey(posting.assetId())) touchedAssets[touchedAssetCount++] = posting.assetId();
            long previous = totals.get(posting.assetId());
            totals.put(posting.assetId(), Math.addExact(previous, posting.units()));
        }
        this.postings = trusted ? Collections.unmodifiableList(postings) : List.copyOf(normalizedPostings);
        this.unitsByAsset = totals;
        this.assetIds = java.util.Arrays.copyOf(touchedAssets, touchedAssetCount);
    }

    private static boolean samePostingKey(Posting left, Posting right) {
        return left.assetId() == right.assetId()
                && left.ownerKind() == right.ownerKind()
                && left.ownerId() == right.ownerId()
                && left.subledger() == right.subledger();
    }

    public static RuntimeFundsDelta empty() {
        return EMPTY;
    }

    static RuntimeFundsDelta from(List<Posting> postings) {
        return postings.isEmpty() ? EMPTY : new RuntimeFundsDelta(postings);
    }

    static RuntimeFundsDelta fromDistinct(List<Posting> postings) {
        return postings.isEmpty() ? EMPTY : new RuntimeFundsDelta(postings, false, true);
    }

    public RuntimeFundsDelta plus(RuntimeFundsDelta other) {
        if (other == null || other.postings.isEmpty()) return this;
        if (postings.isEmpty()) return other;
        RuntimeFundsAccumulator accumulator = new RuntimeFundsAccumulator(postings.size() + other.postings.size());
        accumulator.add(this);
        accumulator.add(other);
        return accumulator.toDelta();
    }

    public int postingCount() {
        return postings.size();
    }

    public void requireConserved(boolean externalAdjustment) {
        if (externalAdjustment) return;
        for (int assetId : assetIds) {
            long units = unitsByAsset.get(assetId);
            if (units != 0) {
                throw new IllegalArgumentException("runtime funds delta is not conserved for asset " + assetId);
            }
        }
    }

    public RuntimeTreasuryDelta treasuryDelta() {
        RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta(Math.max(
                RuntimeTreasuryDelta.SINGLE_COMMAND_CAPACITY, unitsByAsset.size()));
        for (Posting posting : postings) {
            if (posting.ownerKind() != FundsPosting.OwnerKind.TREASURY) continue;
            switch (posting.subledger()) {
                case FEE -> delta.addFee(posting.assetId(), posting.units());
                case INSURANCE, LIQUIDATION_FEE -> delta.addInsurance(posting.assetId(), posting.units());
                case DEFICIT -> delta.addDeficit(posting.assetId(), Math.negateExact(posting.units()));
                case FUNDING_RESIDUAL -> delta.addFundingResidual(posting.assetId(), posting.units());
                case ROUNDING_RESIDUAL -> delta.addRoundingResidual(posting.assetId(), posting.units());
                case CLEARING_PNL -> delta.addClearing(posting.assetId(), posting.units());
                case AVAILABLE, LOCKED, RESERVATION, POSITION_MARGIN, EXTERNAL_ADJUSTMENT ->
                        throw new IllegalStateException(
                        "invalid Treasury funds subledger: " + posting.subledger());
            }
        }
        return delta;
    }

    List<Posting> postings() {
        return postings;
    }

    record Posting(int assetId, FundsPosting.OwnerKind ownerKind, long ownerId,
                   FundsPosting.Subledger subledger, long units) {
        Posting {
            if (assetId < 0 || ownerKind == null || subledger == null || units == 0) {
                throw new IllegalArgumentException("invalid runtime funds posting");
            }
        }

    }
}
