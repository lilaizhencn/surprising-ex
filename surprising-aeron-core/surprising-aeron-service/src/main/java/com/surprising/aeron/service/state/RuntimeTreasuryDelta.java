package com.surprising.aeron.service.state;

public final class RuntimeTreasuryDelta {
    public static final int SINGLE_COMMAND_CAPACITY = 4;
    public static final int ORDER_BATCH_CAPACITY = 40;

    private final int[] assetIds;
    private final long[] feeUnits;
    private final long[] insuranceUnits;
    private final long[] deficitUnits;
    private final long[] fundingResidualUnits;
    private final long[] roundingResidualUnits;
    private final long[] clearingUnits;
    private int size;

    public RuntimeTreasuryDelta() {
        this(SINGLE_COMMAND_CAPACITY);
    }

    public RuntimeTreasuryDelta(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("treasury delta capacity must be positive");
        assetIds = new int[capacity];
        feeUnits = new long[capacity];
        insuranceUnits = new long[capacity];
        deficitUnits = new long[capacity];
        fundingResidualUnits = new long[capacity];
        roundingResidualUnits = new long[capacity];
        clearingUnits = new long[capacity];
    }

    public int size() {
        return size;
    }

    public int assetId(int index) {
        checkIndex(index);
        return assetIds[index];
    }

    public long feeUnits(int index) {
        checkIndex(index);
        return feeUnits[index];
    }

    public long insuranceUnits(int index) {
        checkIndex(index);
        return insuranceUnits[index];
    }

    public long deficitUnits(int index) {
        checkIndex(index);
        return deficitUnits[index];
    }

    public long fundingResidualUnits(int index) {
        checkIndex(index);
        return fundingResidualUnits[index];
    }

    public long roundingResidualUnits(int index) {
        checkIndex(index);
        return roundingResidualUnits[index];
    }

    public long clearingUnits(int index) {
        checkIndex(index);
        return clearingUnits[index];
    }

    public void addFee(int assetId, long units) {
        int index = entry(assetId);
        feeUnits[index] = Math.addExact(feeUnits[index], units);
    }

    public void addInsurance(int assetId, long units) {
        int index = entry(assetId);
        insuranceUnits[index] = Math.addExact(insuranceUnits[index], units);
    }

    public void addDeficit(int assetId, long units) {
        int index = entry(assetId);
        deficitUnits[index] = Math.addExact(deficitUnits[index], units);
    }

    public void addFundingResidual(int assetId, long units) {
        int index = entry(assetId);
        fundingResidualUnits[index] = Math.addExact(fundingResidualUnits[index], units);
    }

    public void addRoundingResidual(int assetId, long units) {
        int index = entry(assetId);
        roundingResidualUnits[index] = Math.addExact(roundingResidualUnits[index], units);
    }

    public void addClearing(int assetId, long units) {
        int index = entry(assetId);
        clearingUnits[index] = Math.addExact(clearingUnits[index], units);
    }

    public void merge(RuntimeTreasuryDelta other) {
        if (other == null) throw new IllegalArgumentException("treasury delta is required");
        for (int index = 0; index < other.size; index++) {
            int assetId = other.assetIds[index];
            addFee(assetId, other.feeUnits[index]);
            addInsurance(assetId, other.insuranceUnits[index]);
            addDeficit(assetId, other.deficitUnits[index]);
            addFundingResidual(assetId, other.fundingResidualUnits[index]);
            addRoundingResidual(assetId, other.roundingResidualUnits[index]);
            addClearing(assetId, other.clearingUnits[index]);
        }
    }

    public void apply(TreasuryRuntime treasury) {
        if (treasury == null) throw new IllegalArgumentException("treasury is required");
        for (int index = 0; index < size; index++) {
            int assetId = assetIds[index];
            treasury.setFee(assetId, Math.addExact(treasury.fee(assetId), feeUnits[index]));
            treasury.setInsurance(assetId,
                    Math.addExact(treasury.insurance(assetId), insuranceUnits[index]),
                    Math.addExact(treasury.insuranceDeficit(assetId), deficitUnits[index]));
            treasury.setFundingResidual(assetId,
                    Math.addExact(treasury.fundingResidual(assetId), fundingResidualUnits[index]));
            treasury.setRoundingResidual(assetId,
                    Math.addExact(treasury.roundingResidual(assetId), roundingResidualUnits[index]));
            treasury.setClearingPnl(assetId,
                    Math.addExact(treasury.clearingPnl(assetId), clearingUnits[index]));
        }
    }

    public void clear() {
        java.util.Arrays.fill(feeUnits, 0);
        java.util.Arrays.fill(insuranceUnits, 0);
        java.util.Arrays.fill(deficitUnits, 0);
        java.util.Arrays.fill(fundingResidualUnits, 0);
        java.util.Arrays.fill(roundingResidualUnits, 0);
        java.util.Arrays.fill(clearingUnits, 0);
        size = 0;
    }

    private int entry(int assetId) {
        if (assetId < 0) throw new IllegalArgumentException("assetId must be non-negative");
        int existing = indexOf(assetId);
        if (existing >= 0) return existing;
        if (size == assetIds.length) {
            throw new IllegalStateException("account lane treasury contribution capacity exceeded");
        }
        assetIds[size] = assetId;
        return size++;
    }

    private int indexOf(int assetId) {
        for (int index = 0; index < size; index++) if (assetIds[index] == assetId) return index;
        return -1;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
    }
}
