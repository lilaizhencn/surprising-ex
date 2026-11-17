package com.surprising.gateway.provider.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FundCheckResult(
        boolean healthy,
        long totalAccountsChecked,
        long negativeBalanceCount,
        long pendingWithdrawalsCount,
        BigDecimal totalDiscrepancyUsdt,
        List<String> discrepancies,
        Instant executionTime,
        long durationMs
) {
    public static FundCheckResult healthy(long totalAccounts, long pendingWithdrawals, long durationMs) {
        return new FundCheckResult(
                true,
                totalAccounts,
                0,
                pendingWithdrawals,
                BigDecimal.ZERO,
                List.of(),
                Instant.now(),
                durationMs
        );
    }

    public static FundCheckResult anomaly(long totalAccounts, long negativeBalances, long pendingWithdrawals,
                                          BigDecimal discrepancy, List<String> issues, long durationMs) {
        return new FundCheckResult(
                false,
                totalAccounts,
                negativeBalances,
                pendingWithdrawals,
                discrepancy,
                issues,
                Instant.now(),
                durationMs
        );
    }
}
