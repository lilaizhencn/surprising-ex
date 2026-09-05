package com.surprising.aeron.service.state;

final class OptionRiskRules {

    private OptionRiskRules() {
    }

    static void requireOptionRiskPrices(CoreInstrumentState instrument, long indexPriceTicks,
                                                long forwardPriceTicks) {
        if (instrument.contractType().isOption() && (indexPriceTicks <= 0 || forwardPriceTicks <= 0)) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option mark requires index and same-expiry forward prices");
        }
    }
}
