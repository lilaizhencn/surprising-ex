package com.surprising.aeron.service.state;

public final class RuntimeTreasuryDelta {
    public static final int SINGLE_COMMAND_CAPACITY = 4;
    public static final int ORDER_BATCH_CAPACITY = 40;

    private final int capacity;
    private int firstAssetId;
    private long firstFeeUnits;
    private long firstInsuranceUnits;
    private long firstDeficitUnits;
    private long firstFundingResidualUnits;
    private long firstRoundingResidualUnits;
    private long firstClearingUnits;
    private int[] assetIds;
    private long[] feeUnits;
    private long[] insuranceUnits;
    private long[] deficitUnits;
    private long[] fundingResidualUnits;
    private long[] roundingResidualUnits;
    private long[] clearingUnits;
    private int size;

    public RuntimeTreasuryDelta() {
        this(SINGLE_COMMAND_CAPACITY);
    }

    public RuntimeTreasuryDelta(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("treasury delta capacity must be positive");
        this.capacity = capacity;
    }

    public int size() {
        return size;
    }

    public int assetId(int index) {
        checkIndex(index);
        return assetAt(index);
    }

    public long feeUnits(int index) {
        checkIndex(index);
        return feeAt(index);
    }

    public long insuranceUnits(int index) {
        checkIndex(index);
        return insuranceAt(index);
    }

    public long deficitUnits(int index) {
        checkIndex(index);
        return deficitAt(index);
    }

    public long fundingResidualUnits(int index) {
        checkIndex(index);
        return fundingResidualAt(index);
    }

    public long roundingResidualUnits(int index) {
        checkIndex(index);
        return roundingResidualAt(index);
    }

    public long clearingUnits(int index) {
        checkIndex(index);
        return clearingAt(index);
    }

    public void addFee(int assetId, long units) {
        int index = entry(assetId);
        if (feeUnits == null) firstFeeUnits = Math.addExact(firstFeeUnits, units);
        else feeUnits[index] = Math.addExact(feeUnits[index], units);
    }

    public void addInsurance(int assetId, long units) {
        int index = entry(assetId);
        if (insuranceUnits == null) firstInsuranceUnits = Math.addExact(firstInsuranceUnits, units);
        else insuranceUnits[index] = Math.addExact(insuranceUnits[index], units);
    }

    public void addDeficit(int assetId, long units) {
        int index = entry(assetId);
        if (deficitUnits == null) firstDeficitUnits = Math.addExact(firstDeficitUnits, units);
        else deficitUnits[index] = Math.addExact(deficitUnits[index], units);
    }

    public void addFundingResidual(int assetId, long units) {
        int index = entry(assetId);
        if (fundingResidualUnits == null) {
            firstFundingResidualUnits = Math.addExact(firstFundingResidualUnits, units);
        } else {
            fundingResidualUnits[index] = Math.addExact(fundingResidualUnits[index], units);
        }
    }

    public void addRoundingResidual(int assetId, long units) {
        int index = entry(assetId);
        if (roundingResidualUnits == null) {
            firstRoundingResidualUnits = Math.addExact(firstRoundingResidualUnits, units);
        } else {
            roundingResidualUnits[index] = Math.addExact(roundingResidualUnits[index], units);
        }
    }

    public void addClearing(int assetId, long units) {
        int index = entry(assetId);
        if (clearingUnits == null) firstClearingUnits = Math.addExact(firstClearingUnits, units);
        else clearingUnits[index] = Math.addExact(clearingUnits[index], units);
    }

    public void merge(RuntimeTreasuryDelta other) {
        if (other == null) throw new IllegalArgumentException("treasury delta is required");
        for (int index = 0; index < other.size; index++) {
            int assetId = other.assetAt(index);
            addFee(assetId, other.feeAt(index));
            addInsurance(assetId, other.insuranceAt(index));
            addDeficit(assetId, other.deficitAt(index));
            addFundingResidual(assetId, other.fundingResidualAt(index));
            addRoundingResidual(assetId, other.roundingResidualAt(index));
            addClearing(assetId, other.clearingAt(index));
        }
    }

