package com.surprising.aeron.service.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

public final class RuntimeFundsDelta {

    private static final RuntimeFundsDelta EMPTY = new RuntimeFundsDelta(List.of());
    private final List<Posting> postings;
    private final IntLongHashMap unitsByAsset;

    RuntimeFundsDelta(List<Posting> postings) {
        this(postings, true);
    }

    private RuntimeFundsDelta(List<Posting> postings, boolean normalize) {
        if (postings == null) throw new IllegalArgumentException("runtime funds postings are required");
        ArrayList<Posting> normalizedPostings;
        if (normalize) {
            Map<PostingKey, Long> coalesced = new HashMap<>();
            for (Posting posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
                coalesced.merge(posting.key(), posting.units(), Math::addExact);
            }
            ArrayList<PostingKey> orderedKeys = new ArrayList<>(coalesced.keySet());
            orderedKeys.sort(null);
            normalizedPostings = new ArrayList<>(coalesced.size());
            for (PostingKey key : orderedKeys) {
                long units = coalesced.get(key);
                if (units == 0) continue;
                normalizedPostings.add(new Posting(
                        key.assetId(), key.ownerKind(), key.ownerId(), key.subledger(), units));
            }
        } else {
            normalizedPostings = new ArrayList<>(postings.size());
            for (Posting posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
                normalizedPostings.add(posting);
            }
        }
        IntLongHashMap totals = new IntLongHashMap();
        for (Posting posting : normalizedPostings) {
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
        return postings.isEmpty() ? EMPTY : new RuntimeFundsDelta(postings, false);
    }

    public RuntimeFundsDelta plus(RuntimeFundsDelta other) {
        if (other == null || other.postings.isEmpty()) return this;
        if (postings.isEmpty()) return other;
        ArrayList<Posting> merged = new ArrayList<>(postings.size() + other.postings.size());
        merged.addAll(postings);
        merged.addAll(other.postings);
        return from(merged);
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

    public FundsDelta materialize(RuntimeIdentityRegistry identities, boolean externalAdjustment) {
        ArrayList<FundsPosting> materialized = new ArrayList<>(postings.size() + unitsByAsset.size());
        for (Posting posting : postings) {
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

        PostingKey key() {
            return new PostingKey(assetId, ownerKind, ownerId, subledger);
        }
    }

    private record PostingKey(int assetId, FundsPosting.OwnerKind ownerKind, long ownerId,
                              FundsPosting.Subledger subledger) implements Comparable<PostingKey> {
        @Override
        public int compareTo(PostingKey other) {
            int result = Integer.compare(assetId, other.assetId);
            if (result == 0) result = Integer.compare(ownerKind.ordinal(), other.ownerKind.ordinal());
            if (result == 0) result = Long.compare(ownerId, other.ownerId);
            if (result == 0) result = Integer.compare(subledger.ordinal(), other.subledger.ordinal());
            return result;
        }
    }
}
