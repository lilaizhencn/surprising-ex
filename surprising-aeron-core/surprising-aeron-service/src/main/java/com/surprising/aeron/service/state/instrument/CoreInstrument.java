package com.surprising.aeron.service.state.instrument;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.OrderReservation;
import com.surprising.aeron.service.state.model.AssetBalance;

import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.CoreInstrumentMaintenance;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Objects;

public final class CoreInstrument {
    private final String symbol;
    private final ContractType contractType;
    private final String baseAsset;
    private final String quoteAsset;
    private final String settleAsset;
    private final long notionalMultiplierUnits;
    private final long priceTickUnits;
    private final long settleScaleUnits;
    private final long initialMarginRatePpm;
    private final long maintenanceMarginRatePpm;
    private final long makerFeeRatePpm;
    private final long takerFeeRatePpm;
    private final long expiryEpochMillis;
    private final OptionType optionType;
    private final long strikePriceTicks;
    private final long maxLeveragePpm;
    private final long maxPositionNotionalUnits;
    private final long userOpenInterestLimitRatePpm;
    private final long userOpenInterestLimitFloorUnits;
    private final List<CoreRiskLimitBracket> riskLimitBrackets;
    private volatile CoreInstrumentMaintenance maintenance;

    public CoreInstrument(String symbol, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets,
            CoreInstrumentMaintenance maintenance) {
        this.symbol = OrderReservation.normalizeSymbol(symbol);
        this.contractType = Objects.requireNonNull(contractType, "contractType");
        this.baseAsset = AssetBalance.normalizeAsset(baseAsset);
        this.quoteAsset = AssetBalance.normalizeAsset(quoteAsset);
        this.settleAsset = AssetBalance.normalizeAsset(settleAsset);
        this.notionalMultiplierUnits = notionalMultiplierUnits;
        this.priceTickUnits = priceTickUnits;
        this.settleScaleUnits = settleScaleUnits;
        this.initialMarginRatePpm = initialMarginRatePpm;
        this.maintenanceMarginRatePpm = maintenanceMarginRatePpm;
        this.makerFeeRatePpm = makerFeeRatePpm;
        this.takerFeeRatePpm = takerFeeRatePpm;
        this.expiryEpochMillis = expiryEpochMillis;
        this.optionType = optionType;
        this.strikePriceTicks = strikePriceTicks;
        this.maxLeveragePpm = maxLeveragePpm;
        this.maxPositionNotionalUnits = maxPositionNotionalUnits;
        this.userOpenInterestLimitRatePpm = userOpenInterestLimitRatePpm;
        this.userOpenInterestLimitFloorUnits = userOpenInterestLimitFloorUnits;
        this.riskLimitBrackets = List.copyOf(Objects.requireNonNull(riskLimitBrackets, "riskLimitBrackets"));
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        validate();
    }

    public CoreInstrument(String symbol, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets) {
        this(symbol, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm,
                makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks,
                maxLeveragePpm, maxPositionNotionalUnits, userOpenInterestLimitRatePpm,
                userOpenInterestLimitFloorUnits, riskLimitBrackets,
                CoreInstrumentMaintenance.TRADING);
    }

    public String symbol() { return symbol; }
    public ContractType contractType() { return contractType; }
    public String baseAsset() { return baseAsset; }
    public String quoteAsset() { return quoteAsset; }
    public String settleAsset() { return settleAsset; }
    public long notionalMultiplierUnits() { return notionalMultiplierUnits; }
    public long priceTickUnits() { return priceTickUnits; }
    public long settleScaleUnits() { return settleScaleUnits; }
    public long initialMarginRatePpm() { return initialMarginRatePpm; }
    public long maintenanceMarginRatePpm() { return maintenanceMarginRatePpm; }
    public long makerFeeRatePpm() { return makerFeeRatePpm; }
    public long takerFeeRatePpm() { return takerFeeRatePpm; }
    public long expiryEpochMillis() { return expiryEpochMillis; }
    public OptionType optionType() { return optionType; }
    public long strikePriceTicks() { return strikePriceTicks; }
    public long maxLeveragePpm() { return maxLeveragePpm; }
    public long maxPositionNotionalUnits() { return maxPositionNotionalUnits; }
    public long userOpenInterestLimitRatePpm() { return userOpenInterestLimitRatePpm; }
    public long userOpenInterestLimitFloorUnits() { return userOpenInterestLimitFloorUnits; }
    public List<CoreRiskLimitBracket> riskLimitBrackets() { return riskLimitBrackets; }
    public CoreInstrumentMaintenance maintenance() { return maintenance; }

