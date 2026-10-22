package com.surprising.risk.provider.controller;

import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.utils.AdminCursorPage;
import com.surprising.risk.provider.model.LiquidationCandidatePageQuery;
import com.surprising.risk.provider.model.RiskRuleModels.RiskRuleUpdateCommand;
import java.util.Locale;

/** 风控两个HTTP入口共同的输入格式绑定；策略所有权、Core版本冲突和默认值归Service。 */
public final class RiskRequestBinding {
    private RiskRequestBinding() { }

    public static long userId(long value) {
        if (value <= 0) throw new IllegalArgumentException("userId must be positive");
        return value;
    }

    public static String accountType(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public static LiquidationCandidatePageQuery candidates(String status, int limit, String cursor, String sort) {
        LiquidationCandidateStatus requested = LiquidationCandidateStatus.valueOf(requireText(status, "status").toUpperCase(Locale.ROOT));
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        String order = sort == null || sort.isBlank() ? "eventTime.asc" : sort.trim();
        if (!order.equals("eventTime.asc") && !order.equals("eventTime.desc")) {
            throw new IllegalArgumentException("unsupported sort: " + sort);
        }
        var after = AdminCursorPage.decodeCursor(cursor);
        return new LiquidationCandidatePageQuery(requested, limit, after == null ? null : after.id(), order.equals("eventTime.desc"));
    }

    public static RiskRuleUpdateCommand command(RiskRuleUpdateCommand request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        String reason = requireText(request.reason(), "reason");
        if (reason.length() > 500) throw new IllegalArgumentException("reason must be at most 500 characters");
        if (request.expectedVersion() != null && request.expectedVersion() <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        if (request.scanDelayMs() != null && request.scanDelayMs() < 0) {
            throw new IllegalArgumentException("scanDelayMs must be non-negative");
        }
        if (request.scanBatchSize() != null && (request.scanBatchSize() < 1 || request.scanBatchSize() > 4096)) {
            throw new IllegalArgumentException("scanBatchSize must be between 1 and 4096");
        }
        return new RiskRuleUpdateCommand(request.expectedVersion(), ruleName(request.ruleName(), null), request.enabled(),
                request.scanDelayMs(), request.scanBatchSize(), reason);
    }

    public static String asset(String value) {
        String normalized = requireText(value, "settleAsset").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{2,20}")) throw new IllegalArgumentException("invalid settleAsset");
        return normalized;
    }

    public static String ruleCode(String value) {
        String normalized = requireText(value, "ruleCode").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{2,96}")) throw new IllegalArgumentException("invalid ruleCode");
        return normalized;
    }

    private static String ruleName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        if (normalized.length() > 120) throw new IllegalArgumentException("ruleName must be at most 120 characters");
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

}
