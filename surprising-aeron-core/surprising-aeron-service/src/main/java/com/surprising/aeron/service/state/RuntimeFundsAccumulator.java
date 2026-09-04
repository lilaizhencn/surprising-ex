package com.surprising.aeron.service.state;

import java.util.ArrayList;
import java.util.Arrays;

/** Reusable sequence-local primitive accumulator for funds postings. */
final class RuntimeFundsAccumulator {
    private static final FundsPosting.OwnerKind[] OWNER_KINDS = FundsPosting.OwnerKind.values();
    private static final FundsPosting.Subledger[] SUBLEDGERS = FundsPosting.Subledger.values();
    private int[] assetIds;
    private byte[] ownerKinds;
    private long[] ownerIds;
    private byte[] subledgers;
    private long[] units;
    private int size;

    RuntimeFundsAccumulator() {
        this(16);
    }

    RuntimeFundsAccumulator(int capacity) {
        int initialCapacity = Math.max(1, capacity);
        assetIds = new int[initialCapacity];
        ownerKinds = new byte[initialCapacity];
        ownerIds = new long[initialCapacity];
        subledgers = new byte[initialCapacity];
        units = new long[initialCapacity];
    }

    void add(int assetId, FundsPosting.OwnerKind ownerKind, long ownerId,
             FundsPosting.Subledger subledger, long deltaUnits) {
        if (deltaUnits == 0) return;
        if (assetId < 0 || ownerKind == null || subledger == null) {
            throw new IllegalArgumentException("invalid runtime funds posting");
        }
        byte ownerKindCode = (byte) ownerKind.ordinal();
        byte subledgerCode = (byte) subledger.ordinal();
        for (int index = 0; index < size; index++) {
            if (assetIds[index] != assetId || ownerKinds[index] != ownerKindCode
                    || ownerIds[index] != ownerId || subledgers[index] != subledgerCode) continue;
            long merged = Math.addExact(units[index], deltaUnits);
            if (merged == 0) removeAt(index);
            else units[index] = merged;
            return;
        }
        ensureCapacity(size + 1);
        assetIds[size] = assetId;
        ownerKinds[size] = ownerKindCode;
        ownerIds[size] = ownerId;
        subledgers[size] = subledgerCode;
        units[size] = deltaUnits;
        size++;
    }

    void add(RuntimeFundsDelta delta) {
        if (delta == null) return;
        for (RuntimeFactFrame.FundsPosting posting : delta.postings()) {
            add(posting.assetId(), posting.ownerKind(), posting.ownerId(), posting.subledger(), posting.units());
        }
    }

    void add(RuntimeFundsAccumulator other) {
        if (other == null) return;
        for (int index = 0; index < other.size; index++) {
            add(other.assetIds[index], OWNER_KINDS[other.ownerKinds[index]], other.ownerIds[index],
                    SUBLEDGERS[other.subledgers[index]], other.units[index]);
        }
    }

    RuntimeFundsDelta toDelta() {
        if (size == 0) return RuntimeFundsDelta.empty();
        ArrayList<RuntimeFactFrame.FundsPosting> postings = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            postings.add(new RuntimeFactFrame.FundsPosting(assetIds[index], OWNER_KINDS[ownerKinds[index]],
                    ownerIds[index], SUBLEDGERS[subledgers[index]], units[index]));
        }
        return RuntimeFundsDelta.fromDistinctPatchPostings(postings);
    }

    void clear() {
        size = 0;
    }

    private void removeAt(int index) {
        int last = --size;
        if (index == last) return;
        assetIds[index] = assetIds[last];
        ownerKinds[index] = ownerKinds[last];
        ownerIds[index] = ownerIds[last];
        subledgers[index] = subledgers[last];
        units[index] = units[last];
    }

    private void ensureCapacity(int required) {
        if (required <= assetIds.length) return;
        int capacity = Math.max(required, Math.multiplyExact(assetIds.length, 2));
        assetIds = Arrays.copyOf(assetIds, capacity);
        ownerKinds = Arrays.copyOf(ownerKinds, capacity);
        ownerIds = Arrays.copyOf(ownerIds, capacity);
        subledgers = Arrays.copyOf(subledgers, capacity);
        units = Arrays.copyOf(units, capacity);
    }
}
