package com.surprising.aeron.service.state.admission;

/** Identity values and the borrowed immutable Ledger fact fixed before Account Lane admission. */
public record AdmissionIdentity(
        long clientKey, int symbolId, long positionKey,
        com.surprising.aeron.service.state.TreasuryRuntime.SettlementStatus settlementStatus) {
    public boolean lifecycleSettled() { return settlementStatus.lifecycleSettled(); }
    public boolean fundingInProgress() { return settlementStatus.fundingInProgress(); }
}