    public void apply(TreasuryRuntime treasury) {
        if (treasury == null) throw new IllegalArgumentException("treasury is required");
        for (int index = 0; index < size; index++) {
            int assetId = assetAt(index);
            treasury.setFee(assetId, Math.addExact(treasury.fee(assetId), feeAt(index)));
            treasury.setInsurance(assetId,
                    Math.addExact(treasury.insurance(assetId), insuranceAt(index)),
                    Math.addExact(treasury.insuranceDeficit(assetId), deficitAt(index)));
            treasury.setFundingResidual(assetId,
                    Math.addExact(treasury.fundingResidual(assetId), fundingResidualAt(index)));
            treasury.setRoundingResidual(assetId,
                    Math.addExact(treasury.roundingResidual(assetId), roundingResidualAt(index)));
            treasury.setClearingPnl(assetId,
                    Math.addExact(treasury.clearingPnl(assetId), clearingAt(index)));
        }
    }

    public void clear() {
        size = 0;
    }

    private int entry(int assetId) {
        if (assetId < 0) throw new IllegalArgumentException("assetId must be non-negative");
        int existing = indexOf(assetId);
        if (existing >= 0) return existing;
        if (size == capacity) {
            throw new IllegalStateException("account lane treasury contribution capacity exceeded");
        }
        if (size == 0 && assetIds == null) {
            firstAssetId = assetId;
            firstFeeUnits = 0;
            firstInsuranceUnits = 0;
            firstDeficitUnits = 0;
            firstFundingResidualUnits = 0;
            firstRoundingResidualUnits = 0;
            firstClearingUnits = 0;
        } else {
            ensureArrays();
            assetIds[size] = assetId;
            feeUnits[size] = 0;
            insuranceUnits[size] = 0;
            deficitUnits[size] = 0;
            fundingResidualUnits[size] = 0;
            roundingResidualUnits[size] = 0;
            clearingUnits[size] = 0;
        }
        return size++;
    }

    private int indexOf(int assetId) {
        for (int index = 0; index < size; index++) if (assetAt(index) == assetId) return index;
        return -1;
    }

    private void ensureArrays() {
        if (assetIds != null) return;
        assetIds = new int[capacity];
        feeUnits = new long[capacity];
        insuranceUnits = new long[capacity];
        deficitUnits = new long[capacity];
        fundingResidualUnits = new long[capacity];
        roundingResidualUnits = new long[capacity];
        clearingUnits = new long[capacity];
        if (size != 0) {
            assetIds[0] = firstAssetId;
            feeUnits[0] = firstFeeUnits;
            insuranceUnits[0] = firstInsuranceUnits;
            deficitUnits[0] = firstDeficitUnits;
            fundingResidualUnits[0] = firstFundingResidualUnits;
            roundingResidualUnits[0] = firstRoundingResidualUnits;
            clearingUnits[0] = firstClearingUnits;
        }
    }

    private int assetAt(int index) { return assetIds == null ? firstAssetId : assetIds[index]; }
    private long feeAt(int index) { return feeUnits == null ? firstFeeUnits : feeUnits[index]; }
    private long insuranceAt(int index) {
        return insuranceUnits == null ? firstInsuranceUnits : insuranceUnits[index];
    }
    private long deficitAt(int index) { return deficitUnits == null ? firstDeficitUnits : deficitUnits[index]; }
    private long fundingResidualAt(int index) {
        return fundingResidualUnits == null ? firstFundingResidualUnits : fundingResidualUnits[index];
    }
    private long roundingResidualAt(int index) {
        return roundingResidualUnits == null ? firstRoundingResidualUnits : roundingResidualUnits[index];
    }
    private long clearingAt(int index) { return clearingUnits == null ? firstClearingUnits : clearingUnits[index]; }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
    }
}
