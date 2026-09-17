package com.surprising.aeron.service.state.admission;

/** Primitive identity values prepared once before an account-lane admission handoff. */
public record AdmissionIdentity(
        long clientKey, int symbolId, long positionKey, boolean lifecycleSettled, boolean fundingInProgress) {
}
