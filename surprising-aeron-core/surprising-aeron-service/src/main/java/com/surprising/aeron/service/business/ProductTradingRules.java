package com.surprising.aeron.service.business;

import com.surprising.aeron.service.state.instrument.CoreInstrumentState;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.exception.CoreStateRejectedException;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

public interface ProductTradingRules {

    long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                          ResolvedPlaceOrder order, long leverage,
                          long pendingQuantitySteps);

    ProductLine productLine();

    ContractType contractType();

    /**
     * Validates lifecycle admission before the runtime cancels orders or prepares
     * account-lane settlement work. Sequencing and state mutation remain in the
     * runtime; product permission and maintenance-gate rules live here.
     */
    default void validateLifecycleSettlement(CoreInstrumentState instrument,
                                             SettleInstrumentCommand command) {
        requireInstrument(instrument);
        if (command == null) {
            throw new IllegalArgumentException("settlement command is required");
        }
        if (instrument.maintenance().mode()
                == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && !instrument.administrativeSettlement(command)) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "settlement differs from approved maintenance task");
        }
        validateLifecycleSettlementProductRule(instrument, command);
        if (command.settlementPriceTicks() <= 0) {
            throw new CoreStateRejectedException("INVALID_SETTLEMENT_PRICE",
                    "delivery price must be positive");
        }
    }

    /** Product-specific lifecycle admission. Unsupported lines reject by default. */
    default void validateLifecycleSettlementProductRule(CoreInstrumentState instrument,
                                                        SettleInstrumentCommand command) {
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "instrument settlement is unsupported for " + productLine());
    }

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

    /** Cash change for one position at the product line's lifecycle settlement boundary. */
    default long lifecycleSettlementCashDeltaUnits(CoreInstrumentState instrument,
                                                   long signedQuantitySteps,
                                                   long entryPriceTicks,
                                                   long settlementPriceTicks) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "lifecycle settlement is unsupported for " + productLine());
    }
}
