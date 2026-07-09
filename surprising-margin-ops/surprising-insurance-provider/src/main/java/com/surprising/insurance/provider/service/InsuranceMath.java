package com.surprising.insurance.provider.service;

public final class InsuranceMath {

    private InsuranceMath() {
    }

    public static long coverAmount(long deficitUnits, long fundBalanceUnits) {
        if (deficitUnits <= 0 || fundBalanceUnits <= 0) {
            return 0L;
        }
        return Math.min(deficitUnits, fundBalanceUnits);
    }
}
