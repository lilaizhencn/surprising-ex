package com.surprising.aeron.service.fee;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreFeePolicyState;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 不可变费用政策版本：更新时复制数组并按政策自身比较规则排序，已有命令继续持有原版本。 */
public final class FeePolicyBook {
    /** 没有任何政策时共享的不可变空版本。 */
    public static final FeePolicyBook EMPTY = new FeePolicyBook(new CoreFeePolicyState[0]);
    private final CoreFeePolicyState[] policies;

    private FeePolicyBook(CoreFeePolicyState[] policies) {
        this.policies = policies;
        Arrays.sort(policies);
    }

    /** 校验恢复映射非空且每个键与政策身份一致，空映射复用 EMPTY，其他情况从值创建独立数组。 */
    public static FeePolicyBook from(Map<Long, CoreFeePolicyState> values) {
        if (values == null) throw new IllegalArgumentException("fee policies are required");
        values.forEach((id, policy) -> {
            if (policy == null || id == null || id != policy.policyId())
                throw new IllegalArgumentException("fee policy identity differs from its key");
        });
        return values.isEmpty() ? EMPTY : new FeePolicyBook(values.values().toArray(CoreFeePolicyState[]::new));
    }

    /** 查相同政策 ID：旧修订拒绝，同修订不同内容冲突，完全相同复用当前版本。 */
    public FeePolicyBook upsert(CoreFeePolicyState next) {
        int found = -1;
        for (int i = 0; i < policies.length; i++) {
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
        CoreFeePolicyState[] updated = Arrays.copyOf(policies, policies.length + (found < 0 ? 1 : 0));
        updated[found < 0 ? policies.length : found] = next;
        return new FeePolicyBook(updated);
    }

    /** 按已固定的数组顺序找第一条满足用户、标的和业务时间的生效政策，没有匹配返回空。 */
    public CoreFeePolicyState select(long userId, String instrumentId, long clusterTimestamp) {
        for (CoreFeePolicyState policy : policies)
            if (policy.effective(userId, instrumentId, clusterTimestamp)) return policy;
        return null;
    }

    /** 按政策 ID 建立有序只读映射副本，政策值本身继续复用不可变对象。 */
    public Map<Long, CoreFeePolicyState> snapshot() {
        TreeMap<Long, CoreFeePolicyState> values = new TreeMap<>();
        for (CoreFeePolicyState policy : policies) values.put(policy.policyId(), policy);
        return Collections.unmodifiableMap(values);
    }
}
