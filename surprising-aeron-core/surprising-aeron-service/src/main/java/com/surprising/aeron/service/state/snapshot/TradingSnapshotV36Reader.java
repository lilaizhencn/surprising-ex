package com.surprising.aeron.service.state.snapshot;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.service.state.*;

import com.surprising.aeron.service.state.model.RiskLaneProgress;

import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreRiskSnapshot;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.product.api.ProductLine;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.UUID;

final class TradingSnapshotV36Reader {

    private static final int VERSION = 36;
    private static final int MAX_TEXT_BYTES = 64;
    private static final int MAX_AUDIT_TEXT_BYTES = 2_048;

    private TradingSnapshotV36Reader() {
    }

    public static TradingCoreState decode(byte[] encoded, ProductLine expectedProductLine) {
        Reader reader = new Reader(encoded);
        int version = reader.intValue();
        if (version < 35 || version > VERSION) {
            throw new ProtocolException("unsupported trading snapshot version: " + version);
        }
        ProductLine productLine = ProductLineWireCode.decode(reader.intValue());
        if (productLine != expectedProductLine) {
            throw new ProtocolException("trading snapshot product line mismatch");
        }
        long revision = reader.nonNegativeLong("core revision");
        int userCount = reader.count("users");
        Map<Long, CoreUserState> users = new TreeMap<>();
        for (int index = 0; index < userCount; index++) {
            long userId = reader.positiveLong("userId");
            long userRevision = reader.nonNegativeLong("user revision");
            CorePositionMode positionMode = CorePositionMode.fromWireCode(reader.intValue());
            Map<String, AssetBalance> balances = new TreeMap<>();
            int balanceCount = reader.count("balances");
            for (int balanceIndex = 0; balanceIndex < balanceCount; balanceIndex++) {
                String asset = reader.text();
                putUnique(balances, asset, new AssetBalance(asset,
                        reader.nonNegativeLong("available units"), reader.nonNegativeLong("locked units")));
            }
            Map<Long, OrderReservation> reservations = new TreeMap<>();
            int reservationCount = reader.count("reservations");
            for (int reservationIndex = 0; reservationIndex < reservationCount; reservationIndex++) {
                long orderId = reader.positiveLong("reservation orderId");
                OrderReservation reservation = new OrderReservation(orderId, reader.text(),
                        ReservationKind.fromWireCode(reader.intValue()), reader.text(),
                        reader.positiveLong("reserved units"), reader.nonNegativeLong("released units"),
                        reader.nonNegativeLong("consumed units"), reader.positiveLong("order quantity"));
                putUnique(reservations, orderId, reservation);
            }
            Map<String, CorePositionState> positions = new TreeMap<>();
            int positionCount = reader.count("positions");
            for (int positionIndex = 0; positionIndex < positionCount; positionIndex++) {
                String instrumentId = reader.text();
                String marginAsset = reader.text();
                CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
                CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
                CorePositionState position = new CorePositionState(instrumentId, marginAsset, marginMode, positionSide,
                        reader.longValue(),
                        reader.nonNegativeLong("entry price"), reader.nonNegativeLong("entry value"),
                        reader.longValue(), reader.nonNegativeLong("position margin"));
                putUnique(positions, instrumentId, position);
            }
            putUnique(users, userId, new CoreUserState(productLine, userId, userRevision,
                    balances, reservations, positions, positionMode));
        }
        Map<Long, CoreOrderState> orders = new TreeMap<>();
        int orderCount = reader.count("orders");
        for (int index = 0; index < orderCount; index++) {
            long orderId = reader.positiveLong("orderId");
            long userId = reader.positiveLong("order userId");
            String instrumentId = reader.text();
            CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
            long priceTicks = reader.nonNegativeLong("price ticks");
            long matchingPriceTicks = reader.nonNegativeLong("matching price ticks");
            long quantitySteps = reader.positiveLong("quantity steps");
            long executedSteps = reader.nonNegativeLong("executed steps");
            long remainingSteps = reader.nonNegativeLong("remaining steps");
            boolean reduceOnly = reader.booleanValue();
            CoreMarginMode orderMarginMode = CoreMarginMode.fromWireCode(reader.intValue());
            CorePositionSide orderPositionSide = CorePositionSide.fromWireCode(reader.intValue());
            CoreOrderType orderType = CoreOrderType.fromWireCode(reader.intValue());
            CoreTimeInForce timeInForce = CoreTimeInForce.fromWireCode(reader.intValue());
            boolean postOnly = reader.booleanValue();
            String clientOrderId = reader.optionalText();
            UUID commandId = new UUID(reader.longValue(), reader.longValue());
            long makerFeeRatePpm = reader.longValue();
            long takerFeeRatePpm = reader.longValue();
            long cumulativeFeeUnits = reader.longValue();
            long valueHigh = reader.longValue();
            long valueLow = reader.longValue();
            long createdAt = reader.nonNegativeLong("order created time");
            long updatedAt = reader.nonNegativeLong("order updated time");
            long clusterPosition = reader.nonNegativeLong("order cluster position");
            int statusCode = reader.intValue();
            if (statusCode < 0 || statusCode >= CoreOrderStatus.values().length) {
                throw new ProtocolException("invalid order status: " + statusCode);
            }
            CoreOrderState order = new CoreOrderState(orderId, productLine, userId, instrumentId,
                    side,
                    priceTicks, matchingPriceTicks, quantitySteps, executedSteps, remainingSteps, reduceOnly,
                    orderMarginMode, orderPositionSide, orderType, timeInForce, postOnly,
                    clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                    cumulativeFeeUnits, valueHigh, valueLow, createdAt, updatedAt, clusterPosition,
                    SnapshotEnumCodes.readCoreOrderStatus(statusCode), reader.positiveLong("order revision"));
            putUnique(orders, orderId, order);
        }
        Map<String, CoreInstrument> instruments = new TreeMap<>();
        int instrumentCount = reader.count("instruments");
        for (int index = 0; index < instrumentCount; index++) {
            String instrumentId = reader.text();
            int contractType = reader.intValue();
            if (contractType < 0 || contractType >= ContractType.values().length) {
                throw new ProtocolException("invalid contract type: " + contractType);
            }
            ContractType decodedType = SnapshotEnumCodes.readContractType(contractType);
            String baseAsset = reader.text();
            String quoteAsset = reader.text();
            String settleAsset = reader.text();
            long multiplier = reader.positiveLong("notional multiplier");
            long priceTick = reader.positiveLong("price tick units");
            long settleScale = reader.positiveLong("settle scale");
            long initialMargin = reader.positiveLong("initial margin rate");
            long maintenanceMargin = reader.positiveLong("maintenance margin rate");
            long makerFee = reader.longValue();
            long takerFee = reader.longValue();
            long expiry = reader.nonNegativeLong("expiry time");
            int optionTypeCode = reader.intValue();
            if (optionTypeCode < -1 || optionTypeCode >= OptionType.values().length) {
                throw new ProtocolException("invalid option type: " + optionTypeCode);
            }
            long strikePrice = reader.nonNegativeLong("strike price");
            long maxLeverage = reader.positiveLong("max leverage");
            long maxPosition = reader.positiveLong("max position notional");
            long openInterestRate = reader.nonNegativeLong("open interest limit rate");
            long openInterestFloor = reader.positiveLong("open interest limit floor");
            long maintenanceTaskId = reader.nonNegativeLong("maintenance task");
            int maintenanceMode = reader.intValue();
            var maintenanceModes = com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.values();
            if (maintenanceMode < 0 || maintenanceMode >= maintenanceModes.length) throw new ProtocolException("invalid maintenance mode");
            var maintenance = new com.surprising.aeron.protocol.CoreInstrumentMaintenance(maintenanceTaskId,
                    SnapshotEnumCodes.readCoreInstrumentMaintenanceMode(maintenanceMode), reader.nonNegativeLong("maintenance price"));
            int instrumentStatus = reader.intValue();
            if (instrumentStatus < 0 || instrumentStatus >= com.surprising.instrument.api.model.InstrumentStatus.values().length) {
                throw new ProtocolException("invalid instrument status: " + instrumentStatus);
            }
            boolean marketOrderEnabled = reader.booleanValue();
            boolean postOnlyEnabled = reader.booleanValue();
            boolean reduceOnlyEnabled = reader.booleanValue();
            int supportedOrderTypeMask = reader.intValue();
            int supportedTimeInForceMask = reader.intValue();
            int bracketCount = reader.count("risk limit brackets");
            if (bracketCount == 0) throw new ProtocolException("risk limit brackets are empty");
            java.util.List<CoreRiskLimitBracket> brackets = new java.util.ArrayList<>(bracketCount);
            for (int bracketIndex = 0; bracketIndex < bracketCount; bracketIndex++) {
                brackets.add(new CoreRiskLimitBracket(reader.intValue(),
                        reader.nonNegativeLong("risk bracket floor"),
                        reader.positiveLong("risk bracket cap"),
                        reader.positiveLong("risk bracket max leverage"),
                        reader.positiveLong("risk bracket initial margin"),
                        reader.positiveLong("risk bracket maintenance margin"),
                        reader.positiveLong("option margin factor")));
            }
            CoreInstrument instrument = new CoreInstrument(instrumentId,
                    decodedType, baseAsset, quoteAsset, settleAsset, multiplier, priceTick, settleScale,
                    initialMargin, maintenanceMargin, makerFee, takerFee, expiry,
                    optionTypeCode < 0 ? null : SnapshotEnumCodes.readOptionType(optionTypeCode),
                    strikePrice, maxLeverage, maxPosition, openInterestRate, openInterestFloor,
                    java.util.List.copyOf(brackets), maintenance,
                    SnapshotEnumCodes.readInstrumentStatus(instrumentStatus),
                    marketOrderEnabled, postOnlyEnabled, reduceOnlyEnabled,
                    supportedOrderTypeMask, supportedTimeInForceMask);
            putUnique(instruments, instrumentId, instrument);
        }
        Map<String, CoreMarkPriceState> marks = new TreeMap<>();
        int markCount = reader.count("mark prices");
        for (int index = 0; index < markCount; index++) {
            String instrumentId = reader.text();
            CoreMarkPriceState mark = new CoreMarkPriceState(instrumentId,
                    reader.positiveLong("mark price"),
                    reader.nonNegativeLong("mark index price"), reader.nonNegativeLong("mark forward price"),
                    reader.positiveLong("price sequence"), reader.positiveLong("mark generated time"),
                    version < 36 ? 0 : reader.nonNegativeLong("last price"));
            putUnique(marks, instrumentId, mark);
        }
        Map<String, CoreRiskSnapshot> risks = new TreeMap<>();
        int riskCount = reader.count("risk snapshots");
        for (int index = 0; index < riskCount; index++) {
            long userId = reader.positiveLong("risk userId");
            String instrumentId = reader.text();
            CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
            long priceSequence = reader.positiveLong("risk price sequence");
            long equity = reader.longValue();
            long unrealized = reader.longValue();
            long maintenance = reader.nonNegativeLong("maintenance margin");
            long ratio = reader.nonNegativeLong("margin ratio");
            int status = reader.intValue();
            if (status < 0 || status >= CoreRiskStatus.values().length) {
                throw new ProtocolException("invalid risk status: " + status);
            }
            CoreRiskSnapshot risk = new CoreRiskSnapshot(userId, instrumentId, positionSide,
                    priceSequence, equity, unrealized, maintenance, ratio, SnapshotEnumCodes.readCoreRiskStatus(status));
            putUnique(risks, risk.key(), risk);
        }
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>();
        int liquidationCount = reader.count("liquidations");
        for (int index = 0; index < liquidationCount; index++) {
            long liquidationId = reader.positiveLong("liquidationId");
            long userId = reader.positiveLong("liquidation userId");
            String instrumentId = reader.text();
            CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
            CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
            long priceSequence = reader.positiveLong("liquidation price sequence");
            long signedQuantity = reader.longValue();
            long closeQuantity = reader.positiveLong("liquidation close quantity");
            long deficitUnits = reader.nonNegativeLong("liquidation deficit");
            long executionPriceTicks = reader.nonNegativeLong("liquidation execution price");
            long liquidationFeeRatePpm = reader.nonNegativeLong("liquidation fee rate");
            long liquidationFeeUnits = reader.nonNegativeLong("liquidation fee units");
            int status = reader.intValue();
            if (status < 0 || status >= CoreLiquidationState.Status.values().length) {
                throw new ProtocolException("invalid liquidation status: " + status);
            }
            CoreLiquidationState liquidation = new CoreLiquidationState(liquidationId, userId, instrumentId,
                    marginMode, positionSide, priceSequence, signedQuantity, closeQuantity,
                    deficitUnits, executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits,
                    SnapshotEnumCodes.readCoreLiquidationStateStatus(status), reader.nonNegativeLong("liquidation cancel cursor"));
            putUnique(liquidations, liquidationId, liquidation);
        }
        Map<String, CoreRiskState.RiskScan> scans = new TreeMap<>();
        int scanCount = reader.count("risk scans");
        for (int index = 0; index < scanCount; index++) {
            String scanSymbol = reader.text();
            CoreRiskState.RiskScan scan = new CoreRiskState.RiskScan(scanSymbol,
                    reader.intValue(),
                    reader.nonNegativeLong("scan price sequence"),
                    reader.nonNegativeLong("scan start price sequence"),
                    reader.nonNegativeLong("scan userId"), reader.booleanValue(),
                    reader.nonNegativeLong("scan active userId"), reader.intValue(), reader.text(),
                    reader.nonNegativeLong("scan reservation cursor"), reader.longValue(),
                    reader.nonNegativeLong("scan maintenance margin"),
                    reader.nonNegativeLong("scan isolated margin"),
                    reader.nonNegativeLong("scan isolated reservation"), reader.booleanValue(),
                    reader.intValue(), reader.nonNegativeLong("trigger price cursor"),
                    reader.nonNegativeLong("trigger order cursor"),
                    reader.nonNegativeLong("trigger upper id"),
                    reader.nonNegativeLong("trigger mark price"),
                    reader.nonNegativeLong("trigger generated time"),
                    reader.nonNegativeLong("trigger OCO order id"),
                    reader.nonNegativeLong("trigger OCO cursor"), reader.nonNegativeLong("risk scheduling revision"), readRiskLanes(reader));
            putUnique(scans, scanSymbol, scan);
        }
        long nextLiquidationId = reader.positiveLong("next liquidation id");
        long marketRevision = reader.nonNegativeLong("market revision");
        CoreRiskScanControlView scanControl = new CoreRiskScanControlView(
                reader.positiveLong("risk scan control version"), reader.auditText(), reader.booleanValue(),
                reader.nonNegativeLong("risk scan delay"), reader.intValue(), reader.auditText(), reader.auditText(),
                reader.nonNegativeLong("risk scan control updated time"));
        CoreRiskState riskState = new CoreRiskState(marks, risks, liquidations, scans,
                nextLiquidationId, scanControl, marketRevision);
        Map<String, Long> feeBalances = readUnits(reader, "fee balances");
        Map<String, Long> insuranceBalances = readUnits(reader, "insurance balances");
        Map<String, Long> insuranceDeficits = readUnits(reader, "insurance deficits");
        Map<String, Long> liquidationFeeBalances = readUnits(reader, "liquidation fee balances");
        Map<String, Long> fundingResidualBalances = readUnits(reader, "funding residual balances");
        Map<String, Long> roundingResidualBalances = readUnits(reader, "rounding residual balances");
        Map<String, Long> clearingPnlBalances = readUnits(reader, "clearing pnl balances");
        Map<String, Long> fundingSettlements = readUnits(reader, "funding settlements");
        Map<String, Long> lifecycleSettlements = readUnits(reader, "lifecycle settlements");
        Map<String, CoreTreasuryState.FundingProgress> fundingProgress = new TreeMap<>();
        int fundingProgressCount = reader.count("funding progress");
        for (int index = 0; index < fundingProgressCount; index++) {
            String instrumentId = reader.text();
            long settlementId = reader.positiveLong("funding progress settlement id");
            long rate = reader.longValue();
            long mark = reader.positiveLong("funding progress mark");
            long priceSequence = reader.positiveLong("funding progress price sequence");
            CoreTreasuryState.FundingProgress progress = new CoreTreasuryState.FundingProgress(
                    settlementId, rate, reader.intValue(),
                    reader.nonNegativeLong("funding progress cursor"),
                    new UUID(reader.longValue(), reader.longValue()), mark, priceSequence);
            putUnique(fundingProgress, instrumentId, progress);
        }
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = new TreeMap<>();
        int lifecycleProgressCount = reader.count("lifecycle progress");
        for (int index = 0; index < lifecycleProgressCount; index++) {
            String instrumentId = reader.text();
            CoreTreasuryState.LifecycleProgress progress = new CoreTreasuryState.LifecycleProgress(
                    reader.positiveLong("lifecycle progress settlement id"),
                    reader.nonNegativeLong("lifecycle progress settlement price"),
                    reader.nonNegativeLong("lifecycle progress option cash"),
                    reader.booleanValue(), reader.intValue(),
                    reader.nonNegativeLong("lifecycle progress order cursor"),
                    reader.nonNegativeLong("lifecycle progress user cursor"),
                    new UUID(reader.longValue(), reader.longValue()), reader.nonNegativeLong("required settlement insurance"));
            putUnique(lifecycleProgress, instrumentId, progress);
        }
        CoreTreasuryState treasuryState = new CoreTreasuryState(feeBalances, insuranceBalances,
                insuranceDeficits, liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances,
                clearingPnlBalances, fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
        Map<CoreLeverageKey, Long> leverages = new TreeMap<>();
        int leverageCount = reader.count("leverages");
        for (int index = 0; index < leverageCount; index++) {
            CoreLeverageKey key = new CoreLeverageKey(reader.positiveLong("leverage userId"), reader.text(),
                    CoreMarginMode.fromWireCode(reader.intValue()));
            putUnique(leverages, key, reader.positiveLong("leveragePpm"));
        }
        Map<Long, CoreAlgoOrderState> algoOrders = new TreeMap<>();
        int algoCount = reader.count("algo orders");
        for (int index = 0; index < algoCount; index++) {
            int length = reader.count("algo payload bytes");
            CoreAlgoOrderState algo = CoreAlgoOrderState.from(
                    readPublishedAlgo(reader.bytes(length)));
            putUnique(algoOrders, algo.algoOrderId(), algo);
        }
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new TreeMap<>();
        int timerCount = reader.count("cancel-all-after timers");
        for (int index = 0; index < timerCount; index++) {
            long userId = reader.positiveLong("cancel-all-after userId");
            String symbolScope = reader.text();
            long countdownMillis = reader.nonNegativeLong("cancel-all-after countdown");
            com.surprising.aeron.protocol.CoreCancelAllAfterStatus status =
                    com.surprising.aeron.protocol.CoreCancelAllAfterStatus.fromWireCode(reader.intValue());
            long triggerAt = reader.nonNegativeLong("cancel-all-after trigger time");
            long updatedAt = reader.positiveLong("cancel-all-after updated time");
            int canceledOrders = reader.intValue();
            int canceledTriggerOrders = reader.intValue();
            if (canceledOrders < 0 || canceledTriggerOrders < 0) {
                throw new ProtocolException("negative cancel-all-after result count");
            }
            CoreCancelAllAfterState timer = new CoreCancelAllAfterState(userId, symbolScope, countdownMillis,
                    status, triggerAt, updatedAt, canceledOrders, canceledTriggerOrders,
                    reader.positiveLong("cancel-all-after revision"));
            putUnique(cancelAllAfterTimers, timer.key(), timer);
        }
        Map<Long, CoreTriggerOrderState> triggerOrders = new TreeMap<>();
        int triggerCount = reader.count("trigger orders");
        for (int index = 0; index < triggerCount; index++) {
            int length = reader.count("trigger payload bytes");
            var view = readPublishedTrigger(reader.bytes(length));
            CoreInstrument instrument = instruments.get(view.instrumentId());
            if (instrument == null) throw new ProtocolException("trigger instrument is missing");
            CoreTriggerOrderState trigger = CoreTriggerOrderState.from(view, instrument);
            putUnique(triggerOrders, trigger.triggerOrderId(), trigger);
        }
        reader.requireConsumed();
        return new TradingCoreState(productLine, revision, users, orders, instruments, riskState,
                treasuryState, leverages, algoOrders, cancelAllAfterTimers, triggerOrders);
    }

    // Published embedded layouts are frozen here; interface Codec changes must not change disk readers.
    static CoreAlgoOrderView readPublishedAlgo(byte[] encoded) {
        Reader reader = new Reader(encoded);
        if (reader.intValue() != 1 || reader.intValue() != 1)
            throw new ProtocolException("unsupported historical algo snapshot");
        long id = reader.longValue(), userId = reader.longValue(); String clientId = reader.embeddedText(256), instrumentId = reader.embeddedText(256);
        int type = reader.intValue(); CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
        long price = reader.longValue(), quantity = reader.longValue(), childQuantity = reader.longValue();
        long interval = reader.longValue(), duration = reader.longValue();
        CoreMarginMode margin = CoreMarginMode.fromWireCode(reader.intValue());
        CorePositionSide position = CorePositionSide.fromWireCode(reader.intValue());
        boolean reduce = reader.booleanValue(), post = reader.booleanValue(); CoreTimeInForce tif = CoreTimeInForce.fromWireCode(reader.intValue());
        int status = reader.intValue(); long current = reader.longValue(); String reason = reader.embeddedText(256), trace = reader.embeddedText(256);
        long start = reader.longValue(), next = reader.longValue(), completed = reader.longValue();
        long created = reader.longValue(), updated = reader.longValue(), revision = reader.longValue();
        int childCount = reader.count("algo children"); List<Long> children = new ArrayList<>(childCount);
        for (int index = 0; index < childCount; index++) children.add(reader.longValue());
        var result = new CoreAlgoOrderView(id, userId, clientId, instrumentId, type, side, price, quantity, childQuantity,
                interval, duration, margin, position, reduce, post, tif, status, current, reason, trace,
                start, next, completed, created, updated, revision, children, reader.longValue(), reader.longValue(), reader.intValue());
        reader.requireConsumed();
        return result;
    }

    static CoreTriggerOrderStateView readPublishedTrigger(byte[] encoded) {
        Reader reader = new Reader(encoded); int version = reader.intValue();
        if (version != 2 && version != 3) throw new ProtocolException("unsupported historical trigger snapshot");
        CoreTriggerOrderStateView result = new CoreTriggerOrderStateView(
                reader.positiveLong("trigger value"), ProductLineWireCode.decode(reader.intValue()), reader.positiveLong("trigger value"),
                reader.embeddedText(128), reader.embeddedText(128), reader.embeddedText(128), CoreOrderSide.fromWireCode(reader.intValue()),
                SnapshotEnumCodes.readCoreTriggerOrderType(reader.intValue()),
                SnapshotEnumCodes.readCoreTriggerCondition(reader.intValue()), reader.nonNegativeLong("trigger value"),
                reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"),
                CoreOrderType.fromWireCode(reader.intValue()), CoreTimeInForce.fromWireCode(reader.intValue()),
                reader.nonNegativeLong("trigger value"), reader.positiveLong("trigger value"), CoreMarginMode.fromWireCode(reader.intValue()),
                CorePositionSide.fromWireCode(reader.intValue()),
                SnapshotEnumCodes.readCoreTriggerOrderStatus(reader.intValue()), reader.nonNegativeLong("trigger value"),
                reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"), reader.embeddedText(128), reader.embeddedText(128), reader.nonNegativeLong("trigger value"),
                reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"),
                reader.longValue(), reader.longValue(),
                version < 3 ? CoreTriggerPriceSource.MARK
                        : SnapshotEnumCodes.readCoreTriggerPriceSource(reader.intValue()));
        reader.requireConsumed(); return result;
    }

    private static Map<String, Long> readUnits(Reader reader, String name) {
        Map<String, Long> values = new TreeMap<>();
        int count = reader.count(name);
        for (int index = 0; index < count; index++) {
            putUnique(values, reader.text(), reader.longValue());
        }
        return values;
    }

    private static <K, V> void putUnique(Map<K, V> values, K key, V value) {
        if (values.put(key, value) != null) {
            throw new ProtocolException("duplicate trading snapshot key: " + key);
        }
    }

    private static java.util.List<RiskLaneProgress> readRiskLanes(Reader reader) {
        int count = reader.count("risk Lanes");
        if (count > Long.SIZE) throw new IllegalArgumentException("too many risk Lanes");
        var lanes = new java.util.ArrayList<RiskLaneProgress>(count);
        for (int i = 0; i < count; i++) lanes.add(new RiskLaneProgress(
                reader.nonNegativeLong("Lane completed user"), reader.booleanValue(),
                reader.nonNegativeLong("Lane active user"), reader.intValue(), reader.text(),
                reader.nonNegativeLong("Lane reservation cursor"), reader.longValue(),
                reader.nonNegativeLong("Lane maintenance margin"), reader.nonNegativeLong("Lane isolated margin"),
                reader.nonNegativeLong("Lane isolated reservation"),
                reader.nonNegativeLong("Lane account revision"), reader.nonNegativeLong("Lane market revision")));
        return lanes;
    }

    private static final class Reader {
        private final byte[] input;
        private int offset;

        Reader(byte[] input) {
            if (input == null) {
                throw new ProtocolException("trading snapshot is required");
            }
            this.input = input;
        }

        int byteValue() {
            require(Byte.BYTES);
            return Byte.toUnsignedInt(input[offset++]);
        }

        int intValue() {
            require(Integer.BYTES);
            int value = 0;
            for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
                value |= Byte.toUnsignedInt(input[offset++]) << shift;
            }
            return value;
        }

        long longValue() {
            require(Long.BYTES);
            long value = 0;
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                value |= (long) Byte.toUnsignedInt(input[offset++]) << shift;
            }
            return value;
        }

