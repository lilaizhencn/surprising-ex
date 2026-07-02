package com.surprising.account.provider.model;

public record BalanceDebitResult(
        long debitedUnits,
        long balanceAfterUnits) {
}
