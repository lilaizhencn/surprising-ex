package com.surprising.aeron.service.fee;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreFeePolicyState;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 不可变费用政策版本：更新时复制数组并按政策自身比较规则排序，已有命令继续持有原版本。查询只从已排序数组选择首条生效政策。
 */
public final class FeePolicyBook {
    /** 没有任何政策时共享的不可变空版本。 */
    public static final FeePolicyBook EMPTY = new FeePolicyBook(new CoreFeePolicyState[0]);
    /** 本版本独占且构造后不再修改的已排序政策数组。 */
    private final CoreFeePolicyState[] policies;

    /**
     * 接收本类独占的新数组并原地排序；公开构造路径不把旧版本数组拿来修改。
     * @param policies 本类独占的新政策数组，构造时允许原地排序
     */
    private FeePolicyBook(CoreFeePolicyState[] policies) {
        this.policies = policies;
        Arrays.sort(policies);
    }

    /**
     * 校验恢复映射非空且每个键与政策身份一致，空映射复用 EMPTY，其他情况从值创建独立数组。
     * @param values 需要恢复的政策 ID 到状态映射
     */
    public static FeePolicyBook from(Map<Long, CoreFeePolicyState> values) {
        if (values == null) throw new IllegalArgumentException("fee policies are required");
        values.forEach((/* 恢复映射当前政策键，必须与政策自身 ID 相同 */ id, /* 当前校验、选择或导出的不可变政策值 */ policy) -> {
            if (policy == null || id == null || id != policy.policyId())
                throw new IllegalArgumentException("fee policy identity differs from its key");
        });
        return values.isEmpty() ? EMPTY : new FeePolicyBook(values.values().toArray(CoreFeePolicyState[]::new));
    }

    /**
     * 查相同政策 ID：旧修订拒绝，同修订不同内容冲突，完全相同复用当前版本；新身份或更高修订复制数组后建立新排序版本。调用方提供非空政策。
     * @param next 需要新增或更新的非空政策状态
     */
    public FeePolicyBook upsert(CoreFeePolicyState next) {
        // 同政策 ID 的原数组下标，负一表示需要追加新身份
        int found = -1;
        for (/* 当前查政策身份的原数组下标 */ int i = 0; i < policies.length; i++) {
            // 已找到的原政策，用于比较修订和完整内容
            CoreFeePolicyState current = policies[i];
            if (current.policyId() != next.policyId()) continue;
            if (next.policyRevision() < current.policyRevision())
                throw new CoreStateRejectedException("STALE_FEE_POLICY_VERSION", "fee policy version must increase");
            if (next.policyRevision() == current.policyRevision()) {
                if (!current.equals(next))
                    throw new CoreStateRejectedException("FEE_POLICY_VERSION_CONFLICT", "fee policy version contains different data");
                return this;
            }
            found = i;
            break;
        }
        // 从旧版本复制出的新独占数组，随后排序，不改原版本
        CoreFeePolicyState[] updated = Arrays.copyOf(policies, policies.length + (found < 0 ? 1 : 0));
        updated[found < 0 ? policies.length : found] = next;
        return new FeePolicyBook(updated);
    }

    /**
     * 按已固定的数组顺序找第一条满足用户、标的和业务时间的生效政策，没有匹配返回空。
     * @param userId 本次费用判断所属用户
     * @param instrumentId 本次费用判断的外部标的 ID
     * @param clusterTimestamp 本次政策生效判断使用的原业务时间
     */
    public CoreFeePolicyState select(long userId, String instrumentId, long clusterTimestamp) {
        for (CoreFeePolicyState /* 当前校验、选择或导出的不可变政策值 */ policy : policies)
            if (policy.effective(userId, instrumentId, clusterTimestamp)) return policy;
        return null;
    }

    /**
     * 按政策 ID 建立有序只读映射副本，政策值本身继续复用不可变对象。
     */
    public Map<Long, CoreFeePolicyState> snapshot() {
        // 本次新建的按政策 ID 排序映射，最后包装为只读
        TreeMap<Long, CoreFeePolicyState> values = new TreeMap<>();
        for (CoreFeePolicyState /* 当前校验、选择或导出的不可变政策值 */ policy : policies) values.put(policy.policyId(), policy);
        return Collections.unmodifiableMap(values);
    }
}
