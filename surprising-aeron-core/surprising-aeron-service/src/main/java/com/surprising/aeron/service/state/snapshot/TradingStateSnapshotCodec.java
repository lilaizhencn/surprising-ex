package com.surprising.aeron.service.state.snapshot;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreRiskState.RiskScan;
import com.surprising.aeron.service.state.CoreTreasuryState.FundingProgress;
import com.surprising.aeron.service.state.CoreTreasuryState.LifecycleProgress;
import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Function;

/**
 * 交易状态持久化格式。字段 ID 只增不复用；Java 名称、声明顺序和接口 DTO 不定义磁盘格式。
 * v35/v36 由冻结的读取器恢复；v37 使用独立记录和编号字段，未知字段按长度跳过。
 */
public final class TradingStateSnapshotCodec {
    private static final int VERSION = 37;
    private static final int READER_REVISION = 3;
    private TradingStateSnapshotCodec() { }

    public static byte[] encode(TradingCoreState value) {
        byte[] fields = new SnapshotFields.Writer()
                .number(1, SnapshotEnumCodes.encode(value.productLine()))
                .number(2, value.revision())
                .bytes(3, writeMap(value.users(), key -> new SnapshotFields.Writer().number(1, key).encode(), TradingStateSnapshotCodec::writeCoreUserState))
                .bytes(4, writeMap(value.orders(), key -> new SnapshotFields.Writer().number(1, key).encode(), TradingStateSnapshotCodec::writeCoreOrderState))
                .bytes(5, writeMap(value.instruments(), key -> new SnapshotFields.Writer().text(1, key).encode(), TradingStateSnapshotCodec::writeCoreInstrument))
                .bytes(6, writeCoreRiskState(value.riskState()))
                .bytes(7, writeCoreTreasuryState(value.treasuryState()))
                .bytes(8, writeMap(value.leverages(), TradingStateSnapshotCodec::writeCoreLeverageKey, item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(9, writeMap(value.algoOrders(), key -> new SnapshotFields.Writer().number(1, key).encode(), TradingStateSnapshotCodec::writeCoreAlgoOrderState))
                .list(10, value.cancelAllAfterTimers().values(), TradingStateSnapshotCodec::writeCoreCancelAllAfterState)
                .list(11, value.triggerOrders().values(), item -> writeCoreTriggerOrderStateView(item.view()))
                .number(12, value.instruments().values().stream().anyMatch(instrument -> !instrument.orderProtection().equals(com.surprising.aeron.protocol.CoreOrderProtection.initial())) ? 3 : value.instruments().values().stream().anyMatch(instrument -> instrument.quantityStepUnits() != 1) ? 2 : 1).encode();
        return ByteBuffer.allocate(4 + fields.length).order(ByteOrder.LITTLE_ENDIAN).putInt(VERSION).put(fields).array();
    }

    public static TradingCoreState decode(byte[] encoded, ProductLine expectedProductLine) {
        if (encoded == null || encoded.length < 4) throw new ProtocolException("truncated trading snapshot");
        int version = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (version == 35 || version == 36) return TradingSnapshotV36Reader.decode(encoded, expectedProductLine);
        if (version != VERSION) throw new ProtocolException("unsupported trading snapshot version: " + version);
        try {
            var r = new SnapshotFields.Reader(Arrays.copyOfRange(encoded, 4, encoded.length));
            int minimumReader = r.integer(12);
            if (minimumReader < 1 || minimumReader > READER_REVISION)
                throw new ProtocolException("trading snapshot requires reader version: " + minimumReader);
            ProductLine product = SnapshotEnumCodes.readProductLine(r.integer(1));
            if (product != expectedProductLine) throw new ProtocolException("trading snapshot product line mismatch");
            Map<Long, CoreUserState> users = readMap(r.bytes(3), key -> new SnapshotFields.Reader(key).number(1), TradingStateSnapshotCodec::readCoreUserState);
            Map<Long, CoreOrderState> orders = readMap(r.bytes(4), key -> new SnapshotFields.Reader(key).number(1), TradingStateSnapshotCodec::readCoreOrderState);
            Map<String, CoreInstrument> instruments = readMap(r.bytes(5), key -> new SnapshotFields.Reader(key).text(1), TradingStateSnapshotCodec::readCoreInstrument);
            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = new TreeMap<>();
            for (var timer : r.list(10, TradingStateSnapshotCodec::readCoreCancelAllAfterState)) putUnique(timers, timer.key(), timer);
            Map<Long, CoreTriggerOrderState> triggers = new TreeMap<>();
            for (var view : r.list(11, TradingStateSnapshotCodec::readCoreTriggerOrderStateView)) {
                var instrument = instruments.get(view.instrumentId());
                if (instrument == null) throw new ProtocolException("trigger instrument is missing");
                putUnique(triggers, view.triggerOrderId(), CoreTriggerOrderState.from(view, instrument));
            }
            return new TradingCoreState(product, r.number(2), users, orders, instruments,
                    readCoreRiskState(r.bytes(6)), readCoreTreasuryState(r.bytes(7)),
                    readMap(r.bytes(8), TradingStateSnapshotCodec::readCoreLeverageKey, item -> new SnapshotFields.Reader(item).number(1)),
                    readMap(r.bytes(9), key -> new SnapshotFields.Reader(key).number(1), TradingStateSnapshotCodec::readCoreAlgoOrderState), timers, triggers);
        } catch (ProtocolException invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw new ProtocolException("invalid trading snapshot: " + invalid.getMessage(), invalid); }
    }

    private static <K, V> byte[] writeMap(Map<K, V> values, Function<K, byte[]> key, Function<V, byte[]> value) {
        return new SnapshotFields.Writer().list(1, values.entrySet(), entry -> new SnapshotFields.Writer()
                .bytes(1, key.apply(entry.getKey())).bytes(2, value.apply(entry.getValue())).encode()).encode();
    }
    private static <K, V> Map<K, V> readMap(byte[] encoded, Function<byte[], K> key, Function<byte[], V> value) {
        Map<K, V> values = new TreeMap<>();
        for (var entry : new SnapshotFields.Reader(encoded).list(1, SnapshotFields.Reader::new))
            putUnique(values, key.apply(entry.bytes(1)), value.apply(entry.bytes(2)));
        return values;
    }
    private static <K, V> void putUnique(Map<K, V> values, K key, V value) {
        if (values.put(key, value) != null) throw new ProtocolException("duplicate trading snapshot key: " + key);
    }

    private static byte[] writeAssetBalance(AssetBalance value) {
        return new SnapshotFields.Writer()
                .text(1, value.asset()) // 1: asset
                .number(2, value.availableUnits()) // 2: availableUnits
                .number(3, value.lockedUnits()) // 3: lockedUnits
                .encode();
    }
    private static AssetBalance readAssetBalance(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new AssetBalance(
                r.text(1), // 1: asset
                r.number(2), // 2: availableUnits
                r.number(3)); // 3: lockedUnits
    }

    private static byte[] writeOrderReservation(OrderReservation value) {
        return new SnapshotFields.Writer()
                .number(1, value.orderId()) // 1: orderId
                .text(2, value.instrumentId()) // 2: instrumentId
                .number(3, SnapshotEnumCodes.encode(value.kind())) // 3: kind
                .text(4, value.asset()) // 4: asset
                .number(5, value.reservedUnits()) // 5: reservedUnits
                .number(6, value.releasedUnits()) // 6: releasedUnits
                .number(7, value.consumedUnits()) // 7: consumedUnits
                .number(8, value.orderQuantitySteps()) // 8: orderQuantitySteps
                .encode();
    }
    private static OrderReservation readOrderReservation(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new OrderReservation(
                r.number(1), // 1: orderId
                r.text(2), // 2: instrumentId
                SnapshotEnumCodes.readReservationKind(r.integer(3)), // 3: kind
                r.text(4), // 4: asset
                r.number(5), // 5: reservedUnits
                r.number(6), // 6: releasedUnits
                r.number(7), // 7: consumedUnits
                r.number(8)); // 8: orderQuantitySteps
    }

    private static byte[] writeCorePositionState(CorePositionState value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId()) // 1: instrumentId
                .text(2, value.marginAsset()) // 2: marginAsset
                .number(3, SnapshotEnumCodes.encode(value.marginMode())) // 3: marginMode
                .number(4, SnapshotEnumCodes.encode(value.positionSide())) // 4: positionSide
                .number(5, value.signedQuantitySteps()) // 5: signedQuantitySteps
                .number(6, value.entryPriceTicks()) // 6: entryPriceTicks
                .number(7, value.entryValueTicks()) // 7: entryValueTicks
                .number(8, value.realizedPnlUnits()) // 8: realizedPnlUnits
                .number(9, value.positionMarginUnits()) // 9: positionMarginUnits
                .encode();
    }
    private static CorePositionState readCorePositionState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CorePositionState(
                r.text(1), // 1: instrumentId
                r.text(2), // 2: marginAsset
                SnapshotEnumCodes.readCoreMarginMode(r.integer(3)), // 3: marginMode
                SnapshotEnumCodes.readCorePositionSide(r.integer(4)), // 4: positionSide
                r.number(5), // 5: signedQuantitySteps
                r.number(6), // 6: entryPriceTicks
                r.number(7), // 7: entryValueTicks
                r.number(8), // 8: realizedPnlUnits
                r.number(9)); // 9: positionMarginUnits
    }

