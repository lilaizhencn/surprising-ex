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
}
