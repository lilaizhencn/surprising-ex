package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;

public record CoreInstrumentState(
        String symbol,
        long version,
        ContractType contractType,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        long notionalMultiplierUnits,
        long priceTickUnits,
        long settleScaleUnits,
        long initialMarginRatePpm,
        long maintenanceMarginRatePpm,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long expiryEpochMillis,
        OptionType optionType,
        long strikePriceTicks) {

    public CoreInstrumentState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        baseAsset = AssetBalance.normalizeAsset(baseAsset);
        quoteAsset = AssetBalance.normalizeAsset(quoteAsset);
        settleAsset = AssetBalance.normalizeAsset(settleAsset);
        if (version <= 0 || contractType == null || notionalMultiplierUnits <= 0 || priceTickUnits <= 0
                || settleScaleUnits <= 0 || initialMarginRatePpm <= 0 || maintenanceMarginRatePpm <= 0) {
            throw new IllegalArgumentException("invalid instrument state");
        }
        if (contractType.isDelivery() && expiryEpochMillis <= 0) {
            throw new IllegalArgumentException("delivery instrument requires expiry time");
        }
        if (contractType.isOption()) {
            if (expiryEpochMillis <= 0 || optionType == null || strikePriceTicks <= 0) {
                throw new IllegalArgumentException("option instrument requires expiry, type, and strike");
            }
        } else if (optionType != null || strikePriceTicks != 0) {
            throw new IllegalArgumentException("non-option instrument contains option parameters");
        }
    }

    public static CoreInstrumentState from(ProductLine productLine, UpsertInstrumentCommand command) {
        ContractType[] values = ContractType.values();
        if (command.contractTypeCode() >= values.length) {
            throw new CoreStateRejectedException("INVALID_CONTRACT_TYPE", "contract type code is unknown");
        }
        ContractType contractType = values[command.contractTypeCode()];
        if (contractType.productLine() != productLine) {
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "instrument belongs to another product line");
        }
        OptionType optionType = null;
        if (contractType.isOption()) {
            if (command.optionTypeCode() < 0 || command.optionTypeCode() >= OptionType.values().length) {
                throw new CoreStateRejectedException("INVALID_OPTION_TYPE", "option type code is unknown");
            }
            optionType = OptionType.values()[command.optionTypeCode()];
        } else if (command.optionTypeCode() != -1) {
            throw new CoreStateRejectedException("INVALID_OPTION_TYPE", "non-option must not set option type");
        }
        return new CoreInstrumentState(command.symbol(), command.instrumentVersion(), contractType,
                command.baseAsset(), command.quoteAsset(), command.settleAsset(),
                command.notionalMultiplierUnits(), command.priceTickUnits(), command.settleScaleUnits(),
                command.initialMarginRatePpm(), command.maintenanceMarginRatePpm(),
                command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.expiryEpochMillis(),
                optionType, command.strikePriceTicks());
    }
}
