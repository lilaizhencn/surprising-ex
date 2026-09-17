package com.surprising.aeron.service.business.option;

import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.CoreStateRejectedException;

/** Validates the risk prices required by option marking and risk checks. */
public final class OptionRiskRules {
    private OptionRiskRules() {
    }

    public static void requireOptionRiskPrices(CoreInstrumentState instrument, long indexPriceTicks,
                                               long forwardPriceTicks) {
        if (instrument.contractType().isOption() && (indexPriceTicks <= 0 || forwardPriceTicks <= 0)) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option mark requires index and same-expiry forward prices");
        }
    }
}
