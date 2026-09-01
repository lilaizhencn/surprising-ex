package com.surprising.aeron.service.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

public final class RuntimeFundsDelta {

    private static final RuntimeFundsDelta EMPTY = new RuntimeFundsDelta(List.of(), false, true);
    private final List<RuntimeCommitPatch.FundsPosting> postings;
    private final IntLongHashMap unitsByAsset;

    RuntimeFundsDelta(List<Posting> postings) {
        this(toPatchPostings(postings), true, true);
    }

    private RuntimeFundsDelta(List<RuntimeCommitPatch.FundsPosting> postings,
                              boolean normalize, boolean trusted) {
        if (postings == null) throw new IllegalArgumentException("runtime funds postings are required");
        ArrayList<RuntimeCommitPatch.FundsPosting> normalizedPostings;
        if (normalize) {
            ArrayList<RuntimeCommitPatch.FundsPosting> ordered = new ArrayList<>(postings.size());
            for (RuntimeCommitPatch.FundsPosting posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
                ordered.add(posting);
            }
            ordered.sort(null);
            normalizedPostings = new ArrayList<>(ordered.size());
            RuntimeCommitPatch.FundsPosting key = null;
            long units = 0;
            for (RuntimeCommitPatch.FundsPosting posting : ordered) {
                if (key != null && key.compareTo(posting) != 0) {
                    appendNormalized(normalizedPostings, key, units);
                    units = 0;
                }
                if (key == null || key.compareTo(posting) != 0) key = posting;
                units = Math.addExact(units, posting.units());
            }
            if (key != null) appendNormalized(normalizedPostings, key, units);
        } else {
            normalizedPostings = new ArrayList<>(postings.size());
            for (RuntimeCommitPatch.FundsPosting posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
                normalizedPostings.add(posting);
            }
        }
        IntLongHashMap totals = new IntLongHashMap();
        for (RuntimeCommitPatch.FundsPosting posting : normalizedPostings) {
            long previous = totals.get(posting.assetId());
            totals.put(posting.assetId(), Math.addExact(previous, posting.units()));
        }
        this.postings = List.copyOf(normalizedPostings);
        this.unitsByAsset = totals;
    }

    public static RuntimeFundsDelta empty() {
        return EMPTY;
    }

    static RuntimeFundsDelta from(List<Posting> postings) {
        return postings.isEmpty() ? EMPTY : new RuntimeFundsDelta(postings);
    }

    static RuntimeFundsDelta fromDistinct(List<Posting> postings) {
        return postings.isEmpty() ? EMPTY : new RuntimeFundsDelta(toPatchPostings(postings), false, true);
    }

    static RuntimeFundsDelta fromPatchPostings(List<RuntimeCommitPatch.FundsPosting> postings) {
        return postings.isEmpty() ? EMPTY : new RuntimeFundsDelta(postings, true, true);
    }

    public RuntimeFundsDelta plus(RuntimeFundsDelta other) {
        if (other == null || other.postings.isEmpty()) return this;
        if (postings.isEmpty()) return other;
        ArrayList<RuntimeCommitPatch.FundsPosting> merged = new ArrayList<>(postings.size() + other.postings.size());
        merged.addAll(postings);
        merged.addAll(other.postings);
        return fromPatchPostings(merged);
    }

    public int postingCount() {
        return postings.size();
    }

    public void requireConserved(boolean externalAdjustment) {
        if (externalAdjustment) return;
        unitsByAsset.forEachKeyValue((assetId, units) -> {
            if (units != 0) {
                throw new IllegalArgumentException("runtime funds delta is not conserved for asset " + assetId);
            }
        });
    }

    public FundsDelta materialize(RuntimeCommitPatch.IdentityView identities, boolean externalAdjustment) {
        ArrayList<FundsPosting> materialized = new ArrayList<>(postings.size() + unitsByAsset.size());
        for (RuntimeCommitPatch.FundsPosting posting : postings) {
            materialized.add(new FundsPosting(identities.asset(posting.assetId()), posting.ownerKind(),
                    posting.ownerId(), posting.subledger(), posting.units()));
        }
        if (externalAdjustment) {
            int[] assetIds = unitsByAsset.keySet().toArray();
            Arrays.sort(assetIds);
            for (int assetId : assetIds) {
                long units = unitsByAsset.get(assetId);
                if (units != 0) {
                    materialized.add(new FundsPosting(identities.asset(assetId), FundsPosting.OwnerKind.EXTERNAL,
                            0, FundsPosting.Subledger.EXTERNAL_ADJUSTMENT, Math.negateExact(units)));
                }
            }
        }
        return new FundsDelta(materialized);
    }

    public RuntimeTreasuryDelta treasuryDelta() {
        RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta(Math.max(
                RuntimeTreasuryDelta.SINGLE_COMMAND_CAPACITY, unitsByAsset.size()));
        for (RuntimeCommitPatch.FundsPosting posting : postings) {
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

    List<RuntimeCommitPatch.FundsPosting> postings() {
        return postings;
    }

    private static void appendNormalized(ArrayList<RuntimeCommitPatch.FundsPosting> target,
                                         RuntimeCommitPatch.FundsPosting key, long units) {
        if (units == 0) return;
        target.add(units == key.units() ? key : new RuntimeCommitPatch.FundsPosting(
                key.assetId(), key.ownerKind(), key.ownerId(), key.subledger(), units));
    }

    private static List<RuntimeCommitPatch.FundsPosting> toPatchPostings(List<Posting> postings) {
        if (postings == null || postings.isEmpty()) return List.of();
        ArrayList<RuntimeCommitPatch.FundsPosting> converted = new ArrayList<>(postings.size());
        for (Posting posting : postings) {
            if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
            converted.add(new RuntimeCommitPatch.FundsPosting(posting.assetId(), posting.ownerKind(),
                    posting.ownerId(), posting.subledger(), posting.units()));
        }
        return converted;
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
