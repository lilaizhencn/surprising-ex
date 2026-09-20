package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;

/** Owns direct runtime treasury insurance-fund adjustments. */
public final class RuntimeInsuranceFundStateTransitions {

    private RuntimeInsuranceFundStateTransitions() {
    }

    public static void adjust(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                       AdjustInsuranceFundCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime insurance adjustment");
        }
        runtime.assertOwner();
        int assetId = identities.assetId(command.asset());
        long current = runtime.treasury().insurance(assetId);
        if (command.deltaUnits() < 0 && Math.negateExact(command.deltaUnits()) > current) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "insurance fund balance is insufficient");
        }
        runtime.treasury().adjustInsurance(assetId, command.deltaUnits());
        runtime.incrementCommandRevision();
    }
}
