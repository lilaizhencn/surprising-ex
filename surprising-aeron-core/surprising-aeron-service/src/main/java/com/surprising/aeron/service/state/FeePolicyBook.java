package com.surprising.aeron.service.state;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreFeePolicyState;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Canonical immutable fee configuration; a command retains the version preceding its log entry. */
public final class FeePolicyBook {
    public static final FeePolicyBook EMPTY = new FeePolicyBook(new CoreFeePolicyState[0]);
    private final CoreFeePolicyState[] policies;

    private FeePolicyBook(CoreFeePolicyState[] policies) {
        this.policies = policies;
        Arrays.sort(policies);
    }

    public static FeePolicyBook from(Map<Long, CoreFeePolicyState> values) {
        if (values == null) throw new IllegalArgumentException("fee policies are required");
        values.forEach((id, policy) -> {
            if (policy == null || id == null || id != policy.policyId())
                throw new IllegalArgumentException("fee policy identity differs from its key");
        });
        return values.isEmpty() ? EMPTY : new FeePolicyBook(values.values().toArray(CoreFeePolicyState[]::new));
    }

    FeePolicyBook upsert(CoreFeePolicyState next) {
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

    /** Priority is sorted once per configuration update; no wrapper or iterator per order. */
    public CoreFeePolicyState select(long userId, String instrumentId, long clusterTimestamp) {
        for (CoreFeePolicyState policy : policies)
            if (policy.effective(userId, instrumentId, clusterTimestamp)) return policy;
        return null;
    }

    Map<Long, CoreFeePolicyState> snapshot() {
        TreeMap<Long, CoreFeePolicyState> values = new TreeMap<>();
        for (CoreFeePolicyState policy : policies) values.put(policy.policyId(), policy);
        return Collections.unmodifiableMap(values);
    }
}
