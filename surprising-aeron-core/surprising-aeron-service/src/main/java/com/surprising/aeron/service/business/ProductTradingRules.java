package com.surprising.aeron.service.business;

import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.CoreStateRejectedException;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

public interface ProductTradingRules {

    long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                          ResolvedPlaceOrder order, long leverage,
                          long pendingQuantitySteps);

    ProductLine productLine();

    ContractType contractType();

    default void requireInstrument(CoreInstrumentState instrument) {
        if (instrument.contractType() != contractType()
                || instrument.contractType().productLine() != productLine()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "instrument does not belong to settlement kernel " + productLine());
        }
    }

    default long premiumDeltaUnits(CoreInstrumentState instrument, CoreOrderSide side,
                                   long priceTicks, long quantitySteps) {
        requireInstrument(instrument);
        return 0;
    }

    default long realizedPnlUnits(CoreInstrumentState instrument, long signedCloseSteps,
                                  long entryPriceTicks, long executionPriceTicks) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "realized PnL is unsupported for " + productLine());
    }

    default long fundingDeltaUnits(CoreInstrumentState instrument, long signedQuantitySteps,
                                   long markPriceTicks, long fundingRatePpm) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "funding is unsupported for " + productLine());
    }

    default long lifecycleCashDeltaUnits(CoreInstrumentState instrument, long signedQuantitySteps,
                                         long entryPriceTicks, long settlementPriceTicks) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "lifecycle settlement is unsupported for " + productLine());
    }
}