    public void updateMaintenance(CoreInstrumentMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    public void requireTrading(boolean reduceOnly) {
        var mode = maintenance().mode();
        if (mode != com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.TRADING
                && !(mode == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.REDUCE_ONLY && reduceOnly)) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_TRADING", "instrument is under maintenance");
        }
    }

    public boolean administrativeSettlement(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var maintenance = maintenance();
        return maintenance.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && maintenance.taskId() == command.settlementId()
                && maintenance.settlementPriceTicks() == command.settlementPriceTicks();
    }

    private void validate() {
        if (baseAsset.equals(quoteAsset)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "base and quote assets must differ");
        }
        if (notionalMultiplierUnits <= 0 || priceTickUnits <= 0
                || settleScaleUnits <= 0 || initialMarginRatePpm <= 0 || maintenanceMarginRatePpm <= 0
                || maxLeveragePpm < 1_000_000L || maxPositionNotionalUnits <= 0
                || userOpenInterestLimitRatePpm < 0 || userOpenInterestLimitFloorUnits <= 0
                || riskLimitBrackets.isEmpty()) {
            throw new IllegalArgumentException("invalid instrument");
        }
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

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CoreInstrument that)) return false;
        return notionalMultiplierUnits == that.notionalMultiplierUnits
                && priceTickUnits == that.priceTickUnits && settleScaleUnits == that.settleScaleUnits
                && initialMarginRatePpm == that.initialMarginRatePpm
                && maintenanceMarginRatePpm == that.maintenanceMarginRatePpm
                && makerFeeRatePpm == that.makerFeeRatePpm && takerFeeRatePpm == that.takerFeeRatePpm
                && expiryEpochMillis == that.expiryEpochMillis && strikePriceTicks == that.strikePriceTicks
                && maxLeveragePpm == that.maxLeveragePpm
                && maxPositionNotionalUnits == that.maxPositionNotionalUnits
                && userOpenInterestLimitRatePpm == that.userOpenInterestLimitRatePpm
                && userOpenInterestLimitFloorUnits == that.userOpenInterestLimitFloorUnits
                && symbol.equals(that.symbol) && contractType == that.contractType
                && baseAsset.equals(that.baseAsset) && quoteAsset.equals(that.quoteAsset)
                && settleAsset.equals(that.settleAsset) && optionType == that.optionType
                && riskLimitBrackets.equals(that.riskLimitBrackets) && maintenance.equals(that.maintenance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, contractType, baseAsset, quoteAsset, settleAsset,
                notionalMultiplierUnits, priceTickUnits, settleScaleUnits, initialMarginRatePpm,
                maintenanceMarginRatePpm, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis,
                optionType, strikePriceTicks, maxLeveragePpm, maxPositionNotionalUnits,
                userOpenInterestLimitRatePpm, userOpenInterestLimitFloorUnits, riskLimitBrackets);
    }

    @Override
    public String toString() {
        return "CoreInstrument[symbol=" + symbol + ", contractType=" + contractType
                + ", baseAsset=" + baseAsset + ", quoteAsset=" + quoteAsset
                + ", settleAsset=" + settleAsset + ", maintenance=" + maintenance + ']';
    }

    public static CoreInstrument from(ProductLine productLine, RegisterInstrumentCommand command) {
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
        return new CoreInstrument(command.symbol(), contractType,
                command.baseAsset(), command.quoteAsset(), command.settleAsset(),
                command.notionalMultiplierUnits(), command.priceTickUnits(), command.settleScaleUnits(),
                command.initialMarginRatePpm(), command.maintenanceMarginRatePpm(),
                command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.expiryEpochMillis(),
                optionType, command.strikePriceTicks(), command.maxLeveragePpm(),
                command.maxPositionNotionalUnits(), command.userOpenInterestLimitRatePpm(),
                command.userOpenInterestLimitFloorUnits(), command.riskLimitBrackets());
    }
}