    private static byte[] writeCoreOrderState(CoreOrderState value) {
        return new SnapshotFields.Writer()
                .number(1, value.orderId()) // 1: orderId
                .number(2, SnapshotEnumCodes.encode(value.productLine())) // 2: productLine
                .number(3, value.userId()) // 3: userId
                .text(4, value.instrumentId()) // 4: instrumentId
                .number(5, SnapshotEnumCodes.encode(value.side())) // 5: side
                .number(6, value.priceTicks()) // 6: priceTicks
                .number(7, value.matchingPriceTicks()) // 7: matchingPriceTicks
                .number(8, value.quantitySteps()) // 8: quantitySteps
                .number(9, value.executedQuantitySteps()) // 9: executedQuantitySteps
                .number(10, value.remainingQuantitySteps()) // 10: remainingQuantitySteps
                .bool(11, value.reduceOnly()) // 11: reduceOnly
                .number(12, SnapshotEnumCodes.encode(value.marginMode())) // 12: marginMode
                .number(13, SnapshotEnumCodes.encode(value.positionSide())) // 13: positionSide
                .number(14, SnapshotEnumCodes.encode(value.orderType())) // 14: orderType
                .number(15, SnapshotEnumCodes.encode(value.timeInForce())) // 15: timeInForce
                .bool(16, value.postOnly()) // 16: postOnly
                .text(17, value.clientOrderId()) // 17: clientOrderId
                .uuid(18, value.commandId()) // 18: commandId
                .number(19, value.makerFeeRatePpm()) // 19: makerFeeRatePpm
                .number(20, value.takerFeeRatePpm()) // 20: takerFeeRatePpm
                .number(21, value.cumulativeFeeUnits()) // 21: cumulativeFeeUnits
                .number(22, value.executedValueHigh()) // 22: executedValueHigh
                .number(23, value.executedValueLow()) // 23: executedValueLow
                .number(24, value.createdAtEpochMillis()) // 24: createdAtEpochMillis
                .number(25, value.updatedAtEpochMillis()) // 25: updatedAtEpochMillis
                .number(26, value.clusterPosition()) // 26: clusterPosition
                .number(27, SnapshotEnumCodes.encode(value.status())) // 27: status
                .number(28, value.revision()) // 28: revision
                .encode();
    }
    private static CoreOrderState readCoreOrderState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreOrderState(
                r.number(1), // 1: orderId
                SnapshotEnumCodes.readProductLine(r.integer(2)), // 2: productLine
                r.number(3), // 3: userId
                r.text(4), // 4: instrumentId
                SnapshotEnumCodes.readCoreOrderSide(r.integer(5)), // 5: side
                r.number(6), // 6: priceTicks
                r.number(7), // 7: matchingPriceTicks
                r.number(8), // 8: quantitySteps
                r.number(9), // 9: executedQuantitySteps
                r.number(10), // 10: remainingQuantitySteps
                r.bool(11), // 11: reduceOnly
                SnapshotEnumCodes.readCoreMarginMode(r.integer(12)), // 12: marginMode
                SnapshotEnumCodes.readCorePositionSide(r.integer(13)), // 13: positionSide
                SnapshotEnumCodes.readCoreOrderType(r.integer(14)), // 14: orderType
                SnapshotEnumCodes.readCoreTimeInForce(r.integer(15)), // 15: timeInForce
                r.bool(16), // 16: postOnly
                r.text(17), // 17: clientOrderId
                r.uuid(18), // 18: commandId
                r.number(19), // 19: makerFeeRatePpm
                r.number(20), // 20: takerFeeRatePpm
                r.number(21), // 21: cumulativeFeeUnits
                r.number(22), // 22: executedValueHigh
                r.number(23), // 23: executedValueLow
                r.number(24), // 24: createdAtEpochMillis
                r.number(25), // 25: updatedAtEpochMillis
                r.number(26), // 26: clusterPosition
                SnapshotEnumCodes.readCoreOrderStatus(r.integer(27)), // 27: status
                r.number(28)); // 28: revision
    }

    private static byte[] writeCoreRiskSnapshot(CoreRiskSnapshot value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId()) // 1: userId
                .text(2, value.instrumentId()) // 2: instrumentId
                .number(3, SnapshotEnumCodes.encode(value.positionSide())) // 3: positionSide
                .number(4, value.priceSequence()) // 4: priceSequence
                .number(5, value.equityUnits()) // 5: equityUnits
                .number(6, value.unrealizedPnlUnits()) // 6: unrealizedPnlUnits
                .number(7, value.maintenanceMarginUnits()) // 7: maintenanceMarginUnits
                .number(8, value.marginRatioPpm()) // 8: marginRatioPpm
                .number(9, SnapshotEnumCodes.encode(value.status())) // 9: status
                .encode();
    }
    private static CoreRiskSnapshot readCoreRiskSnapshot(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskSnapshot(
                r.number(1), // 1: userId
                r.text(2), // 2: instrumentId
                SnapshotEnumCodes.readCorePositionSide(r.integer(3)), // 3: positionSide
                r.number(4), // 4: priceSequence
                r.number(5), // 5: equityUnits
                r.number(6), // 6: unrealizedPnlUnits
                r.number(7), // 7: maintenanceMarginUnits
                r.number(8), // 8: marginRatioPpm
                SnapshotEnumCodes.readCoreRiskStatus(r.integer(9))); // 9: status
    }

    private static byte[] writeCoreMarkPriceState(CoreMarkPriceState value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId()) // 1: instrumentId
                .number(2, value.markPriceTicks()) // 2: markPriceTicks
                .number(3, value.indexPriceTicks()) // 3: indexPriceTicks
                .number(4, value.forwardPriceTicks()) // 4: forwardPriceTicks
                .number(5, value.priceSequence()) // 5: priceSequence
                .number(6, value.generatedAtEpochMillis()) // 6: generatedAtEpochMillis
                .number(7, value.lastPriceTicks()) // 7: lastPriceTicks
                .encode();
    }
    private static CoreMarkPriceState readCoreMarkPriceState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreMarkPriceState(
                r.text(1), // 1: instrumentId
                r.number(2), // 2: markPriceTicks
                r.number(3), // 3: indexPriceTicks
                r.number(4), // 4: forwardPriceTicks
                r.number(5), // 5: priceSequence
                r.number(6), // 6: generatedAtEpochMillis
                r.numberOr(7, 0)); // 7: lastPriceTicks
    }

    private static byte[] writeCoreLiquidationState(CoreLiquidationState value) {
        return new SnapshotFields.Writer()
                .number(1, value.liquidationId()) // 1: liquidationId
                .number(2, value.userId()) // 2: userId
                .text(3, value.instrumentId()) // 3: instrumentId
                .number(4, SnapshotEnumCodes.encode(value.marginMode())) // 4: marginMode
                .number(5, SnapshotEnumCodes.encode(value.positionSide())) // 5: positionSide
                .number(6, value.triggerPriceSequence()) // 6: triggerPriceSequence
                .number(7, value.signedQuantitySteps()) // 7: signedQuantitySteps
                .number(8, value.closeQuantitySteps()) // 8: closeQuantitySteps
                .number(9, value.deficitUnits()) // 9: deficitUnits
                .number(10, value.executionPriceTicks()) // 10: executionPriceTicks
                .number(11, value.liquidationFeeRatePpm()) // 11: liquidationFeeRatePpm
                .number(12, value.liquidationFeeUnits()) // 12: liquidationFeeUnits
                .number(13, SnapshotEnumCodes.encode(value.status())) // 13: status
                .number(14, value.nextCancelOrderId()) // 14: nextCancelOrderId
                .encode();
    }
    private static CoreLiquidationState readCoreLiquidationState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreLiquidationState(
                r.number(1), // 1: liquidationId
                r.number(2), // 2: userId
                r.text(3), // 3: instrumentId
                SnapshotEnumCodes.readCoreMarginMode(r.integer(4)), // 4: marginMode
                SnapshotEnumCodes.readCorePositionSide(r.integer(5)), // 5: positionSide
                r.number(6), // 6: triggerPriceSequence
                r.number(7), // 7: signedQuantitySteps
                r.number(8), // 8: closeQuantitySteps
                r.number(9), // 9: deficitUnits
                r.number(10), // 10: executionPriceTicks
                r.number(11), // 11: liquidationFeeRatePpm
                r.number(12), // 12: liquidationFeeUnits
                SnapshotEnumCodes.readCoreLiquidationStateStatus(r.integer(13)), // 13: status
                r.number(14)); // 14: nextCancelOrderId
    }

    private static byte[] writeRiskLaneProgress(RiskLaneProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.lastUserId()) // 1: lastUserId
                .bool(2, value.complete()) // 2: complete
                .number(3, value.userId()) // 3: userId
                .number(4, value.phase()) // 4: phase
                .text(5, value.positionCursor()) // 5: positionCursor
                .number(6, value.reservationCursor()) // 6: reservationCursor
                .number(7, value.unrealizedPnlUnits()) // 7: unrealizedPnlUnits
                .number(8, value.maintenanceMarginUnits()) // 8: maintenanceMarginUnits
                .number(9, value.isolatedMarginUnits()) // 9: isolatedMarginUnits
                .number(10, value.isolatedReservationUnits()) // 10: isolatedReservationUnits
                .number(11, value.userRevision()) // 11: userRevision
                .number(12, value.marketRevision()) // 12: marketRevision
                .encode();
    }
    private static RiskLaneProgress readRiskLaneProgress(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new RiskLaneProgress(
                r.number(1), // 1: lastUserId
                r.bool(2), // 2: complete
                r.number(3), // 3: userId
                r.integer(4), // 4: phase
                r.text(5), // 5: positionCursor
                r.number(6), // 6: reservationCursor
                r.number(7), // 7: unrealizedPnlUnits
                r.number(8), // 8: maintenanceMarginUnits
                r.number(9), // 9: isolatedMarginUnits
                r.number(10), // 10: isolatedReservationUnits
                r.number(11), // 11: userRevision
                r.number(12)); // 12: marketRevision
    }

    private static byte[] writeCoreRiskScanControlView(CoreRiskScanControlView value) {
        return new SnapshotFields.Writer()
                .number(1, value.version()) // 1: version
                .text(2, value.ruleName()) // 2: ruleName
                .bool(3, value.enabled()) // 3: enabled
                .number(4, value.scanDelayMs()) // 4: scanDelayMs
                .number(5, value.scanBatchSize()) // 5: scanBatchSize
                .text(6, value.updatedBy()) // 6: updatedBy
                .text(7, value.reason()) // 7: reason
                .number(8, value.updatedAtEpochMillis()) // 8: updatedAtEpochMillis
                .encode();
    }
    private static CoreRiskScanControlView readCoreRiskScanControlView(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskScanControlView(
                r.number(1), // 1: version
                r.text(2), // 2: ruleName
                r.bool(3), // 3: enabled
                r.number(4), // 4: scanDelayMs
                r.integer(5), // 5: scanBatchSize
                r.text(6), // 6: updatedBy
                r.text(7), // 7: reason
                r.number(8)); // 8: updatedAtEpochMillis
    }

    private static byte[] writeCoreRiskLimitBracket(CoreRiskLimitBracket value) {
        return new SnapshotFields.Writer()
                .number(1, value.bracketNo()) // 1: bracketNo
                .number(2, value.notionalFloorUnits()) // 2: notionalFloorUnits
                .number(3, value.notionalCapUnits()) // 3: notionalCapUnits
                .number(4, value.maxLeveragePpm()) // 4: maxLeveragePpm
                .number(5, value.initialMarginRatePpm()) // 5: initialMarginRatePpm
                .number(6, value.maintenanceMarginRatePpm()) // 6: maintenanceMarginRatePpm
                .number(7, value.optionMarginFactorPpm()) // 7: optionMarginFactorPpm
                .encode();
    }
    private static CoreRiskLimitBracket readCoreRiskLimitBracket(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskLimitBracket(
                r.integer(1), // 1: bracketNo
                r.number(2), // 2: notionalFloorUnits
                r.number(3), // 3: notionalCapUnits
                r.number(4), // 4: maxLeveragePpm
                r.number(5), // 5: initialMarginRatePpm
                r.number(6), // 6: maintenanceMarginRatePpm
                r.number(7)); // 7: optionMarginFactorPpm
    }

    private static byte[] writeCoreInstrumentMaintenance(CoreInstrumentMaintenance value) {
        return new SnapshotFields.Writer()
                .number(1, value.taskId()) // 1: taskId
                .number(2, SnapshotEnumCodes.encode(value.mode())) // 2: mode
                .number(3, value.settlementPriceTicks()) // 3: settlementPriceTicks
                .encode();
    }
    private static CoreInstrumentMaintenance readCoreInstrumentMaintenance(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreInstrumentMaintenance(
                r.number(1), // 1: taskId
                SnapshotEnumCodes.readCoreInstrumentMaintenanceMode(r.integer(2)), // 2: mode
                r.number(3)); // 3: settlementPriceTicks
    }

    private static byte[] writeCoreAlgoOrderState(CoreAlgoOrderState value) {
        return new SnapshotFields.Writer()
                .number(1, value.algoOrderId()) // 1: algoOrderId
                .number(2, value.userId()) // 2: userId
                .text(3, value.clientAlgoOrderId()) // 3: clientAlgoOrderId
                .text(4, value.instrumentId()) // 4: instrumentId
                .number(5, value.algoTypeCode()) // 5: algoTypeCode
                .number(6, SnapshotEnumCodes.encode(value.side())) // 6: side
                .number(7, value.priceTicks()) // 7: priceTicks
                .number(8, value.quantitySteps()) // 8: quantitySteps
                .number(9, value.childQuantitySteps()) // 9: childQuantitySteps
                .number(10, value.intervalSeconds()) // 10: intervalSeconds
                .number(11, value.durationSeconds()) // 11: durationSeconds
                .number(12, SnapshotEnumCodes.encode(value.marginMode())) // 12: marginMode
                .number(13, SnapshotEnumCodes.encode(value.positionSide())) // 13: positionSide
                .bool(14, value.reduceOnly()) // 14: reduceOnly
                .bool(15, value.postOnly()) // 15: postOnly
                .number(16, SnapshotEnumCodes.encode(value.timeInForce())) // 16: timeInForce
                .number(17, value.statusCode()) // 17: statusCode
                .number(18, value.currentOrderId()) // 18: currentOrderId
                .text(19, value.rejectReason()) // 19: rejectReason
                .text(20, value.traceId()) // 20: traceId
                .number(21, value.startAtEpochMillis()) // 21: startAtEpochMillis
                .number(22, value.nextSliceAtEpochMillis()) // 22: nextSliceAtEpochMillis
                .number(23, value.completedAtEpochMillis()) // 23: completedAtEpochMillis
                .number(24, value.createdAtEpochMillis()) // 24: createdAtEpochMillis
                .number(25, value.updatedAtEpochMillis()) // 25: updatedAtEpochMillis
                .number(26, value.revision()) // 26: revision
                .list(27, value.childOrderIds(), item -> new SnapshotFields.Writer().number(1, item).encode()) // 27: childOrderIds
                .encode();
    }
    private static CoreAlgoOrderState readCoreAlgoOrderState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreAlgoOrderState(
                r.number(1), // 1: algoOrderId
                r.number(2), // 2: userId
                r.text(3), // 3: clientAlgoOrderId
                r.text(4), // 4: instrumentId
                r.integer(5), // 5: algoTypeCode
                SnapshotEnumCodes.readCoreOrderSide(r.integer(6)), // 6: side
                r.number(7), // 7: priceTicks
                r.number(8), // 8: quantitySteps
                r.number(9), // 9: childQuantitySteps
                r.number(10), // 10: intervalSeconds
                r.number(11), // 11: durationSeconds
                SnapshotEnumCodes.readCoreMarginMode(r.integer(12)), // 12: marginMode
                SnapshotEnumCodes.readCorePositionSide(r.integer(13)), // 13: positionSide
                r.bool(14), // 14: reduceOnly
                r.bool(15), // 15: postOnly
                SnapshotEnumCodes.readCoreTimeInForce(r.integer(16)), // 16: timeInForce
                r.integer(17), // 17: statusCode
                r.number(18), // 18: currentOrderId
                r.text(19), // 19: rejectReason
                r.text(20), // 20: traceId
                r.number(21), // 21: startAtEpochMillis
                r.number(22), // 22: nextSliceAtEpochMillis
                r.number(23), // 23: completedAtEpochMillis
                r.number(24), // 24: createdAtEpochMillis
                r.number(25), // 25: updatedAtEpochMillis
                r.number(26), // 26: revision
                r.list(27, item -> new SnapshotFields.Reader(item).number(1))); // 27: childOrderIds
    }

    private static byte[] writeCoreCancelAllAfterState(CoreCancelAllAfterState value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId()) // 1: userId
                .text(2, value.symbolScope()) // 2: symbolScope
                .number(3, value.countdownMillis()) // 3: countdownMillis
                .number(4, SnapshotEnumCodes.encode(value.status())) // 4: status
                .number(5, value.triggerAtEpochMillis()) // 5: triggerAtEpochMillis
                .number(6, value.updatedAtEpochMillis()) // 6: updatedAtEpochMillis
                .number(7, value.canceledOrders()) // 7: canceledOrders
                .number(8, value.canceledTriggerOrders()) // 8: canceledTriggerOrders
                .number(9, value.revision()) // 9: revision
                .encode();
    }
    private static CoreCancelAllAfterState readCoreCancelAllAfterState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreCancelAllAfterState(
                r.number(1), // 1: userId
                r.text(2), // 2: symbolScope
                r.number(3), // 3: countdownMillis
                SnapshotEnumCodes.readCoreCancelAllAfterStatus(r.integer(4)), // 4: status
                r.number(5), // 5: triggerAtEpochMillis
                r.number(6), // 6: updatedAtEpochMillis
                r.integer(7), // 7: canceledOrders
                r.integer(8), // 8: canceledTriggerOrders
                r.number(9)); // 9: revision
    }

    private static byte[] writeCoreLeverageKey(CoreLeverageKey value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId()) // 1: userId
                .text(2, value.instrumentId()) // 2: instrumentId
                .number(3, SnapshotEnumCodes.encode(value.marginMode())) // 3: marginMode
                .encode();
    }
    private static CoreLeverageKey readCoreLeverageKey(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreLeverageKey(
                r.number(1), // 1: userId
                r.text(2), // 2: instrumentId
                SnapshotEnumCodes.readCoreMarginMode(r.integer(3))); // 3: marginMode
    }

    private static byte[] writeCoreTriggerOrderStateView(CoreTriggerOrderStateView value) {
        return new SnapshotFields.Writer()
                .number(1, value.triggerOrderId()) // 1: triggerOrderId
                .number(2, SnapshotEnumCodes.encode(value.productLine())) // 2: productLine
                .number(3, value.userId()) // 3: userId
                .text(4, value.clientTriggerOrderId()) // 4: clientTriggerOrderId
                .text(5, value.ocoGroupId()) // 5: ocoGroupId
                .text(6, value.instrumentId()) // 6: instrumentId
                .number(7, SnapshotEnumCodes.encode(value.side())) // 7: side
                .number(8, SnapshotEnumCodes.encode(value.triggerType())) // 8: triggerType
                .number(9, SnapshotEnumCodes.encode(value.triggerCondition())) // 9: triggerCondition
                .number(10, value.triggerPriceTicks()) // 10: triggerPriceTicks
                .number(11, value.activationPriceTicks()) // 11: activationPriceTicks
                .number(12, value.callbackRatePpm()) // 12: callbackRatePpm
                .number(13, value.highestPriceTicks()) // 13: highestPriceTicks
                .number(14, value.lowestPriceTicks()) // 14: lowestPriceTicks
                .number(15, value.activatedAtEpochMillis()) // 15: activatedAtEpochMillis
                .number(16, SnapshotEnumCodes.encode(value.orderType())) // 16: orderType
                .number(17, SnapshotEnumCodes.encode(value.timeInForce())) // 17: timeInForce
                .number(18, value.priceTicks()) // 18: priceTicks
                .number(19, value.quantitySteps()) // 19: quantitySteps
                .number(20, SnapshotEnumCodes.encode(value.marginMode())) // 20: marginMode
                .number(21, SnapshotEnumCodes.encode(value.positionSide())) // 21: positionSide
                .number(22, SnapshotEnumCodes.encode(value.status())) // 22: status
                .number(23, value.placedOrderId()) // 23: placedOrderId
                .number(24, value.triggerSequence()) // 24: triggerSequence
                .number(25, value.triggeredPriceTicks()) // 25: triggeredPriceTicks
                .text(26, value.rejectReason()) // 26: rejectReason
                .text(27, value.traceId()) // 27: traceId
                .number(28, value.expiresAtEpochMillis()) // 28: expiresAtEpochMillis
                .number(29, value.triggeredAtEpochMillis()) // 29: triggeredAtEpochMillis
                .number(30, value.createdAtEpochMillis()) // 30: createdAtEpochMillis
                .number(31, value.updatedAtEpochMillis()) // 31: updatedAtEpochMillis
                .number(32, value.revision()) // 32: revision
                .number(33, value.makerFeeRatePpm()) // 33: makerFeeRatePpm
                .number(34, value.takerFeeRatePpm()) // 34: takerFeeRatePpm
                .number(35, SnapshotEnumCodes.encode(value.priceSource())) // 35: priceSource
                .encode();
    }
    private static CoreTriggerOrderStateView readCoreTriggerOrderStateView(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreTriggerOrderStateView(
                r.number(1), // 1: triggerOrderId
                SnapshotEnumCodes.readProductLine(r.integer(2)), // 2: productLine
                r.number(3), // 3: userId
                r.text(4), // 4: clientTriggerOrderId
                r.text(5), // 5: ocoGroupId
                r.text(6), // 6: instrumentId
                SnapshotEnumCodes.readCoreOrderSide(r.integer(7)), // 7: side
                SnapshotEnumCodes.readCoreTriggerOrderType(r.integer(8)), // 8: triggerType
                SnapshotEnumCodes.readCoreTriggerCondition(r.integer(9)), // 9: triggerCondition
                r.number(10), // 10: triggerPriceTicks
                r.number(11), // 11: activationPriceTicks
                r.number(12), // 12: callbackRatePpm
                r.number(13), // 13: highestPriceTicks
                r.number(14), // 14: lowestPriceTicks
                r.number(15), // 15: activatedAtEpochMillis
                SnapshotEnumCodes.readCoreOrderType(r.integer(16)), // 16: orderType
                SnapshotEnumCodes.readCoreTimeInForce(r.integer(17)), // 17: timeInForce
                r.number(18), // 18: priceTicks
                r.number(19), // 19: quantitySteps
                SnapshotEnumCodes.readCoreMarginMode(r.integer(20)), // 20: marginMode
                SnapshotEnumCodes.readCorePositionSide(r.integer(21)), // 21: positionSide
                SnapshotEnumCodes.readCoreTriggerOrderStatus(r.integer(22)), // 22: status
                r.number(23), // 23: placedOrderId
                r.number(24), // 24: triggerSequence
                r.number(25), // 25: triggeredPriceTicks
                r.text(26), // 26: rejectReason
                r.text(27), // 27: traceId
                r.number(28), // 28: expiresAtEpochMillis
                r.number(29), // 29: triggeredAtEpochMillis
                r.number(30), // 30: createdAtEpochMillis
                r.number(31), // 31: updatedAtEpochMillis
                r.number(32), // 32: revision
                r.number(33), // 33: makerFeeRatePpm
                r.number(34), // 34: takerFeeRatePpm
                SnapshotEnumCodes.readCoreTriggerPriceSource(r.integer(35))); // 35: priceSource
    }

    private static byte[] writeRiskScan(RiskScan value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId()) // 1: instrumentId
                .number(2, value.accountLaneId()) // 2: accountLaneId
                .number(3, value.priceSequence()) // 3: priceSequence
                .number(4, value.scanStartPriceSequence()) // 4: scanStartPriceSequence
                .number(5, value.lastUserId()) // 5: lastUserId
                .bool(6, value.riskComplete()) // 6: riskComplete
                .number(7, value.riskUserId()) // 7: riskUserId
                .number(8, value.riskPhase()) // 8: riskPhase
                .text(9, value.riskPositionCursor()) // 9: riskPositionCursor
                .number(10, value.riskReservationCursor()) // 10: riskReservationCursor
                .number(11, value.riskUnrealizedPnlUnits()) // 11: riskUnrealizedPnlUnits
                .number(12, value.riskMaintenanceMarginUnits()) // 12: riskMaintenanceMarginUnits
                .number(13, value.riskIsolatedMarginUnits()) // 13: riskIsolatedMarginUnits
                .number(14, value.riskIsolatedReservationUnits()) // 14: riskIsolatedReservationUnits
                .bool(15, value.triggerComplete()) // 15: triggerComplete
                .number(16, value.triggerPhase()) // 16: triggerPhase
                .number(17, value.triggerPriceCursor()) // 17: triggerPriceCursor
                .number(18, value.triggerOrderCursor()) // 18: triggerOrderCursor
                .number(19, value.triggerUpperId()) // 19: triggerUpperId
                .number(20, value.triggerMarkPriceTicks()) // 20: triggerMarkPriceTicks
                .number(21, value.triggerGeneratedAtEpochMillis()) // 21: triggerGeneratedAtEpochMillis
                .number(22, value.triggerOcoOrderId()) // 22: triggerOcoOrderId
                .number(23, value.triggerOcoCursor()) // 23: triggerOcoCursor
                .number(24, value.lastScheduledRevision()) // 24: lastScheduledRevision
                .list(25, value.laneProgress(), item -> writeRiskLaneProgress(item)) // 25: laneProgress
                .encode();
    }
    private static RiskScan readRiskScan(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new RiskScan(
                r.text(1), // 1: instrumentId
                r.integer(2), // 2: accountLaneId
                r.number(3), // 3: priceSequence
                r.number(4), // 4: scanStartPriceSequence
                r.number(5), // 5: lastUserId
                r.bool(6), // 6: riskComplete
                r.number(7), // 7: riskUserId
                r.integer(8), // 8: riskPhase
                r.text(9), // 9: riskPositionCursor
                r.number(10), // 10: riskReservationCursor
                r.number(11), // 11: riskUnrealizedPnlUnits
                r.number(12), // 12: riskMaintenanceMarginUnits
                r.number(13), // 13: riskIsolatedMarginUnits
                r.number(14), // 14: riskIsolatedReservationUnits
                r.bool(15), // 15: triggerComplete
                r.integer(16), // 16: triggerPhase
                r.number(17), // 17: triggerPriceCursor
                r.number(18), // 18: triggerOrderCursor
                r.number(19), // 19: triggerUpperId
                r.number(20), // 20: triggerMarkPriceTicks
                r.number(21), // 21: triggerGeneratedAtEpochMillis
                r.number(22), // 22: triggerOcoOrderId
                r.number(23), // 23: triggerOcoCursor
                r.number(24), // 24: lastScheduledRevision
                r.list(25, item -> readRiskLaneProgress(item))); // 25: laneProgress
    }

    private static byte[] writeFundingProgress(FundingProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.settlementId()) // 1: settlementId
                .number(2, value.fundingRatePpm()) // 2: fundingRatePpm
                .number(3, value.accountLaneId()) // 3: accountLaneId
                .number(4, value.nextCursorUserId()) // 4: nextCursorUserId
                .uuid(5, value.commandId()) // 5: commandId
                .number(6, value.markPriceTicks()) // 6: markPriceTicks
                .number(7, value.priceSequence()) // 7: priceSequence
                .encode();
    }
    private static FundingProgress readFundingProgress(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new FundingProgress(
                r.number(1), // 1: settlementId
                r.number(2), // 2: fundingRatePpm
                r.integer(3), // 3: accountLaneId
                r.number(4), // 4: nextCursorUserId
                r.uuid(5), // 5: commandId
                r.number(6), // 6: markPriceTicks
                r.number(7)); // 7: priceSequence
    }

    private static byte[] writeLifecycleProgress(LifecycleProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.settlementId()) // 1: settlementId
                .number(2, value.settlementPriceTicks()) // 2: settlementPriceTicks
                .number(3, value.optionCashUnitsPerContract()) // 3: optionCashUnitsPerContract
                .bool(4, value.ordersComplete()) // 4: ordersComplete
                .number(5, value.accountLaneId()) // 5: accountLaneId
                .number(6, value.nextCursorOrderId()) // 6: nextCursorOrderId
                .number(7, value.nextCursorUserId()) // 7: nextCursorUserId
                .uuid(8, value.commandId()) // 8: commandId
                .number(9, value.requiredInsuranceUnits()) // 9: requiredInsuranceUnits
                .encode();
    }
    private static LifecycleProgress readLifecycleProgress(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new LifecycleProgress(
                r.number(1), // 1: settlementId
                r.number(2), // 2: settlementPriceTicks
                r.number(3), // 3: optionCashUnitsPerContract
                r.bool(4), // 4: ordersComplete
                r.integer(5), // 5: accountLaneId
                r.number(6), // 6: nextCursorOrderId
                r.number(7), // 7: nextCursorUserId
                r.uuid(8), // 8: commandId
                r.number(9)); // 9: requiredInsuranceUnits
    }

    private static byte[] writeCoreUserState(CoreUserState value) {
        return new SnapshotFields.Writer()
                .number(1, SnapshotEnumCodes.encode(value.productLine())) // 1: productLine
                .number(2, value.userId()) // 2: userId
                .number(3, value.revision()) // 3: revision
                .bytes(4, writeMap(value.balances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeAssetBalance(item))) // 4: balances
                .bytes(5, writeMap(value.reservations(), key -> new SnapshotFields.Writer().number(1, key).encode(), item -> writeOrderReservation(item))) // 5: reservations
                .bytes(6, writeMap(value.positions(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeCorePositionState(item))) // 6: positions
                .number(7, SnapshotEnumCodes.encode(value.positionMode())) // 7: positionMode
                .encode();
    }
    private static CoreUserState readCoreUserState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreUserState(
                SnapshotEnumCodes.readProductLine(r.integer(1)), // 1: productLine
                r.number(2), // 2: userId
                r.number(3), // 3: revision
                readMap(r.bytes(4), key -> new SnapshotFields.Reader(key).text(1), item -> readAssetBalance(item)), // 4: balances
                readMap(r.bytes(5), key -> new SnapshotFields.Reader(key).number(1), item -> readOrderReservation(item)), // 5: reservations
                readMap(r.bytes(6), key -> new SnapshotFields.Reader(key).text(1), item -> readCorePositionState(item)), // 6: positions
                SnapshotEnumCodes.readCorePositionMode(r.integer(7))); // 7: positionMode
    }

    private static byte[] writeCoreInstrument(CoreInstrument value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId()) // 1: instrumentId
                .number(2, SnapshotEnumCodes.encode(value.contractType())) // 2: contractType
                .text(3, value.baseAsset()) // 3: baseAsset
                .text(4, value.quoteAsset()) // 4: quoteAsset
                .text(5, value.settleAsset()) // 5: settleAsset
                .number(6, value.notionalMultiplierUnits()) // 6: notionalMultiplierUnits
                .number(7, value.priceTickUnits()) // 7: priceTickUnits
                .number(8, value.settleScaleUnits()) // 8: settleScaleUnits
                .number(9, value.initialMarginRatePpm()) // 9: initialMarginRatePpm
                .number(10, value.maintenanceMarginRatePpm()) // 10: maintenanceMarginRatePpm
                .number(11, value.makerFeeRatePpm()) // 11: makerFeeRatePpm
                .number(12, value.takerFeeRatePpm()) // 12: takerFeeRatePpm
                .number(13, value.expiryEpochMillis()) // 13: expiryEpochMillis
                .number(14, value.optionType() == null ? -1 : SnapshotEnumCodes.encode(value.optionType())) // 14: optionType
                .number(15, value.strikePriceTicks()) // 15: strikePriceTicks
                .number(16, value.maxLeveragePpm()) // 16: maxLeveragePpm
                .number(17, value.maxPositionNotionalUnits()) // 17: maxPositionNotionalUnits
                .number(18, value.userOpenInterestLimitRatePpm()) // 18: userOpenInterestLimitRatePpm
                .number(19, value.userOpenInterestLimitFloorUnits()) // 19: userOpenInterestLimitFloorUnits
                .list(20, value.riskLimitBrackets(), item -> writeCoreRiskLimitBracket(item)) // 20: riskLimitBrackets
                .bytes(21, writeCoreInstrumentMaintenance(value.maintenance())) // 21: maintenance
                .number(22, SnapshotEnumCodes.encode(value.instrumentStatus())) // 22: instrumentStatus
                .bool(23, value.marketOrderEnabled()) // 23: marketOrderEnabled
                .bool(24, value.postOnlyEnabled()) // 24: postOnlyEnabled
                .bool(25, value.reduceOnlyEnabled()) // 25: reduceOnlyEnabled
                .number(26, value.supportedOrderTypeMask()) // 26: supportedOrderTypeMask
                .number(27, value.supportedTimeInForceMask()) // 27: supportedTimeInForceMask
                .number(28, value.quantityStepUnits()) // 28: base asset units per quantity step
                .number(29, value.orderProtection().marketMaxSlippagePpm())
                .number(30, value.orderProtection().marketMaxMarkAgeMs())
                .number(31, value.orderProtection().limitPriceProtectionEnabled() ? 1 : 0)
                .number(32, value.orderProtection().limitPriceBandPpm())
                .number(33, value.orderProtection().limitPriceMaxMarkAgeMs())
                .encode();
    }
    private static CoreInstrument readCoreInstrument(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreInstrument(
                r.text(1), // 1: instrumentId
                SnapshotEnumCodes.readContractType(r.integer(2)), // 2: contractType
                r.text(3), // 3: baseAsset
                r.text(4), // 4: quoteAsset
                r.text(5), // 5: settleAsset
                r.number(6), // 6: notionalMultiplierUnits
                r.number(7), // 7: priceTickUnits
                r.number(8), // 8: settleScaleUnits
                r.number(9), // 9: initialMarginRatePpm
                r.number(10), // 10: maintenanceMarginRatePpm
                r.number(11), // 11: makerFeeRatePpm
                r.number(12), // 12: takerFeeRatePpm
                r.number(13), // 13: expiryEpochMillis
                r.integer(14) == -1 ? null : SnapshotEnumCodes.readOptionType(r.integer(14)), // 14: optionType
                r.number(15), // 15: strikePriceTicks
                r.number(16), // 16: maxLeveragePpm
                r.number(17), // 17: maxPositionNotionalUnits
                r.number(18), // 18: userOpenInterestLimitRatePpm
                r.number(19), // 19: userOpenInterestLimitFloorUnits
                r.list(20, item -> readCoreRiskLimitBracket(item)), // 20: riskLimitBrackets
                readCoreInstrumentMaintenance(r.bytes(21)), // 21: maintenance
                SnapshotEnumCodes.readInstrumentStatus(r.integer(22)), // 22: instrumentStatus
                r.bool(23), // 23: marketOrderEnabled
                r.bool(24), // 24: postOnlyEnabled
                r.bool(25), // 25: reduceOnlyEnabled
                r.integer(26), // 26: supportedOrderTypeMask
                r.integer(27), // 27: supportedTimeInForceMask
                r.numberOr(28, 1L), // Existing snapshots used unit quantities.
                new com.surprising.aeron.protocol.CoreOrderProtection(r.numberOr(29, 10_000), r.numberOr(30, 5_000),
                        r.numberOr(31, 0) != 0, r.numberOr(32, 50_000), r.numberOr(33, 5_000)));
    }

    private static byte[] writeCoreRiskState(CoreRiskState value) {
        return new SnapshotFields.Writer()
                .bytes(1, writeMap(value.markPrices(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeCoreMarkPriceState(item))) // 1: markPrices
                .bytes(2, writeMap(value.snapshots(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeCoreRiskSnapshot(item))) // 2: snapshots
                .bytes(3, writeMap(value.liquidations(), key -> new SnapshotFields.Writer().number(1, key).encode(), item -> writeCoreLiquidationState(item))) // 3: liquidations
                .bytes(4, writeMap(value.scans(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeRiskScan(item))) // 4: scans
                .number(5, value.nextLiquidationId()) // 5: nextLiquidationId
                .bytes(6, writeCoreRiskScanControlView(value.scanControl())) // 6: scanControl
                .number(7, value.marketRevision()) // 7: marketRevision
                .encode();
    }
    private static CoreRiskState readCoreRiskState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskState(
                readMap(r.bytes(1), key -> new SnapshotFields.Reader(key).text(1), item -> readCoreMarkPriceState(item)), // 1: markPrices
                readMap(r.bytes(2), key -> new SnapshotFields.Reader(key).text(1), item -> readCoreRiskSnapshot(item)), // 2: snapshots
                readMap(r.bytes(3), key -> new SnapshotFields.Reader(key).number(1), item -> readCoreLiquidationState(item)), // 3: liquidations
                readMap(r.bytes(4), key -> new SnapshotFields.Reader(key).text(1), item -> readRiskScan(item)), // 4: scans
                r.number(5), // 5: nextLiquidationId
                readCoreRiskScanControlView(r.bytes(6)), // 6: scanControl
                r.number(7)); // 7: marketRevision
    }

    private static byte[] writeCoreTreasuryState(CoreTreasuryState value) {
        return new SnapshotFields.Writer()
                .bytes(1, writeMap(value.feeBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 1: feeBalances
                .bytes(2, writeMap(value.insuranceBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 2: insuranceBalances
                .bytes(3, writeMap(value.insuranceDeficits(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 3: insuranceDeficits
                .bytes(4, writeMap(value.liquidationFeeBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 4: liquidationFeeBalances
                .bytes(5, writeMap(value.fundingResidualBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 5: fundingResidualBalances
                .bytes(6, writeMap(value.roundingResidualBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 6: roundingResidualBalances
                .bytes(7, writeMap(value.clearingPnlBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 7: clearingPnlBalances
                .bytes(8, writeMap(value.fundingSettlements(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 8: fundingSettlements
                .bytes(9, writeMap(value.lifecycleSettlements(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode())) // 9: lifecycleSettlements
                .bytes(10, writeMap(value.fundingProgress(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeFundingProgress(item))) // 10: fundingProgress
                .bytes(11, writeMap(value.lifecycleProgress(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeLifecycleProgress(item))) // 11: lifecycleProgress
                .encode();
    }
    private static CoreTreasuryState readCoreTreasuryState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreTreasuryState(
                readMap(r.bytes(1), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 1: feeBalances
                readMap(r.bytes(2), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 2: insuranceBalances
                readMap(r.bytes(3), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 3: insuranceDeficits
                readMap(r.bytes(4), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 4: liquidationFeeBalances
                readMap(r.bytes(5), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 5: fundingResidualBalances
                readMap(r.bytes(6), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 6: roundingResidualBalances
                readMap(r.bytes(7), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 7: clearingPnlBalances
                readMap(r.bytes(8), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 8: fundingSettlements
                readMap(r.bytes(9), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)), // 9: lifecycleSettlements
                readMap(r.bytes(10), key -> new SnapshotFields.Reader(key).text(1), item -> readFundingProgress(item)), // 10: fundingProgress
                readMap(r.bytes(11), key -> new SnapshotFields.Reader(key).text(1), item -> readLifecycleProgress(item))); // 11: lifecycleProgress
    }
}
