package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.FundsPosting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

/**
 * 以只读接口提供的一组资金变化及逐资产合计，用于结算守恒核对和提取 Treasury 差额。可信构造路径包装原输入列表，不是总会防御复制；调用方必须停止修改该列表，否则分录与预计算合计不再对应。本对象不直接更新账户或账本余额。
 */
public final class RuntimeFundsDelta {

    /** 空资金变化共用实例，不保存资产或分录项。 */
    private static final RuntimeFundsDelta EMPTY = new RuntimeFundsDelta(List.of(), false, true);
    /**  实际保存的只读分录列表，可信路径包装原列表，其他路径复制归并准备列。 借用实际保存的只读列表；可信路径底层仍是调用方原列表，不能在构造后从外部修改。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final List<Posting> postings;
    /** 按构造时实际选中的源列表计算的逐资产有符号金额合计。 */
    private final IntLongHashMap unitsByAsset;
    /** 构造期实际出现资产的首次遇见顺序，供守恒核对遍历。 */
    private final int[] assetIds;

    /**
     * 以 normalize 与 trusted 同时为 true 进入完整构造；当前可信分支实际保存及求和使用原输入列表，而不是归并准备列表。
     * @param postings 调用方提供的非空分录列表，可信分支要求之后不再修改
     */
    RuntimeFundsDelta(List<Posting> postings) {
        this(postings, true, true);
    }

    /**
     * 按开关检查并准备同键归并或复制列，再建立逐资产合计和资产清单；trusted 为 true 时最终源和只读包装均选原列表，不能把 normalize 开关单独当作输出必已归并的保证。
     * @param postings 调用方提供的非空分录列表，可信分支要求之后不再修改
     * @param normalize 是否执行同键归并准备，最终是否采用该准备列还由 trusted 决定
     * @param trusted 是否保留并只读包装原列表，true 时总额也按原列表计算
     */
    private RuntimeFundsDelta(List<Posting> postings,
                              boolean normalize, boolean trusted) {
        if (postings == null) throw new IllegalArgumentException("runtime funds postings are required");
        // 按 normalize 分支准备的归并或复制列表；trusted 路径最终不选它作为保存和求和源。
        ArrayList<Posting> normalizedPostings;
        if (normalize) {
            normalizedPostings = new ArrayList<>(postings.size());
            for (Posting /* 当前检查、归并、求和或提取系统差额的分录。 */ posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
                // 归并准备列中相同业务键的已有下标，负一表示尚未遇到。
                int existingIndex = -1;
                for (/* 在归并准备列查找同键分录的当前下标。 */ int index = 0; index < normalizedPostings.size(); index++) {
                    if (samePostingKey(normalizedPostings.get(index), posting)) {
                        existingIndex = index;
                        break;
                    }
                }
                if (existingIndex < 0) {
                    normalizedPostings.add(posting);
                    continue;
                }
                // 归并准备列中与当前分录同键的已有值。
                Posting existing = normalizedPostings.get(existingIndex);
                // 当前同键合并后的金额，或当前资产待核对合计，按所在步骤区分。
                long units = Math.addExact(existing.units(), posting.units());
                if (units == 0) normalizedPostings.remove(existingIndex);
                else normalizedPostings.set(existingIndex, new Posting(
                        existing.assetId(), existing.ownerKind(), existing.ownerId(), existing.subledger(), units));
            }
        } else if (trusted) {
            for (Posting /* 当前检查、归并、求和或提取系统差额的分录。 */ posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
            }
            normalizedPostings = null;
        } else {
            normalizedPostings = new ArrayList<>(postings.size());
            for (Posting /* 当前检查、归并、求和或提取系统差额的分录。 */ posting : postings) {
                if (posting == null) throw new IllegalArgumentException("runtime funds posting is required");
                normalizedPostings.add(posting);
            }
        }
        // 按最终选择源列表精确累加的逐资产金额表。
        IntLongHashMap totals = new IntLongHashMap();
        // 保存首次出现资产编号的临时原语数组。
        int[] touchedAssets = new int[Math.max(1, trusted ? postings.size() : normalizedPostings.size())];
        // 当前已登记的不同资产编号数量。
        int touchedAssetCount = 0;
        // 真正用于总额及资产清单构建的列表，trusted 为 true 时取原输入。
        List<Posting> source = trusted ? postings : normalizedPostings;
        for (Posting /* 当前检查、归并、求和或提取系统差额的分录。 */ posting : source) {
            if (!totals.containsKey(posting.assetId())) touchedAssets[touchedAssetCount++] = posting.assetId();
            // 当前资产在累加本条分录前已有的金额合计。
            long previous = totals.get(posting.assetId());
            totals.put(posting.assetId(), Math.addExact(previous, posting.units()));
        }
        this.postings = trusted ? Collections.unmodifiableList(postings) : List.copyOf(normalizedPostings);
        this.unitsByAsset = totals;
        this.assetIds = java.util.Arrays.copyOf(touchedAssets, touchedAssetCount);
    }

