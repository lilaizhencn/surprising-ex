package com.surprising.aeron.service.treasury;

import lombok.Getter;
import lombok.experimental.Accessors;
/**
 * 所属命令或 Lane 的系统资金差额，按资产归集费用、保险、亏空、资金费残差、舍入及清算。单资产先用标量，第二个资产才分配固定容量数组；clear 只重置有效长度。差额不等于权威余额，应用时仍须持有原 Treasury 的单写者所有权。
 */
public final class RuntimeTreasuryDelta {
    /** 普通命令默认允许的四个不同资产槽位上限。 */
    public static final int SINGLE_COMMAND_CAPACITY = 4;

    /** 本对象固定允许的不同资产项数上限。 */
    private final int capacity;
    /** 尚未创建数组时首个资产的编号。 */
    private int firstAssetId;
    /** 单资产标量手续费差额。 */
    private long firstFeeUnits;
    /** 单资产标量保险基金差额。 */
    private long firstInsuranceUnits;
    /** 单资产标量保险亏空差额。 */
    private long firstDeficitUnits;
    /** 单资产标量资金费残差。 */
    private long firstFundingResidualUnits;
    /** 单资产标量舍入残差。 */
    private long firstRoundingResidualUnits;
    /** 单资产标量清算盈亏差额。 */
    private long firstClearingUnits;
    /** 多资产时的资产编号列，首次升到第二个资产才创建。 */
    private int[] assetIds;
    /** 多资产手续费差额列。 */
    private long[] feeUnits;
    /** 多资产保险基金差额列。 */
    private long[] insuranceUnits;
    /** 多资产保险亏空差额列。 */
    private long[] deficitUnits;
    /** 多资产资金费残差列。 */
    private long[] fundingResidualUnits;
    /** 多资产舍入残差列。 */
    private long[] roundingResidualUnits;
    /** 多资产清算盈亏差额列。 */
    private long[] clearingUnits;
    /**  本代实际登记的资产项数，金额归零不会自动移除资产项。 返回本代有效资产项数，不表示金额非零项数或数组容量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int size;

    /**
     * 以普通命令四个资产槽的容量创建差额，尚不分配多资产数组。
     */
    public RuntimeTreasuryDelta() {
        this(SINGLE_COMMAND_CAPACITY);
    }

    /**
     * 保存正数固定资产容量，后续超容量拒绝而不是自动扩展。
     * @param capacity 固定资产项容量，必须为正
     */
    public RuntimeTreasuryDelta(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("treasury delta capacity must be positive");
        this.capacity = capacity;
    }

    /**
     * 校验当前有效下标后读取对应资产编号。
     * @param index 本代有效资产槽下标
     */
    public int assetId(int index) {
        checkIndex(index);
        return (assetIds == null ? firstAssetId : assetIds[index]);
    }

    /**
     * 校验下标后读取该资产手续费变化。
     * @param index 本代有效资产槽下标
     */
    public long feeUnits(int index) {
        checkIndex(index);
        return (feeUnits == null ? firstFeeUnits : feeUnits[index]);
    }

    /**
     * 校验下标后读取该资产清算盈亏变化。
     * @param index 本代有效资产槽下标
     */
    public long clearingUnits(int index) {
        checkIndex(index);
        return (clearingUnits == null ? firstClearingUnits : clearingUnits[index]);
    }

    /**
     * 找到或登记资产项，以精确加法增加该项系统手续费差额，零金额也会登记资产。
     * @param assetId 需要读取或累加的非负资产编号
     * @param units 需要精确累加的有符号变化金额
     */
    public void addFee(int assetId, long units) {
        // 当前遍历、归并、预检或写入的有效资产槽下标。
        int index = entry(assetId);
        if (feeUnits == null) firstFeeUnits = Math.addExact(firstFeeUnits, units);
        else feeUnits[index] = Math.addExact(feeUnits[index], units);
    }

    /**
     * 找到或登记资产项，以精确加法增加保险基金差额。
     * @param assetId 需要读取或累加的非负资产编号
     * @param units 需要精确累加的有符号变化金额
     */
    public void addInsurance(int assetId, long units) {
        // 当前遍历、归并、预检或写入的有效资产槽下标。
        int index = entry(assetId);
        if (insuranceUnits == null) firstInsuranceUnits = Math.addExact(firstInsuranceUnits, units);
        else insuranceUnits[index] = Math.addExact(insuranceUnits[index], units);
    }

