package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.AssetBalance;

import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.List;

public record CoreInstrumentState(
        String symbol,
        long changeId,
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
        long strikePriceTicks,
        long maxLeveragePpm,
        long maxPositionNotionalUnits,
        long userOpenInterestLimitRatePpm,
        long userOpenInterestLimitFloorUnits,
        List<CoreRiskLimitBracket> riskLimitBrackets,
        com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance,
        com.surprising.instrument.api.model.InstrumentStatus status,
        long lastChangeId) {

    public CoreInstrumentState(
        String symbol,
        long changeId,
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
        long strikePriceTicks,
        long maxLeveragePpm,
        long maxPositionNotionalUnits,
        long userOpenInterestLimitRatePpm,
        long userOpenInterestLimitFloorUnits,
        List<CoreRiskLimitBracket> riskLimitBrackets,
        com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
        this(symbol, changeId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits, settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks, maxLeveragePpm, maxPositionNotionalUnits, userOpenInterestLimitRatePpm, userOpenInterestLimitFloorUnits, riskLimitBrackets, maintenance, com.surprising.instrument.api.model.InstrumentStatus.TRADING, changeId);
    }

    public CoreInstrumentState(String symbol, long changeId, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets) {
        this(symbol, changeId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm,
                makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks,
                maxLeveragePpm, maxPositionNotionalUnits, userOpenInterestLimitRatePpm,
                userOpenInterestLimitFloorUnits, riskLimitBrackets,
                com.surprising.aeron.protocol.CoreInstrumentMaintenance.TRADING);
    }

    public CoreInstrumentState withMaintenance(com.surprising.aeron.protocol.CoreInstrumentMaintenance value) {
        return new CoreInstrumentState(symbol, changeId, contractType, baseAsset, quoteAsset, settleAsset,
                notionalMultiplierUnits, priceTickUnits, settleScaleUnits, initialMarginRatePpm,
                maintenanceMarginRatePpm, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis,
                optionType, strikePriceTicks, maxLeveragePpm, maxPositionNotionalUnits,
                userOpenInterestLimitRatePpm, userOpenInterestLimitFloorUnits, riskLimitBrackets, value, status, lastChangeId);
    }

    public CoreInstrumentState withStatus(com.surprising.instrument.api.model.InstrumentStatus value, long auditId) {
        return new CoreInstrumentState(symbol, changeId, contractType, baseAsset, quoteAsset, settleAsset,
                notionalMultiplierUnits, priceTickUnits, settleScaleUnits, initialMarginRatePpm,
                maintenanceMarginRatePpm, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis,
                optionType, strikePriceTicks, maxLeveragePpm, maxPositionNotionalUnits,
                userOpenInterestLimitRatePpm, userOpenInterestLimitFloorUnits, riskLimitBrackets, maintenance, value, auditId);
    }

    public void requireTrading(boolean reduceOnly) {
        if (status != com.surprising.instrument.api.model.InstrumentStatus.TRADING
                && !(status == com.surprising.instrument.api.model.InstrumentStatus.SETTLING && reduceOnly)) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_TRADING", "instrument status is " + status);
        }
        var mode = maintenance.mode();
        if (mode != com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.TRADING
                && !(mode == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.REDUCE_ONLY && reduceOnly)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument is under maintenance");
        }
    }

    public boolean administrativeSettlement(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        return maintenance.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && maintenance.taskId() == command.settlementId()
                && maintenance.settlementPriceTicks() == command.settlementPriceTicks()
                && changeId == command.instrumentChangeId();
    }

    public CoreInstrumentState {
        java.util.Objects.requireNonNull(maintenance, "maintenance");
        java.util.Objects.requireNonNull(status, "status");
        if (lastChangeId < changeId) throw new IllegalArgumentException("invalid instrument audit order");
        symbol = OrderReservation.normalizeSymbol(symbol);
        baseAsset = AssetBalance.normalizeAsset(baseAsset);
        quoteAsset = AssetBalance.normalizeAsset(quoteAsset);
        settleAsset = AssetBalance.normalizeAsset(settleAsset);
        if (baseAsset.equals(quoteAsset)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "base and quote assets must differ");
        }
        if (changeId <= 0 || contractType == null || notionalMultiplierUnits <= 0 || priceTickUnits <= 0
                || settleScaleUnits <= 0 || initialMarginRatePpm <= 0 || maintenanceMarginRatePpm <= 0
                || maxLeveragePpm < 1_000_000L || maxPositionNotionalUnits <= 0
                || userOpenInterestLimitRatePpm < 0 || userOpenInterestLimitFloorUnits <= 0
                || riskLimitBrackets == null || riskLimitBrackets.isEmpty()) {
            throw new IllegalArgumentException("invalid instrument state");
        }
        riskLimitBrackets = List.copyOf(riskLimitBrackets);
        long previousCap = 0;
        int expectedBracketNo = 1;
        for (CoreRiskLimitBracket bracket : riskLimitBrackets) {
            if (bracket.bracketNo() != expectedBracketNo++
                    || bracket.notionalFloorUnits() != previousCap
                    || bracket.maxLeveragePpm() > maxLeveragePpm) {
                throw new IllegalArgumentException("risk limit brackets must be contiguous and bounded");
            }
            previousCap = bracket.notionalCapUnits();
        }
        if (previousCap < maxPositionNotionalUnits) {
            throw new IllegalArgumentException("risk limit brackets must cover max position notional");
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
        return new CoreInstrumentState(command.symbol(), command.instrumentChangeId(), contractType,
                command.baseAsset(), command.quoteAsset(), command.settleAsset(),
                command.notionalMultiplierUnits(), command.priceTickUnits(), command.settleScaleUnits(),
                command.initialMarginRatePpm(), command.maintenanceMarginRatePpm(),
                command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.expiryEpochMillis(),
                optionType, command.strikePriceTicks(), command.maxLeveragePpm(),
                command.maxPositionNotionalUnits(), command.userOpenInterestLimitRatePpm(),
                command.userOpenInterestLimitFloorUnits(), command.riskLimitBrackets())
                .withStatus(com.surprising.instrument.api.model.InstrumentStatus.values()[command.statusCode()], command.lastChangeId());
    }
}