    /**
     * 比较资产、所有者类别、所有者编号及子账四个业务键是否一致，不比较金额。
     * @param left 需要比较业务键的左侧分录
     * @param right 需要比较业务键的右侧分录
     */
    private static boolean samePostingKey(Posting left, Posting right) {
        return left.assetId() == right.assetId()
                && left.ownerKind() == right.ownerKind()
                && left.ownerId() == right.ownerId()
                && left.subledger() == right.subledger();
    }

    /**
     * 返回没有分录和资产合计的共用空值。
     */
    public static RuntimeFundsDelta empty() {
        return EMPTY;
    }

    /**
     * 用于调用方已经按业务键去重的分录列表，跳过去重准备；本方法只检查非空分录，不自行证明键唯一。
     * @param postings 调用方提供的非空分录列表，可信分支要求之后不再修改
     */
    static RuntimeFundsDelta fromDistinct(List<Posting> postings) {
        return postings.isEmpty() ? EMPTY : new RuntimeFundsDelta(postings, false, true);
    }

    /**
     * 空侧复用已有值，其他情况通过原语累加器合并两侧分录并物化结果，不修改任一输入列表。
     * @param other 需要合并的另一资金变化，空引用或空值不新增结果
     */
    public RuntimeFundsDelta plus(RuntimeFundsDelta other) {
        if (other == null || other.postings.isEmpty()) return this;
        if (postings.isEmpty()) return other;
        // 合并两组分录时使用的独立原语资金缓冲。
        RuntimeFundsAccumulator accumulator = new RuntimeFundsAccumulator(postings.size() + other.postings.size());
        accumulator.add(this);
        accumulator.add(other);
        return accumulator.toDelta();
    }

    /**
     * 返回实际保存分录列表的项数，可信默认构造可能仍含同键原始项。
     */
    public int postingCount() {
        return postings.size();
    }

    /**
     * 非外部调整时检查构造期逐资产合计必须为零；外部调整开关开启则直接跳过，不重新遍历分录。
     * @param externalAdjustment 是否为外部资金调整，true 时跳过逐资产合计为零检查
     */
    public void requireConserved(boolean externalAdjustment) {
        if (externalAdjustment) return;
        for (int /* 逐资产守恒检查当前读取的资产编号。 */ assetId : assetIds) {
            // 当前同键合并后的金额，或当前资产待核对合计，按所在步骤区分。
            long units = unitsByAsset.get(assetId);
            if (units != 0) {
                throw new IllegalArgumentException("runtime funds delta is not conserved for asset " + assetId);
            }
        }
    }

    /**
     * 仅提取 Treasury 所有者分录，费用、保险、资金费残差、舍入及清算分别归集，亏空取反号；LIQUIDATION_FEE 在此沿现有规则计入保险，非法 Treasury 子账拒绝。
     */
    public RuntimeTreasuryDelta treasuryDelta() {
        // 从 Treasury 分录提取的可复用结构财务差额，尚未应用到真实财库。
        RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta(Math.max(
                RuntimeTreasuryDelta.SINGLE_COMMAND_CAPACITY, unitsByAsset.size()));
        for (Posting /* 当前检查、归并、求和或提取系统差额的分录。 */ posting : postings) {
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

    /**
     * 使用运行时资产编号的一条非零资金变化值，只检查基本字段，不承担完整所有者组合或资金守恒校验。
     * @param assetId 本条分录的非负资产编号
     * @param ownerKind 本条分录的所有者类别
     * @param ownerId 本条分录的所有者编号，组合有效性由外层业务保证
     * @param subledger 本条资金变化的子账类别
     * @param units 本条分录有符号金额，非零
     */
    record Posting(int assetId, FundsPosting.OwnerKind ownerKind, long ownerId,
                   FundsPosting.Subledger subledger, long units) {
        /**
         * 检查资产非负、类别及子账非空、金额非零；这里不校验所有者编号与类别或子账之间的组合。
         */
        Posting {
            if (assetId < 0 || ownerKind == null || subledger == null || units == 0) {
                throw new IllegalArgumentException("invalid runtime funds posting");
            }
        }

    }
}
