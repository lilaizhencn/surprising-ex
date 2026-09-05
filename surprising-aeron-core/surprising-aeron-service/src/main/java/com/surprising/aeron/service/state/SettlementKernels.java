package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

public final class SettlementKernels {

    private static final SettlementKernel SPOT = new SpotSettlementKernel();
    private static final SettlementKernel LINEAR_PERPETUAL = new LinearPerpetualSettlementKernel();
    private static final SettlementKernel INVERSE_PERPETUAL = new InversePerpetualSettlementKernel();
    private static final SettlementKernel LINEAR_DELIVERY = new LinearDeliverySettlementKernel();
    private static final SettlementKernel INVERSE_DELIVERY = new InverseDeliverySettlementKernel();
    private static final SettlementKernel OPTION = new OptionSettlementKernel();

    private SettlementKernels() {
    }

    public static SettlementKernel forInstrument(CoreInstrumentState instrument) {
        if (instrument == null) throw new IllegalArgumentException("settlement instrument is required");
        SettlementKernel kernel = forProductLine(instrument.contractType().productLine());
        kernel.requireInstrument(instrument);
        return kernel;
    }

    public static SettlementKernel forProductLine(ProductLine productLine) {
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

sealed interface SettlementKernel permits SpotSettlementKernel, LinearPerpetualSettlementKernel,
        InversePerpetualSettlementKernel, LinearDeliverySettlementKernel,
        InverseDeliverySettlementKernel, OptionSettlementKernel {

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

final class SpotSettlementKernel implements SettlementKernel {
    public ProductLine productLine() { return ProductLine.SPOT; }
    public ContractType contractType() { return ContractType.SPOT; }
}

final class LinearPerpetualSettlementKernel implements SettlementKernel {
    public ProductLine productLine() { return ProductLine.LINEAR_PERPETUAL; }
    public ContractType contractType() { return ContractType.LINEAR_PERPETUAL; }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    public long fundingDeltaUnits(CoreInstrumentState instrument, long quantity, long mark, long rate) {
        requireInstrument(instrument);
        return CoreContractMath.fundingDeltaUnits(instrument, quantity, mark, rate);
    }
}

final class InversePerpetualSettlementKernel implements SettlementKernel {
    public ProductLine productLine() { return ProductLine.INVERSE_PERPETUAL; }
    public ContractType contractType() { return ContractType.INVERSE_PERPETUAL; }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    public long fundingDeltaUnits(CoreInstrumentState instrument, long quantity, long mark, long rate) {
        requireInstrument(instrument);
        return CoreContractMath.fundingDeltaUnits(instrument, quantity, mark, rate);
    }
}

final class LinearDeliverySettlementKernel implements SettlementKernel {
    public ProductLine productLine() { return ProductLine.LINEAR_DELIVERY; }
    public ContractType contractType() { return ContractType.LINEAR_DELIVERY; }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    public long lifecycleCashDeltaUnits(CoreInstrumentState instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, settlement);
    }
}

final class InverseDeliverySettlementKernel implements SettlementKernel {
    public ProductLine productLine() { return ProductLine.INVERSE_DELIVERY; }
    public ContractType contractType() { return ContractType.INVERSE_DELIVERY; }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    public long lifecycleCashDeltaUnits(CoreInstrumentState instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, settlement);
    }
}

final class OptionSettlementKernel implements SettlementKernel {
    public ProductLine productLine() { return ProductLine.OPTION; }
    public ContractType contractType() { return ContractType.VANILLA_OPTION; }
    public long premiumDeltaUnits(CoreInstrumentState instrument, CoreOrderSide side,
                                  long priceTicks, long quantitySteps) {
        requireInstrument(instrument);
        long premium = CoreContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps);
        return side == CoreOrderSide.BUY ? Math.negateExact(premium) : premium;
    }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return 0;
    }
    public long lifecycleCashDeltaUnits(CoreInstrumentState instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return Math.multiplyExact(CoreContractMath.optionSettlementCashUnits(instrument, settlement), quantity);
    }
}
