package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

final class CoreStateTestFixtures {

    private CoreStateTestFixtures() {
    }

    static TradingCoreState withInstrument(TradingCoreReducer reducer, ProductLine productLine) {
        return reducer.upsertInstrument(TradingCoreState.empty(productLine), instrument(productLine,
                "BTC-USDT", "BTC", "USDT", settleAsset(productLine), 1));
    }

    static UpsertInstrumentCommand instrument(
            ProductLine productLine,
            String symbol,
            String baseAsset,
            String quoteAsset,
            String settleAsset,
            long version) {
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        int optionType = type.isOption() ? 0 : -1;
        long strike = type.isOption() ? 100 : 0;
        return new UpsertInstrumentCommand(symbol, version, type.ordinal(), baseAsset, quoteAsset, settleAsset,
                1, 1, type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0,
                expiry, optionType, strike);
    }

    static String settleAsset(ProductLine productLine) {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }
}
