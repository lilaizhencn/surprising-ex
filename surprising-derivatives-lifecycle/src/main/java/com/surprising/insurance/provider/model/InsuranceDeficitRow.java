package com.surprising.insurance.provider.model;

public record InsuranceDeficitRow(
        String accountType,
        long userId,
        String asset,
        long deficitUnits) {
}
