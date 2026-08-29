package com.surprising.aeron.service.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeFundsDelta {

    private final List<Posting> postings;
    private final Map<Integer, Long> unitsByAsset;

    RuntimeFundsDelta(List<Posting> postings) {
        if (postings == null) throw new IllegalArgumentException("runtime funds postings are required");
        Map<PostingKey, Long> coalesced = new HashMap<>();
        for (Posting posting : postings) {
            if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
            coalesced.merge(posting.key(), posting.units(), Math::addExact);
        }
        ArrayList<PostingKey> orderedKeys = new ArrayList<>(coalesced.keySet());
        orderedKeys.sort(null);
        ArrayList<Posting> normalized = new ArrayList<>(coalesced.size());
        Map<Integer, Long> totals = new HashMap<>();
        for (PostingKey key : orderedKeys) {
            long units = coalesced.get(key);
            if (units == 0) continue;
            normalized.add(new Posting(key.assetId(), key.ownerKind(), key.ownerId(), key.subledger(), units));
            totals.merge(key.assetId(), units, Math::addExact);
        }
        this.postings = List.copyOf(normalized);
        this.unitsByAsset = Collections.unmodifiableMap(totals);
    }

    public static RuntimeFundsDelta empty() {
        return new RuntimeFundsDelta(List.of());
    }

    public RuntimeFundsDelta plus(RuntimeFundsDelta other) {
        if (other == null || other.postings.isEmpty()) return this;
        if (postings.isEmpty()) return other;
        ArrayList<Posting> merged = new ArrayList<>(postings.size() + other.postings.size());
        merged.addAll(postings);
        merged.addAll(other.postings);
        return new RuntimeFundsDelta(merged);
    }

    public int postingCount() {
        return postings.size();
    }

    public void requireConserved(boolean externalAdjustment) {
        if (externalAdjustment) return;
        unitsByAsset.forEach((assetId, units) -> {
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
            ArrayList<Integer> assetIds = new ArrayList<>(unitsByAsset.keySet());
            assetIds.sort(null);
            for (Integer assetId : assetIds) {
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
                case AVAILABLE, LOCKED, EXTERNAL_ADJUSTMENT -> throw new IllegalStateException(
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
