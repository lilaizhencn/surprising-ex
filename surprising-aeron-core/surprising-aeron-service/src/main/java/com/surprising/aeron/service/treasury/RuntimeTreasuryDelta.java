package com.surprising.aeron.service.treasury;

/** 所属命令或 Lane 的系统资金差额，按资产归集费用、保险、亏空、资金费残差、舍入及清算。 */
public final class RuntimeTreasuryDelta {
    public static final int SINGLE_COMMAND_CAPACITY = 4;

    private final int capacity;
    private int firstAssetId;
    private long firstFeeUnits;
    private long firstInsuranceUnits;
    private long firstDeficitUnits;
    private long firstFundingResidualUnits;
    /** 单资产标量舍入残差。 */
    private long firstRoundingResidualUnits;
    private long firstClearingUnits;
    private int[] assetIds;
    private long[] feeUnits;
    private long[] insuranceUnits;
    private long[] deficitUnits;
    private long[] fundingResidualUnits;
    /** 多资产舍入残差列。 */
    private long[] roundingResidualUnits;
    private long[] clearingUnits;
    private int size;

    /** 以普通命令四个资产槽的容量创建差额，尚不分配多资产数组。 */
    public RuntimeTreasuryDelta() {
        this(SINGLE_COMMAND_CAPACITY);
    }

    /** 保存正数固定资产容量，后续超容量拒绝而不是自动扩展。 */
    public RuntimeTreasuryDelta(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("treasury delta capacity must be positive");
        this.capacity = capacity;
    }

    /** 找到或登记资产项，以精确加法增加该项系统手续费差额，零金额也会登记资产。 */
    public void addFee(int assetId, long units) {
        int index = entry(assetId);
        if (feeUnits == null) firstFeeUnits = Math.addExact(firstFeeUnits, units);
        else feeUnits[index] = Math.addExact(feeUnits[index], units);
    }

    /** 找到或登记资产项，以精确加法增加保险基金差额。 */
    public void addInsurance(int assetId, long units) {
        int index = entry(assetId);
        if (insuranceUnits == null) firstInsuranceUnits = Math.addExact(firstInsuranceUnits, units);
        else insuranceUnits[index] = Math.addExact(insuranceUnits[index], units);
    }

    /** 找到或登记资产项，以精确加法增加保险亏空差额。 */
    public void addDeficit(int assetId, long units) {
        int index = entry(assetId);
        if (deficitUnits == null) firstDeficitUnits = Math.addExact(firstDeficitUnits, units);
        else deficitUnits[index] = Math.addExact(deficitUnits[index], units);
    }

    /** 找到或登记资产项，以精确加法增加资金费残差。 */
    public void addFundingResidual(int assetId, long units) {
        int index = entry(assetId);
        if (fundingResidualUnits == null) {
            firstFundingResidualUnits = Math.addExact(firstFundingResidualUnits, units);
        } else {
            fundingResidualUnits[index] = Math.addExact(fundingResidualUnits[index], units);
        }
    }

    /** 找到或登记资产项，以精确加法增加舍入残差。 */
    public void addRoundingResidual(int assetId, long units) {
        int index = entry(assetId);
        if (roundingResidualUnits == null) {
            firstRoundingResidualUnits = Math.addExact(firstRoundingResidualUnits, units);
        } else {
            roundingResidualUnits[index] = Math.addExact(roundingResidualUnits[index], units);
        }
    }

    /** 找到或登记资产项，以精确加法增加系统清算盈亏差额。 */
    public void addClearing(int assetId, long units) {
        int index = entry(assetId);
        if (clearingUnits == null) firstClearingUnits = Math.addExact(firstClearingUnits, units);
        else clearingUnits[index] = Math.addExact(clearingUnits[index], units);
    }

    /** 逐资产逐金额列合并另一差额，不清空源。途中异常不提供整组自动回滚。 */
    public void merge(RuntimeTreasuryDelta other) {
        if (other == null) throw new IllegalArgumentException("treasury delta is required");
        for (int index = 0; index < other.size; index++) {
            int assetId = (other.assetIds == null ? other.firstAssetId : other.assetIds[index]);
            addFee(assetId, (other.feeUnits == null ? other.firstFeeUnits : other.feeUnits[index]));
            addInsurance(assetId, (other.insuranceUnits == null ? other.firstInsuranceUnits : other.insuranceUnits[index]));
            addDeficit(assetId, (other.deficitUnits == null ? other.firstDeficitUnits : other.deficitUnits[index]));
            addFundingResidual(assetId, (other.fundingResidualUnits == null ? other.firstFundingResidualUnits : other.fundingResidualUnits[index]));
            addRoundingResidual(assetId, (other.roundingResidualUnits == null ? other.firstRoundingResidualUnits : other.roundingResidualUnits[index]));
            addClearing(assetId, (other.clearingUnits == null ? other.firstClearingUnits : other.clearingUnits[index]));
        }
    }

    /** 在财库单写者边界先检查所有资产全部列的加法溢出，再调用原 Treasury 更新。这项保证针对加法溢出，不替代各 setter 的业务合法性检查或外层回滚。 */
    public void apply(TreasuryRuntime treasury) {
        if (treasury == null) throw new IllegalArgumentException("treasury is required");
        // 普通结算通常只有一个资产，先算好全部新值，再调用 setter，避免为加法预检重复读取。
        if (size == 1) {
            int assetId = (assetIds == null ? firstAssetId : assetIds[0]);
            long fee = Math.addExact(treasury.fee(assetId), (feeUnits == null ? firstFeeUnits : feeUnits[0]));
            long insurance = Math.addExact(treasury.insurance(assetId), (insuranceUnits == null ? firstInsuranceUnits : insuranceUnits[0]));
            long deficit = Math.addExact(treasury.insuranceDeficit(assetId), (deficitUnits == null ? firstDeficitUnits : deficitUnits[0]));
            long fundingResidual = Math.addExact(treasury.fundingResidual(assetId), (fundingResidualUnits == null ? firstFundingResidualUnits : fundingResidualUnits[0]));
            long roundingResidual = Math.addExact(treasury.roundingResidual(assetId), (roundingResidualUnits == null ? firstRoundingResidualUnits : roundingResidualUnits[0]));
            long clearing = Math.addExact(treasury.clearingPnl(assetId), (clearingUnits == null ? firstClearingUnits : clearingUnits[0]));
            treasury.setFee(assetId, fee);
            treasury.setInsurance(assetId, insurance, deficit);
            treasury.setFundingResidual(assetId, fundingResidual);
            treasury.setRoundingResidual(assetId, roundingResidual);
            treasury.setClearingPnl(assetId, clearing);
            return;
        }
        for (int index = 0; index < size; index++) {
            int assetId = assetIds[index];
            Math.addExact(treasury.fee(assetId), feeUnits[index]);
            Math.addExact(treasury.insurance(assetId), insuranceUnits[index]);
            Math.addExact(treasury.insuranceDeficit(assetId), deficitUnits[index]);
            Math.addExact(treasury.fundingResidual(assetId), fundingResidualUnits[index]);
            Math.addExact(treasury.roundingResidual(assetId), roundingResidualUnits[index]);
            Math.addExact(treasury.clearingPnl(assetId), clearingUnits[index]);
        }
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

    /** 把有效资产数归零并保留标量和数组容量，下一代登记资产时重新初始化对应有效槽。 */
    public void clear() {
        size = 0;
    }

    /** 查找已有资产项，否则检查固定容量并追加。 */
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
        for (int index = 0; index < size; index++) if ((assetIds == null ? firstAssetId : assetIds[index]) == assetId) return index;
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

}
