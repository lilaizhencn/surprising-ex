package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskSnapshot;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreRiskState.RiskScan;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.UserAccountSnapshot;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.CoreTreasuryState.FundingProgress;
import com.surprising.aeron.service.treasury.CoreTreasuryState.LifecycleProgress;
import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 交易状态的独立持久化格式。新快照外层版本五十六，内部各业务记录以固定字段编号和长度编码。字段编号只增不复用，Java 类名、声明顺序及接口 DTO 不决定磁盘布局。只读取当前版本，不兼容变化统一提升外层版本，未知合法字段按长度跳过而不参与当前恢复。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TradingStateSnapshotCodec {
    /** 本编码器新写入的外层快照布局版本。 */
    private static final int VERSION = 56;

    /** 把交易状态各业务表和独立记录写成编号字段，外层加当前小端版本头，不兼容变化统一提升外层版本。 */
    public static byte[] encode(TradingCoreState value) {
        byte[] fields = new SnapshotFields.Writer()
                .number(1, SnapshotEnumCodes.encode(value.productLine()))
                .number(2, value.revision())
                .bytes(3, writeValues(value.users(), TradingStateSnapshotCodec::writeCoreUserState))
                .bytes(4, writeValues(value.orders(), TradingStateSnapshotCodec::writeCoreOrderState))
                .bytes(5, writeValues(value.instruments(), item -> writeCoreInstrument(item, value.productLine())))
                .bytes(6, writeCoreRiskState(value.riskState(), value.productLine()))
                .bytes(7, writeCoreTreasuryState(value.treasuryState()))
                .bytes(8, writeMap(value.leverages(), TradingStateSnapshotCodec::writeCoreLeverageKey, item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(9, writeValues(value.algoOrders(), TradingStateSnapshotCodec::writeCoreAlgoOrderState))
                .list(10, value.cancelAllAfterTimers().values(), TradingStateSnapshotCodec::writeCoreCancelAllAfterState)
                .list(11, value.triggerOrders().values(), TradingStateSnapshotCodec::writeCoreTriggerOrderState).encode();
        return ByteBuffer.allocate(4 + fields.length).order(ByteOrder.LITTLE_ENDIAN).putInt(VERSION).put(fields).array();
    }

    /** 先拒绝非当前外层版本，再核对产品线，再恢复用户、订单、币对、风险、财库等。触发单必须引用已恢复币对。 */
    public static TradingCoreState decode(byte[] encoded, ProductLine expectedProductLine) {
        if (encoded == null || encoded.length < 4) throw new ProtocolException("truncated trading snapshot");
        int version = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (version != VERSION) throw new ProtocolException("unsupported trading snapshot version: " + version);
        try {
            var r = new SnapshotFields.Reader(Arrays.copyOfRange(encoded, 4, encoded.length));
            ProductLine product = SnapshotEnumCodes.readProductLine(r.integer(1));
            if (product != expectedProductLine) throw new ProtocolException("trading snapshot product line mismatch");
            Map<Long, UserAccountSnapshot> users = readValues(r.bytes(3), item -> readCoreUserState(item, product),
                    UserAccountSnapshot::userId);
            Map<Long, CoreOrderState> orders = readValues(r.bytes(4), item -> readCoreOrderState(item, product),
                    CoreOrderState::orderId);
            Map<String, CoreInstrument> instruments = readValues(r.bytes(5), item -> readCoreInstrument(item, product), CoreInstrument::instrumentId);
            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = new TreeMap<>();
            for (var timer : r.list(10, TradingStateSnapshotCodec::readCoreCancelAllAfterState)) putUnique(timers, timer.key(), timer);
            Map<Long, CoreTriggerOrderState> triggers = new TreeMap<>();
            for (var trigger : r.list(11, item -> readCoreTriggerOrderState(item, product, instruments))) {
                putUnique(triggers, trigger.triggerOrderId(), trigger);
            }
            return new TradingCoreState(product, r.number(2), users, orders, instruments,
                    readCoreRiskState(r.bytes(6), product), readCoreTreasuryState(r.bytes(7)),
                    readMap(r.bytes(8), TradingStateSnapshotCodec::readCoreLeverageKey, item -> new SnapshotFields.Reader(item).number(1)),
                    readValues(r.bytes(9), TradingStateSnapshotCodec::readCoreAlgoOrderState, CoreAlgoOrderState::algoOrderId), timers, triggers);
        } catch (ProtocolException invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw new ProtocolException("invalid trading snapshot: " + invalid.getMessage(), invalid); }
    }

    /** 正文已携带身份的表只保存值，恢复时重建原索引并拒绝重复身份。 */
    private static <K, V> byte[] writeValues(Map<K, V> values, Function<V, byte[]> encode) {
        return new SnapshotFields.Writer().list(1, values.values(), encode).encode();
    }

    private static <K, V> Map<K, V> readValues(byte[] encoded, Function<byte[], V> decode,
                                              Function<V, K> key) {
        Map<K, V> values = new TreeMap<>();
        for (V value : new SnapshotFields.Reader(encoded).list(1, decode))
            putUnique(values, key.apply(value), value);
        return values;
    }

    /** 按输入 Map 当前条目顺序逐项写独立键和值记录，不自行排序。调用方状态表负责确定性顺序。 */
    private static <K, V> byte[] writeMap(Map<K, V> values, Function<K, byte[]> key, Function<V, byte[]> value) {
        return new SnapshotFields.Writer().list(1, values.entrySet(), entry -> new SnapshotFields.Writer()
                .bytes(1, key.apply(entry.getKey())).bytes(2, value.apply(entry.getValue())).encode()).encode();
    }
    /** 逐项解码键值记录并写入 TreeMap，按键恢复有序结果，重复的非空业务值键拒绝。 */
    private static <K, V> Map<K, V> readMap(byte[] encoded, Function<byte[], K> key, Function<byte[], V> value) {
        Map<K, V> values = new TreeMap<>();
        for (var entry : new SnapshotFields.Reader(encoded).list(1, SnapshotFields.Reader::new))
            putUnique(values, key.apply(entry.bytes(1)), value.apply(entry.bytes(2)));
        return values;
    }
    /** 发现重复即终止恢复，不返回部分结果。 */
    private static <K, V> void putUnique(Map<K, V> values, K key, V value) {
        if (values.put(key, value) != null) throw new ProtocolException("duplicate trading snapshot key: " + key);
    }

    private static byte[] writeAssetBalance(AssetBalance value) {
        return new SnapshotFields.Writer()
                .text(1, value.asset())
                .number(2, value.availableUnits())
                .number(3, value.lockedUnits())
                .encode();
    }
    /** 按固定字段编号读取并构造资产余额，恢复资产名称、可用额和冻结额。 */
    private static AssetBalance readAssetBalance(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new AssetBalance(
                r.text(1),
                r.number(2),
                r.number(3));
    }

    private static byte[] writeOrderReservation(OrderFundsReservation.Snapshot value) {
        return new SnapshotFields.Writer()
                .number(1, value.orderId())
                .text(2, value.instrumentId())
                .number(3, SnapshotEnumCodes.encode(value.kind()))
                .text(4, value.asset())
                .number(5, value.reservedUnits())
                .number(6, value.releasedUnits())
                .number(7, value.consumedUnits())
                .number(8, value.orderQuantitySteps())
                .encode();
    }
    /** 按固定字段编号读取并构造订单资金预留，恢复原预留总额、累计释放与消耗及对应订单数量。 */
    private static OrderFundsReservation.Snapshot readOrderReservation(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new OrderFundsReservation.Snapshot(
                r.number(1),
                r.text(2),
                SnapshotEnumCodes.readReservationKind(r.integer(3)),
                r.text(4),
                r.number(5),
                r.number(6),
                r.number(7),
                r.number(8));
    }

    private static byte[] writeCorePositionState(CorePositionState value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId())
                .text(2, value.marginAsset())
                .number(3, SnapshotEnumCodes.encode(value.marginMode()))
                .number(4, SnapshotEnumCodes.encode(value.positionSide()))
                .number(5, value.signedQuantitySteps())
                .number(6, value.entryPriceTicks())
                .number(7, value.entryValueTicks())
                .number(8, value.realizedPnlUnits())
                .number(9, value.positionMarginUnits())
                .encode();
    }
    /** 按固定字段编号读取并构造用户持仓，恢复保证金资产、方向、数量、成本、累计盈亏及保证金。 */
    private static CorePositionState readCorePositionState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CorePositionState(
                r.text(1),
                r.text(2),
                SnapshotEnumCodes.readCoreMarginMode(r.integer(3)),
                SnapshotEnumCodes.readCorePositionSide(r.integer(4)),
                r.number(5),
                r.number(6),
                r.number(7),
                r.number(8),
                r.number(9));
    }

    private static byte[] writeCoreOrderState(CoreOrderState value) {
        return new SnapshotFields.Writer()
                .number(1, value.orderId())
                .number(3, value.userId())
                .text(4, value.instrumentId())
                .number(5, SnapshotEnumCodes.encode(value.side()))
                .number(7, value.matchingPriceTicks())
                .number(8, value.quantitySteps())
                .number(9, value.executedQuantitySteps())
                .bool(11, value.reduceOnly())
                .number(12, SnapshotEnumCodes.encode(value.marginMode()))
                .number(13, SnapshotEnumCodes.encode(value.positionSide()))
                .number(14, SnapshotEnumCodes.encode(value.orderType()))
                .number(15, SnapshotEnumCodes.encode(value.timeInForce()))
                .bool(16, value.postOnly())
                .text(17, value.clientOrderId())
                .uuid(18, value.commandId())
                .number(19, value.makerFeeRatePpm())
                .number(20, value.takerFeeRatePpm())
                .number(21, value.cumulativeFeeUnits())
                .number(22, value.executedValueHigh())
                .number(23, value.executedValueLow())
                .number(24, value.createdAtEpochMillis())
                .number(25, value.updatedAtEpochMillis())
                .number(26, value.clusterPosition())
                .number(27, SnapshotEnumCodes.encode(value.status()))
                .number(28, value.revision())
                .encode();
    }
    /** 按固定字段编号读取并构造普通订单，恢复撮合保护价（原请求价按产品和类型读取）、累计数量与费用、一百二十八位成交值、时间和状态。 */
    private static CoreOrderState readCoreOrderState(byte[] encoded, ProductLine productLine) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreOrderState(
                r.number(1),
                productLine,
                r.number(3),
                r.text(4),
                SnapshotEnumCodes.readCoreOrderSide(r.integer(5)),
                r.number(7),
                r.number(8),
                r.number(9),
                r.bool(11),
                SnapshotEnumCodes.readCoreMarginMode(r.integer(12)),
                SnapshotEnumCodes.readCorePositionSide(r.integer(13)),
                SnapshotEnumCodes.readCoreOrderType(r.integer(14)),
                SnapshotEnumCodes.readCoreTimeInForce(r.integer(15)),
                r.bool(16),
                r.text(17),
                r.uuid(18),
                r.number(19),
                r.number(20),
                r.number(21),
                r.number(22),
                r.number(23),
                r.number(24),
                r.number(25),
                r.number(26),
                SnapshotEnumCodes.readCoreOrderStatus(r.integer(27)),
                r.number(28));
    }

    private static byte[] writeCoreRiskSnapshot(CoreRiskSnapshot value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId())
                .text(2, value.instrumentId())
                .number(3, SnapshotEnumCodes.encode(value.positionSide()))
                .number(4, value.priceSequence())
                .number(5, value.equityUnits())
                .number(6, value.unrealizedPnlUnits())
                .number(7, value.maintenanceMarginUnits())
                .number(8, value.marginRatioPpm())
                .encode();
    }
    /** 按固定字段编号读取并构造账户风险快照，恢复权益、未实现盈亏、维持保证金及比率，等级按固定阈值读取。 */
    private static CoreRiskSnapshot readCoreRiskSnapshot(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskSnapshot(
                r.number(1),
                r.text(2),
                SnapshotEnumCodes.readCorePositionSide(r.integer(3)),
                r.number(4),
                r.number(5),
                r.number(6),
                r.number(7),
                r.number(8));
    }

    private static byte[] writeCoreMarkPriceState(CoreMarkPriceState value, ProductLine productLine) {
        var writer = new SnapshotFields.Writer()
                .text(1, value.instrumentId())
                .number(2, value.markPriceTicks())
                .number(3, value.indexPriceTicks())
                .number(5, value.priceSequence())
                .number(6, value.generatedAtEpochMillis())
                .number(7, value.lastPriceTicks());
        if (productLine.isOptionProduct()) writer.number(4, value.forwardPriceTicks());
        return writer.encode();
    }
    /** 按当前产品恢复标记、指数及最新价。 */
    private static CoreMarkPriceState readCoreMarkPriceState(byte[] encoded, ProductLine productLine) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreMarkPriceState(
                r.text(1),
                r.number(2),
                r.number(3),
                productLine.isOptionProduct() ? r.number(4) : 0,
                r.number(5),
                r.number(6),
                r.number(7));
    }

    private static byte[] writeCoreLiquidationState(CoreLiquidationState value) {
        return new SnapshotFields.Writer()
                .number(1, value.liquidationId())
                .number(2, value.userId())
                .text(3, value.instrumentId())
                .number(4, SnapshotEnumCodes.encode(value.marginMode()))
                .number(5, SnapshotEnumCodes.encode(value.positionSide()))
                .number(6, value.triggerPriceSequence())
                .number(7, value.signedQuantitySteps())
                .number(8, value.closeQuantitySteps())
                .number(9, value.deficitUnits())
                .number(10, value.executionPriceTicks())
                .number(11, value.liquidationFeeRatePpm())
                .number(12, value.liquidationFeeUnits())
                .number(13, SnapshotEnumCodes.encode(value.status()))
                .number(14, value.nextCancelOrderId())
                .encode();
    }
    /** 按固定字段编号读取并构造强平业务记录，恢复计划数量、执行数据、亏空、费用、业务状态和下一撤单游标。 */
    private static CoreLiquidationState readCoreLiquidationState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreLiquidationState(
                r.number(1),
                r.number(2),
                r.text(3),
                SnapshotEnumCodes.readCoreMarginMode(r.integer(4)),
                SnapshotEnumCodes.readCorePositionSide(r.integer(5)),
                r.number(6),
                r.number(7),
                r.number(8),
                r.number(9),
                r.number(10),
                r.number(11),
                r.number(12),
                SnapshotEnumCodes.readCoreLiquidationStateStatus(r.integer(13)),
                r.number(14));
    }

    private static byte[] writeRiskLaneProgress(RiskLaneProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.lastUserId())
                .bool(2, value.complete())
                .number(3, value.userId())
                .number(4, value.phase())
                .text(5, value.positionCursor())
                .number(6, value.reservationCursor())
                .number(7, value.unrealizedPnlUnits())
                .number(8, value.maintenanceMarginUnits())
                .number(9, value.isolatedMarginUnits())
                .number(10, value.isolatedReservationUnits())
                .number(11, value.userRevision())
                .number(12, value.marketRevision())
                .encode();
    }
    /** 按固定字段编号读取并构造单账户 Lane 风险扫描进度，恢复用户与持仓/预留游标、阶段、中间累计值和账户/行情修订。 */
    private static RiskLaneProgress readRiskLaneProgress(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new RiskLaneProgress(
                r.number(1),
                r.bool(2),
                r.number(3),
                r.integer(4),
                r.text(5),
                r.number(6),
                r.number(7),
                r.number(8),
                r.number(9),
                r.number(10),
                r.number(11),
                r.number(12));
    }

    private static byte[] writeCoreRiskScanControlView(CoreRiskScanControlView value) {
        return new SnapshotFields.Writer()
                .number(1, value.version())
                .text(2, value.ruleName())
                .bool(3, value.enabled())
                .number(4, value.scanDelayMs())
                .number(5, value.scanBatchSize())
                .text(6, value.updatedBy())
                .text(7, value.reason())
                .number(8, value.updatedAtEpochMillis())
                .encode();
    }
    /** 按固定字段编号读取并构造风险扫描控制，恢复配置版本、规则、开关、调度参数和审计信息。 */
    private static CoreRiskScanControlView readCoreRiskScanControlView(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskScanControlView(
                r.number(1),
                r.text(2),
                r.bool(3),
                r.number(4),
                r.integer(5),
                r.text(6),
                r.text(7),
                r.number(8));
    }

    /** 风险档按列表顺序保存上界及金融参数。 */
    private static byte[] writeCoreRiskLimitBracket(CoreRiskLimitBracket value, ProductLine productLine) {
        SnapshotFields.Writer writer = new SnapshotFields.Writer().number(3, value.notionalCapUnits());
        if (!productLine.isOptionProduct()) writer.number(4, value.maxLeveragePpm());
        writer.number(5, value.initialMarginRatePpm()).number(6, value.maintenanceMarginRatePpm());
        if (productLine.isOptionProduct()) writer.number(7, value.optionMarginFactorPpm());
        return writer.encode();
    }

    /** 列表顺序及递增上界由CoreInstrumentRiskLimits统一校验，字段1、2已退役。 */
    private static CoreRiskLimitBracket readCoreRiskLimitBracket(byte[] encoded, ProductLine productLine) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskLimitBracket(r.number(3), productLine.isOptionProduct() ? 0 : r.number(4),
                r.number(5), r.number(6), productLine.isOptionProduct() ? r.number(7) : 0);
    }

    private static byte[] writeCoreInstrumentMaintenance(CoreInstrumentMaintenance value) {
        return new SnapshotFields.Writer()
                .number(1, value.taskId())
                .number(2, SnapshotEnumCodes.encode(value.mode()))
                .number(3, value.settlementPriceTicks())
                .encode();
    }
    /** 按固定字段编号读取并构造币对维护设置，恢复维护任务编号、模式和固定结算价。 */
    private static CoreInstrumentMaintenance readCoreInstrumentMaintenance(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreInstrumentMaintenance(
                r.number(1),
                SnapshotEnumCodes.readCoreInstrumentMaintenanceMode(r.integer(2)),
                r.number(3));
    }

    private static byte[] writeCoreAlgoOrderState(CoreAlgoOrderState value) {
        var writer = new SnapshotFields.Writer()
                .number(1, value.algoOrderId())
                .number(2, value.userId())
                .text(3, value.clientAlgoOrderId())
                .text(4, value.instrumentId())
                .number(5, value.algoTypeCode())
                .number(6, SnapshotEnumCodes.encode(value.side()))
                .number(7, value.priceTicks())
                .number(8, value.quantitySteps())
                .number(9, value.childQuantitySteps());
        if (value.algoTypeCode() != CoreAlgoOrderCommand.ICEBERG_TYPE_CODE)
            writer.number(10, value.intervalSeconds());
        writer.number(11, value.durationSeconds())
                .number(12, SnapshotEnumCodes.encode(value.marginMode()))
                .number(13, SnapshotEnumCodes.encode(value.positionSide()))
                .bool(14, value.reduceOnly());
        if (value.algoTypeCode() != CoreAlgoOrderCommand.TWAP_TYPE_CODE)
            writer.number(16, SnapshotEnumCodes.encode(value.timeInForce()));
        return writer.number(17, value.statusCode())
                .number(18, value.currentOrderId())
                .text(19, value.rejectReason())
                .number(21, value.startAtEpochMillis())
                .number(22, value.nextSliceAtEpochMillis())
                .number(24, value.createdAtEpochMillis())
                .number(25, value.updatedAtEpochMillis())
                .number(26, value.revision())
                .list(27, value.childOrderIds(), item -> new SnapshotFields.Writer().number(1, item).encode())
                .number(28, value.retiredExecutedQuantitySteps())
                .number(29, value.pendingClosedChildId())
                .encode();
    }
    /** 按固定编号恢复算法父单，包含字段二十八退休成交量和二十九待关联终态子单。 */
    private static CoreAlgoOrderState readCoreAlgoOrderState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        int algoType = r.integer(5);
        return new CoreAlgoOrderState(
                r.number(1),
                r.number(2),
                r.text(3),
                r.text(4),
                algoType,
                SnapshotEnumCodes.readCoreOrderSide(r.integer(6)),
                r.number(7),
                r.number(8),
                r.number(9),
                algoType == CoreAlgoOrderCommand.ICEBERG_TYPE_CODE
                        ? 0 : r.number(10),
                r.number(11),
                SnapshotEnumCodes.readCoreMarginMode(r.integer(12)),
                SnapshotEnumCodes.readCorePositionSide(r.integer(13)),
                r.bool(14),
                algoType == CoreAlgoOrderCommand.TWAP_TYPE_CODE ? CoreTimeInForce.IOC
                        : SnapshotEnumCodes.readCoreTimeInForce(r.integer(16)),
                r.integer(17),
                r.number(18),
                r.text(19),
                r.number(21),
                r.number(22),
                r.number(24),
                r.number(25),
                r.number(26),
                r.list(27, item -> new SnapshotFields.Reader(item).number(1)),
                r.number(28),
                r.number(29));
    }

    private static byte[] writeCoreCancelAllAfterState(CoreCancelAllAfterState value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId())
                .text(2, value.symbolScope())
                .number(4, SnapshotEnumCodes.encode(value.status()))
                .number(5, value.triggerAtEpochMillis())
                .number(6, value.updatedAtEpochMillis())
                .number(9, value.revision())
                .encode();
    }
    /** 按固定字段编号读取并构造倒计时撤单状态，恢复用户范围、触发状态和修订号。 */
    private static CoreCancelAllAfterState readCoreCancelAllAfterState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreCancelAllAfterState(
                r.number(1),
                r.text(2),
                SnapshotEnumCodes.readCoreCancelAllAfterStatus(r.integer(4)),
                r.number(5),
                r.number(6),
                r.number(9));
    }

    private static byte[] writeCoreLeverageKey(CoreLeverageKey value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId())
                .text(2, value.instrumentId())
                .number(3, SnapshotEnumCodes.encode(value.marginMode()))
                .encode();
    }
    /** 按固定字段编号读取并构造杠杆设置业务键，恢复用户、币对和保证金模式。 */
    private static CoreLeverageKey readCoreLeverageKey(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreLeverageKey(
                r.number(1),
                r.text(2),
                SnapshotEnumCodes.readCoreMarginMode(r.integer(3)));
    }

    private static byte[] writeCoreTriggerOrderState(CoreTriggerOrderState value) {
        return new SnapshotFields.Writer()
                .number(1, value.triggerOrderId())
                .number(3, value.userId())
                .text(4, value.clientTriggerOrderId())
                .text(5, value.ocoGroupId())
                .text(6, value.instrumentId())
                .number(7, SnapshotEnumCodes.encode(value.side()))
                .number(8, SnapshotEnumCodes.encode(value.triggerType()))
                .number(9, SnapshotEnumCodes.encode(value.triggerCondition()))
                .number(10, value.triggerPriceTicks())
                .number(11, value.activationPriceTicks())
                .number(12, value.callbackRatePpm())
                .number(13, value.highestPriceTicks())
                .number(14, value.lowestPriceTicks())
                .number(15, value.activatedAtEpochMillis())
                .number(16, SnapshotEnumCodes.encode(value.orderType()))
                .number(17, SnapshotEnumCodes.encode(value.timeInForce()))
                .number(18, value.priceTicks())
                .number(19, value.quantitySteps())
                .number(20, SnapshotEnumCodes.encode(value.marginMode()))
                .number(21, SnapshotEnumCodes.encode(value.positionSide()))
                .number(22, SnapshotEnumCodes.encode(value.status()))
                .number(23, value.placedOrderId())
                .number(24, value.triggerSequence())
                .number(25, value.triggeredPriceTicks())
                .text(26, value.rejectReason())
                .number(28, value.expiresAtEpochMillis())
                .number(29, value.triggeredAtEpochMillis())
                .number(30, value.createdAtEpochMillis())
                .number(31, value.updatedAtEpochMillis())
                .number(32, value.revision())
                .number(35, SnapshotEnumCodes.encode(value.priceSource()))
                .encode();
    }
    /** 按固定字段编号读取并构造触发单记录，恢复触发条件、追踪止损状态、子单参数、OCO、价格来源及终态时间。 */
    private static CoreTriggerOrderState readCoreTriggerOrderState(byte[] encoded, ProductLine productLine,
            Map<String, CoreInstrument> instruments) {
        var r = new SnapshotFields.Reader(encoded);
        String instrumentId = r.text(6);
        if (instrumentId == null || instrumentId.isBlank()) throw new IllegalArgumentException("invalid trigger order state");
        CoreInstrument instrument = instruments.get(instrumentId);
        if (instrument == null) throw new ProtocolException("trigger instrument is missing");
        if (instrument.contractType().productLine() != productLine)
            throw new IllegalArgumentException("trigger order instrument mismatch");
        return new CoreTriggerOrderState(
                r.number(1),
                r.number(3),
                r.text(4),
                r.text(5),
                instrument,
                SnapshotEnumCodes.readCoreOrderSide(r.integer(7)),
                SnapshotEnumCodes.readCoreTriggerOrderType(r.integer(8)),
                SnapshotEnumCodes.readCoreTriggerCondition(r.integer(9)),
                r.number(10),
                r.number(11),
                r.number(12),
                r.number(13),
                r.number(14),
                r.number(15),
                SnapshotEnumCodes.readCoreOrderType(r.integer(16)),
                SnapshotEnumCodes.readCoreTimeInForce(r.integer(17)),
                r.number(18),
                r.number(19),
                SnapshotEnumCodes.readCoreMarginMode(r.integer(20)),
                SnapshotEnumCodes.readCorePositionSide(r.integer(21)),
                SnapshotEnumCodes.readCoreTriggerOrderStatus(r.integer(22)),
                r.number(23),
                r.number(24),
                r.number(25),
                r.text(26),
                r.number(28),
                r.number(29),
                r.number(30),
                r.number(31),
                r.number(32),
                SnapshotEnumCodes.readCoreTriggerPriceSource(r.integer(35)));
    }

    private static byte[] writeRiskScan(RiskScan value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId())
                .number(2, value.accountLaneId())
                .number(3, value.priceSequence())
                .number(4, value.scanStartPriceSequence())
                .bool(6, value.riskComplete())
                .bool(15, value.triggerComplete())
                .number(16, value.triggerPhase())
                .number(17, value.triggerPriceCursor())
                .number(18, value.triggerOrderCursor())
                .number(19, value.triggerUpperId())
                .number(20, value.triggerMarkPriceTicks())
                .number(21, value.triggerGeneratedAtEpochMillis())
                .number(22, value.triggerOcoOrderId())
                .number(23, value.triggerOcoCursor())
                .number(24, value.lastScheduledRevision())
                .list(25, value.laneProgress(), item -> writeRiskLaneProgress(item))
                .encode();
    }
    /** 按固定字段编号读取并构造币对风险与触发扫描进度，恢复全局和各 Lane 进度、估值中间量、触发索引游标及固定价格输入。 */
    private static RiskScan readRiskScan(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new RiskScan(
                r.text(1),
                r.integer(2),
                r.number(3),
                r.number(4),
                r.bool(6),
                r.bool(15),
                r.integer(16),
                r.number(17),
                r.number(18),
                r.number(19),
                r.number(20),
                r.number(21),
                r.number(22),
                r.number(23),
                r.number(24),
                r.list(25, item -> readRiskLaneProgress(item)));
    }

    private static byte[] writeFundingProgress(FundingProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.settlementId())
                .number(2, value.fundingRatePpm())
                .number(4, value.nextCursorUserId())
                .number(6, value.markPriceTicks())
                .encode();
    }
    private static FundingProgress readFundingProgress(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new FundingProgress(r.number(1),
                r.number(2),
                r.number(4),
                r.number(6));
    }

    private static byte[] writeLifecycleProgress(LifecycleProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.settlementId())
                .number(2, value.settlementPriceTicks())
                .bool(4, value.ordersComplete())
                .number(6, value.nextCursorOrderId())
                .number(7, value.nextCursorUserId())
                .number(9, value.requiredInsuranceUnits())
                .encode();
    }
    /** 按固定字段编号读取并构造到期或固定价格结算续页进度，恢复轮次、固定结算参数、撤单阶段、双游标及保险需求。 */
    private static LifecycleProgress readLifecycleProgress(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new LifecycleProgress(r.number(1),
                r.number(2),
                r.bool(4),
                r.number(6),
                r.number(7),
                r.number(9));
    }

    private static byte[] writeCoreUserState(UserAccountSnapshot value) {
        return new SnapshotFields.Writer()
                .number(2, value.userId())
                .number(3, value.revision())
                .bytes(4, writeValues(value.balances(), item -> writeAssetBalance(item)))
                .bytes(5, writeValues(value.reservations(), item -> writeOrderReservation(item)))
                .bytes(6, writeValues(value.positions(), item -> writeCorePositionState(item)))
                .number(7, SnapshotEnumCodes.encode(value.positionMode()))
                .encode();
    }
    /** 按固定字段编号读取并构造用户账户业务状态，恢复产品线、用户修订、余额、预留、持仓和持仓模式。 */
    private static UserAccountSnapshot readCoreUserState(byte[] encoded, ProductLine productLine) {
        var r = new SnapshotFields.Reader(encoded);
        return new UserAccountSnapshot(
                productLine,
                r.number(2),
                r.number(3),
                readValues(r.bytes(4), item -> readAssetBalance(item), AssetBalance::asset),
                readValues(r.bytes(5), item -> readOrderReservation(item), OrderFundsReservation.Snapshot::orderId),
                readValues(r.bytes(6), item -> readCorePositionState(item), CorePositionState::key),
                SnapshotEnumCodes.readCorePositionMode(r.integer(7)));
    }

    private static byte[] writeCoreInstrument(CoreInstrument value, ProductLine productLine) {
        if (value.contractType().productLine() != productLine)
            throw new ProtocolException("snapshot instrument product line mismatch");
        SnapshotFields.Writer writer = new SnapshotFields.Writer()
                .text(1, value.instrumentId())
                .text(3, value.baseAsset()).text(4, value.quoteAsset()).text(5, value.settleAsset())
                .number(6, value.notionalMultiplierUnits())
                .number(11, value.makerFeeRatePpm()).number(12, value.takerFeeRatePpm())
                .bytes(21, writeCoreInstrumentMaintenance(value.maintenance()))
                .number(22, SnapshotEnumCodes.encode(value.instrumentStatus()))
                .bool(23, value.marketOrderEnabled()).bool(24, value.postOnlyEnabled()).bool(25, value.reduceOnlyEnabled())
                .number(26, value.supportedOrderTypeMask()).number(27, value.supportedTimeInForceMask());
        if (value.contractType().isInverse())
            writer.number(7, value.priceTickUnits()).number(8, value.settleScaleUnits());
        if (productLine.isDeliveryProduct()) writer.number(13, value.expiryEpochMillis());
        if (productLine.isOptionProduct())
            writer.number(14, SnapshotEnumCodes.encode(value.optionType())).number(15, value.strikePriceTicks());
        if (!productLine.isDerivative()) writer.number(28, value.quantityStepUnits());
        if (productLine.isDerivative()) {
            var protection = value.orderProtection();
            writer.number(29, protection.marketMaxSlippagePpm()).number(30, protection.marketMaxMarkAgeMs())
                    .number(31, protection.limitPriceProtectionEnabled() ? 1 : 0)
                    .number(32, protection.limitPriceBandPpm()).number(33, protection.limitPriceMaxMarkAgeMs());
            var risk = value.riskLimits();
            writer.number(9, risk.initialMarginRatePpm())
                    .number(17, risk.maxPositionNotionalUnits())
                    .number(18, risk.userOpenInterestLimitRatePpm()).number(19, risk.userOpenInterestLimitFloorUnits())
                    .list(20, risk.riskLimitBrackets(), item -> writeCoreRiskLimitBracket(item, productLine));
            if (!productLine.isOptionProduct()) writer.number(16, risk.maxLeveragePpm());
        }
        return writer.encode();
    }

    private static CoreInstrument readCoreInstrument(byte[] encoded, ProductLine productLine) {
        var r = new SnapshotFields.Reader(encoded);
        var contractType = ContractType.valueOf(productLine.contractTypeCode());
        var risk = productLine.isDerivative() ? new CoreInstrumentRiskLimits(
                r.number(9), productLine.isOptionProduct() ? 0 : r.number(16), r.number(17), r.number(18), r.number(19),
                r.list(20, item -> readCoreRiskLimitBracket(item, productLine))) : null;
        return new CoreInstrument(r.text(1), contractType,
                r.text(3), r.text(4), r.text(5), r.number(6), contractType.isInverse() ? r.number(7) : 1,
                contractType.isInverse() ? r.number(8) : 1, risk,
                r.number(11), r.number(12), productLine.isDeliveryProduct() ? r.number(13) : 0,
                productLine.isOptionProduct() ? SnapshotEnumCodes.readOptionType(r.integer(14)) : null,
                productLine.isOptionProduct() ? r.number(15) : 0,
                readCoreInstrumentMaintenance(r.bytes(21)), SnapshotEnumCodes.readInstrumentStatus(r.integer(22)),
                r.bool(23), r.bool(24), r.bool(25), r.integer(26), r.integer(27),
                productLine.isDerivative() ? 1 : r.number(28),
                productLine.isDerivative() ? new com.surprising.aeron.protocol.CoreOrderProtection(r.number(29), r.number(30),
                        r.number(31) != 0, r.number(32), r.number(33)) : null);
    }

    private static byte[] writeCoreRiskState(CoreRiskState value, ProductLine productLine) {
        return new SnapshotFields.Writer()
                .bytes(1, writeValues(value.markPrices(), item -> writeCoreMarkPriceState(item, productLine)))
                .bytes(2, writeValues(value.snapshots(), item -> writeCoreRiskSnapshot(item)))
                .bytes(3, writeValues(value.liquidations(), item -> writeCoreLiquidationState(item)))
                .bytes(4, writeValues(value.scans(), item -> writeRiskScan(item)))
                .number(5, value.nextLiquidationId())
                .bytes(6, writeCoreRiskScanControlView(value.scanControl()))
                .number(7, value.marketRevision())
                .encode();
    }
    /** 按固定字段编号读取并构造风险业务状态，恢复参考价格、账户风险、强平记录、扫描进度及扫描控制和行情修订。 */
    private static CoreRiskState readCoreRiskState(byte[] encoded, ProductLine productLine) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskState(
                readValues(r.bytes(1), item -> readCoreMarkPriceState(item, productLine), CoreMarkPriceState::instrumentId),
                readValues(r.bytes(2), item -> readCoreRiskSnapshot(item), CoreRiskSnapshot::key),
                readValues(r.bytes(3), item -> readCoreLiquidationState(item), CoreLiquidationState::liquidationId),
                readValues(r.bytes(4), item -> readRiskScan(item), RiskScan::instrumentId),
                r.number(5),
                readCoreRiskScanControlView(r.bytes(6)),
                r.number(7));
    }

    private static byte[] writeCoreTreasuryState(CoreTreasuryState value) {
        return new SnapshotFields.Writer()
                .bytes(1, writeMap(value.feeBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(2, writeMap(value.insuranceBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(3, writeMap(value.insuranceDeficits(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(4, writeMap(value.liquidationFeeBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(5, writeMap(value.fundingResidualBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                // 6：按资产保存的舍入剩余差额。
                .bytes(6, writeMap(value.roundingResidualBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(7, writeMap(value.clearingPnlBalances(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(8, writeMap(value.fundingSettlements(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(9, writeMap(value.lifecycleSettlements(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(10, writeMap(value.fundingProgress(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeFundingProgress(item)))
                .bytes(11, writeMap(value.lifecycleProgress(), key -> new SnapshotFields.Writer().text(1, key).encode(), item -> writeLifecycleProgress(item)))
                .encode();
    }
    /** 按固定字段编号读取并构造系统财库状态，恢复七类资产金额表、已完成结算编号及未完成资金费和到期进度。 */
    private static CoreTreasuryState readCoreTreasuryState(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreTreasuryState(
                readMap(r.bytes(1), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                readMap(r.bytes(2), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                readMap(r.bytes(3), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                readMap(r.bytes(4), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                readMap(r.bytes(5), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                // 6：按资产保存的舍入剩余差额。
                readMap(r.bytes(6), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                readMap(r.bytes(7), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                readMap(r.bytes(8), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                readMap(r.bytes(9), key -> new SnapshotFields.Reader(key).text(1), item -> new SnapshotFields.Reader(item).number(1)),
                readMap(r.bytes(10), key -> new SnapshotFields.Reader(key).text(1), item -> readFundingProgress(item)),
                readMap(r.bytes(11), key -> new SnapshotFields.Reader(key).text(1), item -> readLifecycleProgress(item)));
    }
}