    /**
     * 找到或登记资产项，以精确加法增加保险亏空差额。
     * @param assetId 需要读取或累加的非负资产编号
     * @param units 需要精确累加的有符号变化金额
     */
    public void addDeficit(int assetId, long units) {
        // 当前遍历、归并、预检或写入的有效资产槽下标。
        int index = entry(assetId);
        if (deficitUnits == null) firstDeficitUnits = Math.addExact(firstDeficitUnits, units);
        else deficitUnits[index] = Math.addExact(deficitUnits[index], units);
    }

    /**
     * 找到或登记资产项，以精确加法增加资金费残差。
     * @param assetId 需要读取或累加的非负资产编号
     * @param units 需要精确累加的有符号变化金额
     */
    public void addFundingResidual(int assetId, long units) {
        // 当前遍历、归并、预检或写入的有效资产槽下标。
        int index = entry(assetId);
        if (fundingResidualUnits == null) {
            firstFundingResidualUnits = Math.addExact(firstFundingResidualUnits, units);
        } else {
            fundingResidualUnits[index] = Math.addExact(fundingResidualUnits[index], units);
        }
    }

    /**
     * 找到或登记资产项，以精确加法增加舍入残差。
     * @param assetId 需要读取或累加的非负资产编号
     * @param units 需要精确累加的有符号变化金额
     */
    public void addRoundingResidual(int assetId, long units) {
        // 当前遍历、归并、预检或写入的有效资产槽下标。
        int index = entry(assetId);
        if (roundingResidualUnits == null) {
            firstRoundingResidualUnits = Math.addExact(firstRoundingResidualUnits, units);
        } else {
            roundingResidualUnits[index] = Math.addExact(roundingResidualUnits[index], units);
        }
    }

    /**
     * 找到或登记资产项，以精确加法增加系统清算盈亏差额。
     * @param assetId 需要读取或累加的非负资产编号
     * @param units 需要精确累加的有符号变化金额
     */
    public void addClearing(int assetId, long units) {
        // 当前遍历、归并、预检或写入的有效资产槽下标。
        int index = entry(assetId);
        if (clearingUnits == null) firstClearingUnits = Math.addExact(firstClearingUnits, units);
        else clearingUnits[index] = Math.addExact(clearingUnits[index], units);
    }

    /**
     * 逐资产逐金额列合并另一差额，不清空源；途中异常不提供整组自动回滚。
     * @param other 需要合入的非空系统差额
     */
    public void merge(RuntimeTreasuryDelta other) {
        if (other == null) throw new IllegalArgumentException("treasury delta is required");
        for (/* 当前遍历、归并、预检或写入的有效资产槽下标。 */ int index = 0; index < other.size; index++) {
            // 当前需要应用差额或查找归并的资产编号。
            int assetId = (other.assetIds == null ? other.firstAssetId : other.assetIds[index]);
            addFee(assetId, (other.feeUnits == null ? other.firstFeeUnits : other.feeUnits[index]));
            addInsurance(assetId, (other.insuranceUnits == null ? other.firstInsuranceUnits : other.insuranceUnits[index]));
            addDeficit(assetId, (other.deficitUnits == null ? other.firstDeficitUnits : other.deficitUnits[index]));
            addFundingResidual(assetId, (other.fundingResidualUnits == null ? other.firstFundingResidualUnits : other.fundingResidualUnits[index]));
            addRoundingResidual(assetId, (other.roundingResidualUnits == null ? other.firstRoundingResidualUnits : other.roundingResidualUnits[index]));
            addClearing(assetId, (other.clearingUnits == null ? other.firstClearingUnits : other.clearingUnits[index]));
        }
    }

