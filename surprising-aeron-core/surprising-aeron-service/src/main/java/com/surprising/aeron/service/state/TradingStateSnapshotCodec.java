package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.product.api.ProductLine;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class TradingStateSnapshotCodec {

    private static final int VERSION = 19;
    private static final int MAX_TEXT_BYTES = 64;

    private TradingStateSnapshotCodec() {
    }

    public static byte[] encode(TradingCoreState state) {
        Writer writer = new Writer();
        writer.intValue(VERSION);
        writer.intValue(ProductLineWireCode.encode(state.productLine()));
        writer.longValue(state.revision());
        writer.intValue(state.users().size());
        for (CoreUserState user : state.users().values()) {
            writer.longValue(user.userId());
            writer.longValue(user.revision());
            writer.intValue(user.positionMode().wireCode());
            writer.intValue(user.balances().size());
            user.balances().values().forEach(balance -> {
                writer.text(balance.asset());
                writer.longValue(balance.availableUnits());
                writer.longValue(balance.lockedUnits());
            });
            writer.intValue(user.reservations().size());
            user.reservations().values().forEach(reservation -> {
                writer.longValue(reservation.orderId());
                writer.text(reservation.symbol());
                writer.longValue(reservation.instrumentVersion());
                writer.intValue(reservation.kind().wireCode());
                writer.text(reservation.asset());
                writer.longValue(reservation.reservedUnits());
                writer.longValue(reservation.releasedUnits());
                writer.longValue(reservation.consumedUnits());
                writer.longValue(reservation.orderQuantitySteps());
            });
            writer.intValue(user.positions().size());
            user.positions().values().forEach(position -> {
                writer.text(position.symbol());
                writer.text(position.marginAsset());
                writer.intValue(position.marginMode().wireCode());
                writer.intValue(position.positionSide().wireCode());
                writer.longValue(position.instrumentVersion());
                writer.longValue(position.signedQuantitySteps());
                writer.longValue(position.entryPriceTicks());
                writer.longValue(position.entryValueTicks());
                writer.longValue(position.realizedPnlUnits());
                writer.longValue(position.positionMarginUnits());
            });
        }
        writer.intValue(state.orders().size());
        state.orders().values().forEach(order -> {
            writer.longValue(order.orderId());
            writer.longValue(order.userId());
            writer.text(order.symbol());
            writer.longValue(order.instrumentVersion());
            writer.intValue(order.side().wireCode());
            writer.longValue(order.priceTicks());
            writer.longValue(order.quantitySteps());
            writer.longValue(order.executedQuantitySteps());
            writer.longValue(order.remainingQuantitySteps());
            writer.byteValue(order.reduceOnly() ? 1 : 0);
            writer.intValue(order.marginMode().wireCode());
            writer.intValue(order.positionSide().wireCode());
            writer.intValue(order.orderType().wireCode());
            writer.intValue(order.timeInForce().wireCode());
            writer.byteValue(order.postOnly() ? 1 : 0);
            writer.optionalText(order.clientOrderId());
            writer.longValue(order.commandId().getMostSignificantBits());
            writer.longValue(order.commandId().getLeastSignificantBits());
            writer.longValue(order.makerFeeRatePpm());
            writer.longValue(order.takerFeeRatePpm());
            writer.longValue(order.createdAtEpochMillis());
            writer.longValue(order.updatedAtEpochMillis());
            writer.longValue(order.clusterPosition());
            writer.intValue(order.status().ordinal());
            writer.longValue(order.revision());
        });
        writer.intValue(state.instruments().size());
        state.instruments().values().forEach(instrument -> {
            writer.text(instrument.symbol());
            writer.longValue(instrument.version());
            writer.intValue(instrument.contractType().ordinal());
            writer.text(instrument.baseAsset());
            writer.text(instrument.quoteAsset());
            writer.text(instrument.settleAsset());
            writer.longValue(instrument.notionalMultiplierUnits());
            writer.longValue(instrument.priceTickUnits());
            writer.longValue(instrument.settleScaleUnits());
            writer.longValue(instrument.initialMarginRatePpm());
            writer.longValue(instrument.maintenanceMarginRatePpm());
            writer.longValue(instrument.makerFeeRatePpm());
            writer.longValue(instrument.takerFeeRatePpm());
            writer.longValue(instrument.expiryEpochMillis());
            writer.intValue(instrument.optionType() == null ? -1 : instrument.optionType().ordinal());
            writer.longValue(instrument.strikePriceTicks());
            writer.longValue(instrument.maxLeveragePpm());
            writer.longValue(instrument.maxPositionNotionalUnits());
            writer.longValue(instrument.userOpenInterestLimitRatePpm());
            writer.longValue(instrument.userOpenInterestLimitFloorUnits());
            writer.intValue(instrument.riskLimitBrackets().size());
            instrument.riskLimitBrackets().forEach(bracket -> {
                writer.intValue(bracket.bracketNo());
                writer.longValue(bracket.notionalFloorUnits());
                writer.longValue(bracket.notionalCapUnits());
                writer.longValue(bracket.maxLeveragePpm());
                writer.longValue(bracket.initialMarginRatePpm());
                writer.longValue(bracket.maintenanceMarginRatePpm());
            });
        });
        writer.intValue(state.riskState().markPrices().size());
        state.riskState().markPrices().values().forEach(mark -> {
            writer.text(mark.symbol());
            writer.longValue(mark.instrumentVersion());
            writer.longValue(mark.markPriceTicks());
            writer.longValue(mark.priceSequence());
        });
        writer.intValue(state.riskState().snapshots().size());
        state.riskState().snapshots().values().forEach(risk -> {
            writer.longValue(risk.userId());
            writer.text(risk.symbol());
            writer.intValue(risk.positionSide().wireCode());
            writer.longValue(risk.priceSequence());
            writer.longValue(risk.equityUnits());
            writer.longValue(risk.unrealizedPnlUnits());
            writer.longValue(risk.maintenanceMarginUnits());
            writer.longValue(risk.marginRatioPpm());
            writer.intValue(risk.status().ordinal());
        });
        writer.intValue(state.riskState().liquidations().size());
        state.riskState().liquidations().values().forEach(liquidation -> {
            writer.longValue(liquidation.liquidationId());
            writer.longValue(liquidation.userId());
            writer.text(liquidation.symbol());
            writer.intValue(liquidation.marginMode().wireCode());
            writer.intValue(liquidation.positionSide().wireCode());
            writer.longValue(liquidation.instrumentVersion());
            writer.longValue(liquidation.triggerPriceSequence());
            writer.longValue(liquidation.signedQuantitySteps());
            writer.longValue(liquidation.closeQuantitySteps());
            writer.longValue(liquidation.deficitUnits());
            writer.longValue(liquidation.executionPriceTicks());
            writer.longValue(liquidation.liquidationFeeRatePpm());
            writer.longValue(liquidation.liquidationFeeUnits());
            writer.intValue(liquidation.status().ordinal());
            writer.longValue(liquidation.nextCancelOrderId());
        });
        writer.intValue(state.riskState().scans().size());
        state.riskState().scans().values().forEach(scan -> {
            writer.text(scan.symbol());
            writer.longValue(scan.priceSequence());
            writer.longValue(scan.scanStartPriceSequence());
            writer.longValue(scan.lastUserId());
            writer.byteValue(scan.riskComplete() ? 1 : 0);
            writer.longValue(scan.riskUserId());
            writer.intValue(scan.riskPhase());
            writer.text(scan.riskPositionCursor());
            writer.longValue(scan.riskReservationCursor());
            writer.longValue(scan.riskUnrealizedPnlUnits());
            writer.longValue(scan.riskMaintenanceMarginUnits());
            writer.longValue(scan.riskIsolatedMarginUnits());
            writer.longValue(scan.riskIsolatedReservationUnits());
            writer.byteValue(scan.triggerComplete() ? 1 : 0);
            writer.intValue(scan.triggerPhase());
            writer.longValue(scan.triggerPriceCursor());
            writer.longValue(scan.triggerOrderCursor());
            writer.longValue(scan.triggerUpperId());
            writer.longValue(scan.triggerMarkPriceTicks());
            writer.longValue(scan.triggerGeneratedAtEpochMillis());
            writer.longValue(scan.triggerOcoOrderId());
            writer.longValue(scan.triggerOcoCursor());
        });
        writer.longValue(state.riskState().nextLiquidationId());
        writeUnits(writer, state.treasuryState().feeBalances());
        writeUnits(writer, state.treasuryState().insuranceBalances());
        writeUnits(writer, state.treasuryState().insuranceDeficits());
        writeUnits(writer, state.treasuryState().fundingSettlements());
        writeUnits(writer, state.treasuryState().lifecycleSettlements());
        writer.intValue(state.treasuryState().fundingProgress().size());
        state.treasuryState().fundingProgress().forEach((symbol, progress) -> {
            writer.text(symbol);
            writer.longValue(progress.settlementId());
            writer.longValue(progress.instrumentVersion());
            writer.longValue(progress.fundingRatePpm());
            writer.longValue(progress.nextCursorUserId());
            writer.longValue(progress.commandId().getMostSignificantBits());
            writer.longValue(progress.commandId().getLeastSignificantBits());
        });
        writer.intValue(state.treasuryState().lifecycleProgress().size());
        state.treasuryState().lifecycleProgress().forEach((symbol, progress) -> {
            writer.text(symbol);
            writer.longValue(progress.settlementId());
            writer.longValue(progress.instrumentVersion());
            writer.longValue(progress.settlementPriceTicks());
            writer.longValue(progress.optionCashUnitsPerContract());
            writer.byteValue(progress.ordersComplete() ? 1 : 0);
            writer.longValue(progress.nextCursorOrderId());
            writer.longValue(progress.nextCursorUserId());
            writer.longValue(progress.commandId().getMostSignificantBits());
            writer.longValue(progress.commandId().getLeastSignificantBits());
        });
        writer.intValue(state.leverages().size());
        state.leverages().forEach((key, leverage) -> {
            writer.longValue(key.userId());
            writer.text(key.symbol());
            writer.intValue(key.marginMode().wireCode());
            writer.longValue(leverage);
        });
        writer.intValue(state.algoOrders().size());
        state.algoOrders().values().forEach(algo -> {
            byte[] encoded = com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(
                    new com.surprising.aeron.protocol.CoreAlgoOrderView(algo.algoOrderId(), algo.userId(),
                            algo.clientAlgoOrderId(), algo.symbol(), algo.algoTypeCode(), algo.side(), algo.priceTicks(),
                            algo.quantitySteps(), algo.childQuantitySteps(), algo.intervalSeconds(), algo.durationSeconds(),
                            algo.marginMode(), algo.positionSide(), algo.reduceOnly(), algo.postOnly(), algo.timeInForce(),
                            algo.statusCode(), algo.currentOrderId(), algo.rejectReason(), algo.traceId(),
                            algo.startAtEpochMillis(), algo.nextSliceAtEpochMillis(), algo.completedAtEpochMillis(),
                            algo.createdAtEpochMillis(), algo.updatedAtEpochMillis(), algo.revision(), algo.childOrderIds(),
                            0, 0, 0));
            writer.intValue(encoded.length);
            writer.bytes(encoded);
        });
        writer.intValue(state.cancelAllAfterTimers().size());
        state.cancelAllAfterTimers().values().forEach(timer -> {
            writer.longValue(timer.userId());
            writer.text(timer.symbolScope());
            writer.longValue(timer.countdownMillis());
            writer.intValue(timer.status().wireCode());
            writer.longValue(timer.triggerAtEpochMillis());
            writer.longValue(timer.updatedAtEpochMillis());
            writer.intValue(timer.canceledOrders());
            writer.intValue(timer.canceledTriggerOrders());
            writer.longValue(timer.revision());
        });
        writer.intValue(state.triggerOrders().size());
        state.triggerOrders().values().forEach(trigger -> {
            byte[] payload = com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger.view());
            writer.intValue(payload.length);
            writer.bytes(payload);
        });
        return writer.toByteArray();
    }

    public static TradingCoreState decode(byte[] encoded, ProductLine expectedProductLine) {
        Reader reader = new Reader(encoded);
        int version = reader.intValue();
        if (version != VERSION) {
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
                        reader.positiveLong("instrument version"),
                        ReservationKind.fromWireCode(reader.intValue()), reader.text(),
                        reader.positiveLong("reserved units"), reader.nonNegativeLong("released units"),
                        reader.nonNegativeLong("consumed units"), reader.positiveLong("order quantity"));
                putUnique(reservations, orderId, reservation);
            }
            Map<String, CorePositionState> positions = new TreeMap<>();
            int positionCount = reader.count("positions");
            for (int positionIndex = 0; positionIndex < positionCount; positionIndex++) {
                String symbol = reader.text();
                String marginAsset = reader.text();
                CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
                CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
                CorePositionState position = new CorePositionState(symbol, marginAsset, marginMode, positionSide,
                        reader.nonNegativeLong("instrument version"), reader.longValue(),
                        reader.nonNegativeLong("entry price"), reader.nonNegativeLong("entry value"),
                        reader.longValue(), reader.nonNegativeLong("position margin"));
                putUnique(positions, symbol, position);
            }
            putUnique(users, userId, new CoreUserState(productLine, userId, userRevision,
                    balances, reservations, positions, positionMode));
        }
        Map<Long, CoreOrderState> orders = new TreeMap<>();
        int orderCount = reader.count("orders");
        for (int index = 0; index < orderCount; index++) {
            long orderId = reader.positiveLong("orderId");
            long userId = reader.positiveLong("order userId");
            String symbol = reader.text();
            long instrumentVersion = reader.positiveLong("instrument version");
            CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
            long priceTicks = reader.nonNegativeLong("price ticks");
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
            long createdAt = reader.nonNegativeLong("order created time");
            long updatedAt = reader.nonNegativeLong("order updated time");
            long clusterPosition = reader.nonNegativeLong("order cluster position");
            int statusCode = reader.intValue();
            if (statusCode < 0 || statusCode >= CoreOrderStatus.values().length) {
                throw new ProtocolException("invalid order status: " + statusCode);
            }
            CoreOrderState order = new CoreOrderState(orderId, productLine, userId, symbol,
                    instrumentVersion, side,
                    priceTicks, quantitySteps, executedSteps, remainingSteps, reduceOnly,
                    orderMarginMode, orderPositionSide, orderType, timeInForce, postOnly,
                    clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                    createdAt, updatedAt, clusterPosition,
                    CoreOrderStatus.values()[statusCode], reader.positiveLong("order revision"));
            putUnique(orders, orderId, order);
        }
        Map<String, CoreInstrumentState> instruments = new TreeMap<>();
        int instrumentCount = reader.count("instruments");
        for (int index = 0; index < instrumentCount; index++) {
            String symbol = reader.text();
            long instrumentVersion = reader.positiveLong("instrument version");
            int contractType = reader.intValue();
            if (contractType < 0 || contractType >= ContractType.values().length) {
                throw new ProtocolException("invalid contract type: " + contractType);
            }
            ContractType decodedType = ContractType.values()[contractType];
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
            int bracketCount = reader.count("risk limit brackets");
            if (bracketCount == 0) throw new ProtocolException("risk limit brackets are empty");
            java.util.List<CoreRiskLimitBracket> brackets = new java.util.ArrayList<>(bracketCount);
            for (int bracketIndex = 0; bracketIndex < bracketCount; bracketIndex++) {
                brackets.add(new CoreRiskLimitBracket(reader.intValue(),
                        reader.nonNegativeLong("risk bracket floor"),
                        reader.positiveLong("risk bracket cap"),
                        reader.positiveLong("risk bracket max leverage"),
                        reader.positiveLong("risk bracket initial margin"),
                        reader.positiveLong("risk bracket maintenance margin")));
            }
            CoreInstrumentState instrument = new CoreInstrumentState(symbol, instrumentVersion,
                    decodedType, baseAsset, quoteAsset, settleAsset, multiplier, priceTick, settleScale,
                    initialMargin, maintenanceMargin, makerFee, takerFee, expiry,
                    optionTypeCode < 0 ? null : OptionType.values()[optionTypeCode],
                    strikePrice, maxLeverage, maxPosition, openInterestRate, openInterestFloor,
                    java.util.List.copyOf(brackets));
            putUnique(instruments, symbol, instrument);
        }
        Map<String, CoreMarkPriceState> marks = new TreeMap<>();
        int markCount = reader.count("mark prices");
        for (int index = 0; index < markCount; index++) {
            String symbol = reader.text();
            CoreMarkPriceState mark = new CoreMarkPriceState(symbol,
                    reader.positiveLong("mark instrument version"), reader.positiveLong("mark price"),
                    reader.positiveLong("price sequence"));
            putUnique(marks, symbol, mark);
        }
        Map<String, CoreRiskSnapshot> risks = new TreeMap<>();
        int riskCount = reader.count("risk snapshots");
        for (int index = 0; index < riskCount; index++) {
            long userId = reader.positiveLong("risk userId");
            String symbol = reader.text();
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
            CoreRiskSnapshot risk = new CoreRiskSnapshot(userId, symbol, positionSide,
                    priceSequence, equity, unrealized, maintenance, ratio, CoreRiskStatus.values()[status]);
            putUnique(risks, risk.key(), risk);
        }
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>();
        int liquidationCount = reader.count("liquidations");
        for (int index = 0; index < liquidationCount; index++) {
            long liquidationId = reader.positiveLong("liquidationId");
            long userId = reader.positiveLong("liquidation userId");
            String symbol = reader.text();
            CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
            CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
            long instrumentVersion = reader.positiveLong("liquidation instrument version");
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
            CoreLiquidationState liquidation = new CoreLiquidationState(liquidationId, userId, symbol,
                    marginMode, positionSide, instrumentVersion, priceSequence, signedQuantity, closeQuantity,
                    deficitUnits, executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits,
                    CoreLiquidationState.Status.values()[status], reader.nonNegativeLong("liquidation cancel cursor"));
            putUnique(liquidations, liquidationId, liquidation);
        }
        Map<String, CoreRiskState.RiskScan> scans = new TreeMap<>();
        int scanCount = reader.count("risk scans");
        for (int index = 0; index < scanCount; index++) {
            String scanSymbol = reader.text();
            CoreRiskState.RiskScan scan = new CoreRiskState.RiskScan(scanSymbol,
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
                    reader.nonNegativeLong("trigger OCO cursor"));
            putUnique(scans, scanSymbol, scan);
        }
        CoreRiskState riskState = new CoreRiskState(marks, risks, liquidations, scans,
                reader.positiveLong("next liquidation id"));
        Map<String, Long> feeBalances = readUnits(reader, "fee balances");
        Map<String, Long> insuranceBalances = readUnits(reader, "insurance balances");
        Map<String, Long> insuranceDeficits = readUnits(reader, "insurance deficits");
        Map<String, Long> fundingSettlements = readUnits(reader, "funding settlements");
        Map<String, Long> lifecycleSettlements = readUnits(reader, "lifecycle settlements");
        Map<String, CoreTreasuryState.FundingProgress> fundingProgress = new TreeMap<>();
        int fundingProgressCount = reader.count("funding progress");
        for (int index = 0; index < fundingProgressCount; index++) {
            String symbol = reader.text();
            CoreTreasuryState.FundingProgress progress = new CoreTreasuryState.FundingProgress(
                    reader.positiveLong("funding progress settlement id"),
                    reader.positiveLong("funding progress instrument version"),
                    reader.longValue(), reader.nonNegativeLong("funding progress cursor"),
                    new UUID(reader.longValue(), reader.longValue()));
            putUnique(fundingProgress, symbol, progress);
        }
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = new TreeMap<>();
        int lifecycleProgressCount = reader.count("lifecycle progress");
        for (int index = 0; index < lifecycleProgressCount; index++) {
            String symbol = reader.text();
            CoreTreasuryState.LifecycleProgress progress = new CoreTreasuryState.LifecycleProgress(
                    reader.positiveLong("lifecycle progress settlement id"),
                    reader.positiveLong("lifecycle progress instrument version"),
                    reader.nonNegativeLong("lifecycle progress settlement price"),
                    reader.nonNegativeLong("lifecycle progress option cash"),
                    reader.booleanValue(), reader.nonNegativeLong("lifecycle progress order cursor"),
                    reader.nonNegativeLong("lifecycle progress user cursor"),
                    new UUID(reader.longValue(), reader.longValue()));
            putUnique(lifecycleProgress, symbol, progress);
        }
        CoreTreasuryState treasuryState = new CoreTreasuryState(feeBalances, insuranceBalances,
                insuranceDeficits, fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
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
                    com.surprising.aeron.protocol.CoreAlgoOrderCodec.decode(reader.bytes(length)));
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
            CoreTriggerOrderState trigger = CoreTriggerOrderState.from(
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeState(reader.bytes(length)));
            putUnique(triggerOrders, trigger.triggerOrderId(), trigger);
        }
        reader.requireConsumed();
        return new TradingCoreState(productLine, revision, users, orders, instruments, riskState,
                treasuryState, leverages, algoOrders, cancelAllAfterTimers, triggerOrders);
    }

    private static void writeUnits(Writer writer, Map<String, Long> values) {
        writer.intValue(values.size());
        values.forEach((asset, units) -> {
            writer.text(asset);
            writer.longValue(units);
        });
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

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        void byteValue(int value) {
            output.write(value);
        }

        void intValue(int value) {
            for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
                output.write(value >>> shift);
            }
        }

        void longValue(long value) {
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                output.write((int) (value >>> shift));
            }
        }

        void text(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("invalid snapshot text length");
            }
            intValue(bytes.length);
            output.writeBytes(bytes);
        }

        void optionalText(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("invalid optional snapshot text length");
            }
            intValue(bytes.length);
            output.writeBytes(bytes);
        }

        void bytes(byte[] value) {
            if (value == null || value.length == 0 || value.length > 65_536) {
                throw new IllegalArgumentException("invalid snapshot payload length");
            }
            output.writeBytes(value);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
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
