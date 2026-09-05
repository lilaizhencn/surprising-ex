package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;

public final class ProductTradingRulesRegistry {

    private static final ProductTradingRules SPOT = new SpotTradingRules();
    private static final ProductTradingRules LINEAR_PERPETUAL = new LinearPerpetualTradingRules();
    private static final ProductTradingRules INVERSE_PERPETUAL = new InversePerpetualTradingRules();
    private static final ProductTradingRules LINEAR_DELIVERY = new LinearDeliveryTradingRules();
    private static final ProductTradingRules INVERSE_DELIVERY = new InverseDeliveryTradingRules();
    private static final ProductTradingRules OPTION = new OptionTradingRules();

    private ProductTradingRulesRegistry() {
    }

    public static ProductTradingRules forInstrument(CoreInstrumentState instrument) {
        if (instrument == null) throw new IllegalArgumentException("settlement instrument is required");
        ProductTradingRules kernel = forProductLine(instrument.contractType().productLine());
        kernel.requireInstrument(instrument);
        return kernel;
    }

    public static ProductTradingRules forProductLine(ProductLine productLine) {
        if (productLine == null) throw new IllegalArgumentException("product line is required");
        return switch (productLine) {
            case SPOT -> SPOT;
            case LINEAR_PERPETUAL -> LINEAR_PERPETUAL;
            case INVERSE_PERPETUAL -> INVERSE_PERPETUAL;
            case LINEAR_DELIVERY -> LINEAR_DELIVERY;
            case INVERSE_DELIVERY -> INVERSE_DELIVERY;
            case OPTION -> OPTION;
        };
    }
}
