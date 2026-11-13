package com.surprising.risk.provider.model;

import java.time.Instant;
import java.util.List;

/** 风控规则的共享请求及业务视图；Core仍是版本与策略的权威来源。 */
public final class RiskRuleModels {
    private RiskRuleModels() { }

    public record RiskRulesResponse(int ruleCount, List<RiskRuleResponse> rules) {}
    public record RiskRuleResponse(String ruleCode, String ruleName, String ruleType, Long version, boolean enabled,
                                   Long scanDelayMs,
                                   Integer scanBatchSize, String source, String adminUserId, String reason,
                                   Instant updatedAt) {}
    public record RiskRuleUpdateCommand(Long expectedVersion, String ruleName, Boolean enabled,
                                        Long scanDelayMs, Integer scanBatchSize, String reason) {}
}