        long nonNegativeLong(String field) {
            long value = longValue();
            if (value < 0) {
                throw new ProtocolException(field + " must not be negative");
            }
            return value;
        }

        long positiveLong(String field) {
            long value = longValue();
            if (value <= 0) {
                throw new ProtocolException(field + " must be positive");
            }
            return value;
        }

        int count(String field) {
            int value = intValue();
            if (value < 0 || value > input.length) {
                throw new ProtocolException("invalid " + field + " count: " + value);
            }
            return value;
        }

        String text() {
            int length = count("text");
            if (length == 0 || length > MAX_TEXT_BYTES) {
                throw new ProtocolException("invalid snapshot text length: " + length);
            }
            require(length);
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        String optionalText() {
            int length = count("optional text");
            if (length > MAX_TEXT_BYTES) {
                throw new ProtocolException("invalid optional snapshot text length: " + length);
            }
            require(length);
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        String embeddedText(int maximumBytes) {
            int length = count("embedded text");
            if (length > maximumBytes) throw new ProtocolException("historical embedded text too long");
            require(length);
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        String auditText() {
            int length = count("audit text");
            if (length == 0 || length > MAX_AUDIT_TEXT_BYTES) {
                throw new ProtocolException("invalid snapshot audit text length: " + length);
            }
            require(length);
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        byte[] bytes(int length) {
            if (length <= 0 || length > 65_536) {
                throw new ProtocolException("invalid snapshot payload length: " + length);
            }
            require(length);
            byte[] value = java.util.Arrays.copyOfRange(input, offset, offset + length);
            offset += length;
            return value;
        }

        boolean booleanValue() {
            int value = byteValue();
            if (value != 0 && value != 1) {
                throw new ProtocolException("invalid boolean value: " + value);
            }
            return value == 1;
        }

        void requireConsumed() {
            if (offset != input.length) {
                throw new ProtocolException("trailing bytes in trading snapshot");
            }
        }

        private void require(int length) {
            if (length < 0 || offset > input.length - length) {
                throw new ProtocolException("truncated trading snapshot");
            }
        }
    }
}