    /**
     * 在财库单写者边界先检查所有资产全部列的加法溢出，再调用原 Treasury 更新。单资产把新值保存在局部，多资产先预检再写；这项保证针对加法溢出，不替代各 setter 的业务合法性检查或外层回滚。
     * @param treasury 由当前执行线程持有所有权的原系统财库
     */
    public void apply(TreasuryRuntime treasury) {
        if (treasury == null) throw new IllegalArgumentException("treasury is required");
        // 普通结算通常只有一个资产，先算好全部新值，再调用 setter，避免为加法预检重复读取。
        if (size == 1) {
            // 当前需要应用差额或查找归并的资产编号。
            int assetId = (assetIds == null ? firstAssetId : assetIds[0]);
            // 单资产路径预先计算的更新后系统手续费余额。
            long fee = Math.addExact(treasury.fee(assetId), (feeUnits == null ? firstFeeUnits : feeUnits[0]));
            // 单资产路径预先计算的更新后保险基金余额。
            long insurance = Math.addExact(treasury.insurance(assetId), (insuranceUnits == null ? firstInsuranceUnits : insuranceUnits[0]));
            // 单资产路径预先计算的更新后保险亏空余额。
            long deficit = Math.addExact(treasury.insuranceDeficit(assetId), (deficitUnits == null ? firstDeficitUnits : deficitUnits[0]));
            // 单资产路径预先计算的更新后资金费残差余额。
            long fundingResidual = Math.addExact(treasury.fundingResidual(assetId), (fundingResidualUnits == null ? firstFundingResidualUnits : fundingResidualUnits[0]));
            // 单资产路径预先计算的更新后舍入残差余额。
            long roundingResidual = Math.addExact(treasury.roundingResidual(assetId), (roundingResidualUnits == null ? firstRoundingResidualUnits : roundingResidualUnits[0]));
            // 单资产路径预先计算的更新后清算盈亏余额。
            long clearing = Math.addExact(treasury.clearingPnl(assetId), (clearingUnits == null ? firstClearingUnits : clearingUnits[0]));
            treasury.setFee(assetId, fee);
            treasury.setInsurance(assetId, insurance, deficit);
            treasury.setFundingResidual(assetId, fundingResidual);
            treasury.setRoundingResidual(assetId, roundingResidual);
            treasury.setClearingPnl(assetId, clearing);
            return;
        }
        // 同一账本所有者执行预检和写入两遍，任何资产加法溢出都在首个资产写入前拒绝。
        for (/* 当前遍历、归并、预检或写入的有效资产槽下标。 */ int index = 0; index < size; index++) {
            // 当前需要应用差额或查找归并的资产编号。
            int assetId = assetIds[index];
            Math.addExact(treasury.fee(assetId), feeUnits[index]);
            Math.addExact(treasury.insurance(assetId), insuranceUnits[index]);
            Math.addExact(treasury.insuranceDeficit(assetId), deficitUnits[index]);
            Math.addExact(treasury.fundingResidual(assetId), fundingResidualUnits[index]);
            Math.addExact(treasury.roundingResidual(assetId), roundingResidualUnits[index]);
            Math.addExact(treasury.clearingPnl(assetId), clearingUnits[index]);
        }
        for (/* 当前遍历、归并、预检或写入的有效资产槽下标。 */ int index = 0; index < size; index++) {
            // 当前需要应用差额或查找归并的资产编号。
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

    /**
     * 把有效资产数归零并保留标量和数组容量，下一代登记资产时重新初始化对应有效槽。
     */
    public void clear() {
        size = 0;
    }

    /**
     * 查找已有资产项，否则检查固定容量并追加；从单资产升为多资产时建立数组并迁入首项。
     * @param assetId 需要读取或累加的非负资产编号
     */
    private int entry(int assetId) {
        if (assetId < 0) throw new IllegalArgumentException("assetId must be non-negative");
        // 当前资产已登记的有效下标，负一表示需要追加。
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

    /**
     * 在本代有效资产前缀线性查找编号，未找到返回负一。
     * @param assetId 需要读取或累加的非负资产编号
     */
    private int indexOf(int assetId) {
        for (/* 当前遍历、归并、预检或写入的有效资产槽下标。 */ int index = 0; index < size; index++) if ((assetIds == null ? firstAssetId : assetIds[index]) == assetId) return index;
        return -1;
    }

    /**
     * 首次需要多资产时按固定容量创建所有列，已有首个标量资产则复制到零槽，之后一直复用数组。
     */
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

    /**
     * 拒绝负数或超出本代有效资产前缀的下标。
     * @param index 本代有效资产槽下标
     */
    private void checkIndex(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
    }
}
