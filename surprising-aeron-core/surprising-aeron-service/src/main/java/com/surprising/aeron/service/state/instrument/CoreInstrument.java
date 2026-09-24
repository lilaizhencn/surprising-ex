package com.surprising.aeron.service.state.instrument;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.OrderReservation;
import com.surprising.aeron.service.state.model.AssetBalance;

import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.CoreInstrumentMaintenance;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Objects;

public final class CoreInstrument {
    private final String symbol;
    private volatile Configuration configuration;
    private volatile CoreInstrumentMaintenance maintenance;

    public CoreInstrument(String symbol, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets,
            CoreInstrumentMaintenance maintenance) {
        this(symbol, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm,
                makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks,
                maxLeveragePpm, maxPositionNotionalUnits, userOpenInterestLimitRatePpm,
                userOpenInterestLimitFloorUnits, riskLimitBrackets, maintenance, InstrumentStatus.TRADING,
                true, true, true, 0b11, 0b1111);
    }

    public CoreInstrument(String symbol, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets, CoreInstrumentMaintenance maintenance,
            InstrumentStatus instrumentStatus, boolean marketOrderEnabled, boolean postOnlyEnabled,
            boolean reduceOnlyEnabled, int supportedOrderTypeMask, int supportedTimeInForceMask) {
        this.symbol = OrderReservation.normalizeSymbol(symbol);
        this.configuration = new Configuration(Objects.requireNonNull(contractType, "contractType"),
                AssetBalance.normalizeAsset(baseAsset), AssetBalance.normalizeAsset(quoteAsset),
                AssetBalance.normalizeAsset(settleAsset), notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm, makerFeeRatePpm,
                takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks, maxLeveragePpm,
                maxPositionNotionalUnits, userOpenInterestLimitRatePpm, userOpenInterestLimitFloorUnits,
                List.copyOf(Objects.requireNonNull(riskLimitBrackets, "riskLimitBrackets")),
                Objects.requireNonNull(instrumentStatus, "instrumentStatus"), marketOrderEnabled,
                postOnlyEnabled, reduceOnlyEnabled, supportedOrderTypeMask, supportedTimeInForceMask);
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
    public Configuration configuration() { return configuration; }
    public void updateConfiguration(CoreInstrument updated) {
        if (updated == null || !symbol.equals(updated.symbol)) throw new IllegalArgumentException("instrument symbol mismatch");
        this.configuration = updated.configuration;
    }
    public void restoreConfiguration(Configuration configuration) { this.configuration = Objects.requireNonNull(configuration); }
    public ContractType contractType() { return configuration.contractType(); }
    public String baseAsset() { return configuration.baseAsset(); }
    public String quoteAsset() { return configuration.quoteAsset(); }
    public String settleAsset() { return configuration.settleAsset(); }
    public long notionalMultiplierUnits() { return configuration.notionalMultiplierUnits(); }
    public long priceTickUnits() { return configuration.priceTickUnits(); }
    public long settleScaleUnits() { return configuration.settleScaleUnits(); }
    public long initialMarginRatePpm() { return configuration.initialMarginRatePpm(); }
    public long maintenanceMarginRatePpm() { return configuration.maintenanceMarginRatePpm(); }
    public long makerFeeRatePpm() { return configuration.makerFeeRatePpm(); }
    public long takerFeeRatePpm() { return configuration.takerFeeRatePpm(); }
    public long expiryEpochMillis() { return configuration.expiryEpochMillis(); }
    public OptionType optionType() { return configuration.optionType(); }
    public long strikePriceTicks() { return configuration.strikePriceTicks(); }
    public long maxLeveragePpm() { return configuration.maxLeveragePpm(); }
    public long maxPositionNotionalUnits() { return configuration.maxPositionNotionalUnits(); }
    public long userOpenInterestLimitRatePpm() { return configuration.userOpenInterestLimitRatePpm(); }
    public long userOpenInterestLimitFloorUnits() { return configuration.userOpenInterestLimitFloorUnits(); }
    public List<CoreRiskLimitBracket> riskLimitBrackets() { return configuration.riskLimitBrackets(); }
    public InstrumentStatus instrumentStatus() { return configuration.instrumentStatus(); }
    public boolean marketOrderEnabled() { return configuration.marketOrderEnabled(); }
    public boolean postOnlyEnabled() { return configuration.postOnlyEnabled(); }
    public boolean reduceOnlyEnabled() { return configuration.reduceOnlyEnabled(); }
    public int supportedOrderTypeMask() { return configuration.supportedOrderTypeMask(); }
    public int supportedTimeInForceMask() { return configuration.supportedTimeInForceMask(); }
    public CoreInstrumentMaintenance maintenance() { return maintenance; }

    public void updateMaintenance(CoreInstrumentMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    public void requireTrading(boolean reduceOnly) {
        InstrumentStatus status = instrumentStatus();
        if (status != InstrumentStatus.TRADING
                && !(status == InstrumentStatus.SETTLING && reduceOnly)) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_TRADING",
                    "instrument status does not allow this order");
        }
        if (reduceOnly && contractType().productLine() == ProductLine.SPOT) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "spot assets do not support reduce-only orders");
        }
        var mode = maintenance().mode();
        if (mode != com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.TRADING
                && !(mode == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.REDUCE_ONLY && reduceOnly)) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_TRADING", "instrument is under maintenance");
        }
        if (reduceOnly && !reduceOnlyEnabled()) {
            throw new CoreStateRejectedException("REDUCE_ONLY_DISABLED", "reduce-only orders are disabled");
        }
    }

    public void requireOrderEnabled(com.surprising.aeron.protocol.PlaceOrderCommand command) {
        requireOrderEnabled(command.orderType(), command.timeInForce(), command.postOnly(), command.reduceOnly());
    }

    public void requireOrderEnabled(com.surprising.aeron.protocol.CoreOrderType orderType,
                                    com.surprising.aeron.protocol.CoreTimeInForce timeInForce,
                                    boolean postOnly, boolean reduceOnly) {
        requireTrading(reduceOnly);
        int orderTypeBit = 1 << (orderType.wireCode() - 1);
        int timeInForceBit = 1 << (timeInForce.wireCode() - 1);
        if ((supportedOrderTypeMask() & orderTypeBit) == 0) {
            throw new CoreStateRejectedException("ORDER_TYPE_DISABLED", "order type is not enabled");
        }
        if ((supportedTimeInForceMask() & timeInForceBit) == 0) {
            throw new CoreStateRejectedException("TIME_IN_FORCE_DISABLED", "time in force is not enabled");
        }
        if (orderType == com.surprising.aeron.protocol.CoreOrderType.MARKET && !marketOrderEnabled()) {
            throw new CoreStateRejectedException("MARKET_ORDER_DISABLED", "market orders are disabled");
        }
        if (postOnly && !postOnlyEnabled()) {
            throw new CoreStateRejectedException("POST_ONLY_DISABLED", "post-only orders are disabled");
        }
    }

    public boolean administrativeSettlement(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var maintenance = maintenance();
        return maintenance.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && maintenance.taskId() == command.settlementId()
                && maintenance.settlementPriceTicks() == command.settlementPriceTicks();
    }

    private void validate() {
        Configuration configuration = this.configuration;
        if (configuration.baseAsset().equals(configuration.quoteAsset())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "base and quote assets must differ");
        }
        if (configuration.notionalMultiplierUnits() <= 0 || configuration.priceTickUnits() <= 0
                || configuration.settleScaleUnits() <= 0 || configuration.initialMarginRatePpm() <= 0
                || configuration.maintenanceMarginRatePpm() <= 0
                || configuration.maxLeveragePpm() < 1_000_000L || configuration.maxPositionNotionalUnits() <= 0
                || configuration.userOpenInterestLimitRatePpm() < 0
                || configuration.userOpenInterestLimitFloorUnits() <= 0
                || configuration.riskLimitBrackets().isEmpty() || configuration.instrumentStatus() == null
                || configuration.supportedOrderTypeMask() <= 0
                || (configuration.supportedOrderTypeMask() & ~0b11) != 0
                || configuration.supportedTimeInForceMask() <= 0
                || (configuration.supportedTimeInForceMask() & ~0b1111) != 0) {
            throw new IllegalArgumentException("invalid instrument");
        }
        long previousCap = 0;
        int expectedBracketNo = 1;
        for (CoreRiskLimitBracket bracket : configuration.riskLimitBrackets()) {
            if (bracket.bracketNo() != expectedBracketNo++
                    || bracket.notionalFloorUnits() != previousCap
                    || bracket.maxLeveragePpm() > configuration.maxLeveragePpm()) {
                throw new IllegalArgumentException("risk limit brackets must be contiguous and bounded");
            }
            previousCap = bracket.notionalCapUnits();
        }
        if (previousCap < configuration.maxPositionNotionalUnits()) {
            throw new IllegalArgumentException("risk limit brackets must cover max position notional");
        }
        if (configuration.contractType().isDelivery() && configuration.expiryEpochMillis() <= 0) {
            throw new IllegalArgumentException("delivery instrument requires expiry time");
        }
        if (configuration.contractType().isOption()) {
            if (configuration.expiryEpochMillis() <= 0 || configuration.optionType() == null
                    || configuration.strikePriceTicks() <= 0) {
                throw new IllegalArgumentException("option instrument requires expiry, type, and strike");
            }
        } else if (configuration.optionType() != null || configuration.strikePriceTicks() != 0) {
            throw new IllegalArgumentException("non-option instrument contains option parameters");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CoreInstrument that)) return false;
        return symbol.equals(that.symbol) && configuration.equals(that.configuration)
                && maintenance.equals(that.maintenance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, configuration);
    }

    @Override
    public String toString() {
        Configuration current = configuration;
        return "CoreInstrument[symbol=" + symbol + ", contractType=" + current.contractType()
                + ", baseAsset=" + current.baseAsset() + ", quoteAsset=" + current.quoteAsset()
                + ", settleAsset=" + current.settleAsset() + ", maintenance=" + maintenance + ']';
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
                command.userOpenInterestLimitFloorUnits(), command.riskLimitBrackets(),
                CoreInstrumentMaintenance.TRADING, InstrumentStatus.values()[command.instrumentStatusCode()],
                command.marketOrderEnabled(), command.postOnlyEnabled(), command.reduceOnlyEnabled(),
                command.supportedOrderTypeMask(), command.supportedTimeInForceMask());
    }

    public record Configuration(ContractType contractType, String baseAsset, String quoteAsset, String settleAsset,
                                long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
                                long initialMarginRatePpm, long maintenanceMarginRatePpm,
                                long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis,
                                OptionType optionType, long strikePriceTicks, long maxLeveragePpm,
                                long maxPositionNotionalUnits, long userOpenInterestLimitRatePpm,
                                long userOpenInterestLimitFloorUnits, List<CoreRiskLimitBracket> riskLimitBrackets,
                                InstrumentStatus instrumentStatus, boolean marketOrderEnabled,
                                boolean postOnlyEnabled, boolean reduceOnlyEnabled, int supportedOrderTypeMask,
                                int supportedTimeInForceMask) {
        public Configuration {
            riskLimitBrackets = List.copyOf(riskLimitBrackets);
            Objects.requireNonNull(instrumentStatus, "instrumentStatus");
        }
    }
}
