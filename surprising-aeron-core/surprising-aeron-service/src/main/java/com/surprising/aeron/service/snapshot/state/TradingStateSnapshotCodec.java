package com.surprising.aeron.service.snapshot.state;

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
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderReservation;
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

/**
 * 交易状态的独立持久化格式。新快照外层版本四十二，内部各业务记录以固定字段编号和长度编码；字段编号只增不复用，Java 类名、声明顺序及接口 DTO 不决定磁盘布局。只读取当前版本，不兼容变化统一提升外层版本，未知合法字段按长度跳过而不参与当前恢复。
 * 构造约定：工具类不创建实例；当前写版本四十二，历史布局不再接受。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TradingStateSnapshotCodec {
    /** 本编码器新写入的外层快照布局版本四十二。 */
    private static final int VERSION = 43;

    /**
     * 把交易状态各业务表和独立记录写成编号字段，外层加小端版本四十二头，不兼容变化统一提升外层版本；不把 Java 类名或当前接口 DTO 当作磁盘结构。
     * @param value 本方法要持久化的对应业务值，字段和顺序由固定编号定义
     */
    public static byte[] encode(TradingCoreState value) {
        // 当前写出的根字段记录，外层另加四字节快照版本头。
        byte[] fields = new SnapshotFields.Writer()
                .number(1, SnapshotEnumCodes.encode(value.productLine()))
                .number(2, value.revision())
                .bytes(3, writeValues(value.users(), TradingStateSnapshotCodec::writeCoreUserState))
                .bytes(4, writeValues(value.orders(), TradingStateSnapshotCodec::writeCoreOrderState))
                .bytes(5, writeValues(value.instruments(), item -> writeCoreInstrument(item, value.productLine())))
                .bytes(6, writeCoreRiskState(value.riskState()))
                .bytes(7, writeCoreTreasuryState(value.treasuryState()))
                .bytes(8, writeMap(value.leverages(), TradingStateSnapshotCodec::writeCoreLeverageKey, /* 当前杠杆值或触发单业务值，按其所在根字段写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode()))
                .bytes(9, writeValues(value.algoOrders(), TradingStateSnapshotCodec::writeCoreAlgoOrderState))
                .list(10, value.cancelAllAfterTimers().values(), TradingStateSnapshotCodec::writeCoreCancelAllAfterState)
                .list(11, value.triggerOrders().values(), /* 当前杠杆值或触发单业务值，按其所在根字段写入。 */ item -> writeCoreTriggerOrderStateView(item.view())).encode();
        return ByteBuffer.allocate(4 + fields.length).order(ByteOrder.LITTLE_ENDIAN).putInt(VERSION).put(fields).array();
    }

    /**
     * 先拒绝非当前外层版本，再核对产品线，再恢复用户、订单、币对、风险、财库等。触发单必须引用已恢复币对；当前布局解析中的协议错误原样抛出，其他运行时构造或算术错误包装为带原因的 ProtocolException。
     * @param encoded 本方法所读的独立字段记录；decode 入口还包含外层版本头
     * @param expectedProductLine 调用方当前实例所属产品线，必须与快照一致
     */
    public static TradingCoreState decode(byte[] encoded, ProductLine expectedProductLine) {
        if (encoded == null || encoded.length < 4) throw new ProtocolException("truncated trading snapshot");
        // 快照布局的唯一版本；不兼容变更必须更新版本，编号十二保留不复用。
        int version = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (version != VERSION) throw new ProtocolException("unsupported trading snapshot version: " + version);
        try {
            // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
            var r = new SnapshotFields.Reader(Arrays.copyOfRange(encoded, 4, encoded.length));
            // 快照声明且必须与调用方期望一致的独立产品线。
            ProductLine product = SnapshotEnumCodes.readProductLine(r.integer(1));
            if (product != expectedProductLine) throw new ProtocolException("trading snapshot product line mismatch");
            // 按用户编号恢复的账户状态表。
            Map<Long, CoreUserState> users = readValues(r.bytes(3), item -> readCoreUserState(item, product),
                    CoreUserState::userId);
            // 按订单编号恢复的订单状态表。
            Map<Long, CoreOrderState> orders = readValues(r.bytes(4), item -> readCoreOrderState(item, product),
                    CoreOrderState::orderId);
            // 按币对业务编号恢复的注册配置表，触发单随后引用其中配置。
            Map<String, CoreInstrument> instruments = readValues(r.bytes(5), item -> readCoreInstrument(item, product), CoreInstrument::instrumentId);
            // 按用户和币对范围恢复的倒计时撤单表。
            Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = new TreeMap<>();
            for (var /* 当前解码并需要按业务键查重的倒计时撤单记录。 */ timer : r.list(10, TradingStateSnapshotCodec::readCoreCancelAllAfterState)) putUnique(timers, timer.key(), timer);
            // 按触发单编号恢复的触发单权威业务值表。
            Map<Long, CoreTriggerOrderState> triggers = new TreeMap<>();
            for (var /* 当前触发单字段记录恢复的协议业务视图，随后绑定注册币对。 */ view : r.list(11, item -> readCoreTriggerOrderStateView(item,
                    product))) {
                // 当前正在检查能力要求或按触发单引用取得的注册币对。
                var instrument = instruments.get(view.instrumentId());
                if (instrument == null) throw new ProtocolException("trigger instrument is missing");
                putUnique(triggers, view.triggerOrderId(), CoreTriggerOrderState.from(view, instrument));
            }
            return new TradingCoreState(product, r.number(2), users, orders, instruments,
                    readCoreRiskState(r.bytes(6)), readCoreTreasuryState(r.bytes(7)),
                    readMap(r.bytes(8), TradingStateSnapshotCodec::readCoreLeverageKey, /* 当前杠杆值字段记录字节，字段一保存杠杆数值。 */ item -> new SnapshotFields.Reader(item).number(1)),
                    readValues(r.bytes(9), TradingStateSnapshotCodec::readCoreAlgoOrderState, CoreAlgoOrderState::algoOrderId), timers, triggers);
        } catch (/* 协议异常直接抛出，其他运行时异常在外层转换时作为原原因保留。 */ ProtocolException invalid) { throw invalid; }
        catch (/* 协议异常直接抛出，其他运行时异常在外层转换时作为原原因保留。 */ RuntimeException invalid) { throw new ProtocolException("invalid trading snapshot: " + invalid.getMessage(), invalid); }
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

    /**
     * 按输入 Map 当前条目顺序逐项写独立键和值记录，不自行排序；调用方状态表负责确定性顺序。此处没有新增通用条目数上限。
     * @param <K> 业务 Map 的键类型，恢复时必须可排序
     * @param <V> 业务 Map 的非空值类型
     * @param values 本次编码或恢复的业务键值表
     * @param key 把一条业务键编码成独立字段记录的函数
     * @param value 把一条业务值编码成独立字段记录的函数
     */
    private static <K, V> byte[] writeMap(Map<K, V> values, Function<K, byte[]> key, Function<V, byte[]> value) {
        return new SnapshotFields.Writer().list(1, values.entrySet(), /* 当前 Map 条目，分别调用键和值编码函数。 */ entry -> new SnapshotFields.Writer()
                .bytes(1, key.apply(entry.getKey())).bytes(2, value.apply(entry.getValue())).encode()).encode();
    }
    /**
     * 逐项解码键值记录并写入 TreeMap，按键恢复有序结果，重复的非空业务值键拒绝；业务键和值由各自读取函数校验。
     * @param <K> 业务 Map 的键类型，恢复时必须可排序
     * @param <V> 业务 Map 的非空值类型
     * @param encoded 本方法所读的独立字段记录；decode 入口还包含外层版本头
     * @param key 从独立键记录字节恢复业务键的函数
     * @param value 从独立值记录字节恢复业务值的函数
     */
    private static <K, V> Map<K, V> readMap(byte[] encoded, Function<byte[], K> key, Function<byte[], V> value) {
        // 按业务键自然顺序构建的 Map 恢复结果。
        Map<K, V> values = new TreeMap<>();
        for (var /* 当前 Map 条目的字段读取器，字段一为键、字段二为值。 */ entry : new SnapshotFields.Reader(encoded).list(1, SnapshotFields.Reader::new))
            putUnique(values, key.apply(entry.bytes(1)), value.apply(entry.bytes(2)));
        return values;
    }
    /**
     * 把已解码非空业务值加入结果表，以 put 返回的旧值查重；发现重复即终止恢复，不返回部分结果。查重发生在 put 之后，本方法不撤回刚写入的值。
     * @param <K> 业务 Map 的键类型，恢复时必须可排序
     * @param <V> 业务 Map 的非空值类型
     * @param values 本次编码或恢复的业务键值表
     * @param key 该条业务值在恢复表中的唯一键
     * @param value 已经完成字段及业务校验的非空值
     */
    private static <K, V> void putUnique(Map<K, V> values, K key, V value) {
        if (values.put(key, value) != null) throw new ProtocolException("duplicate trading snapshot key: " + key);
    }

    /**
     * 按固定字段编号写入资产余额，保存资产名称、可用额和冻结额；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的资产余额业务值
     */
    private static byte[] writeAssetBalance(AssetBalance value) {
        return new SnapshotFields.Writer()
                .text(1, value.asset()) // 1：资产名称
                .number(2, value.availableUnits()) // 2：可用金额
                .number(3, value.lockedUnits()) // 3：冻结金额
                .encode();
    }
    /**
     * 按固定字段编号读取并构造资产余额，恢复资产名称、可用额和冻结额；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前资产余额的独立字段记录字节
     */
    private static AssetBalance readAssetBalance(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new AssetBalance(
                r.text(1), // 1：资产名称
                r.number(2), // 2：可用金额
                r.number(3)); // 3：冻结金额
    }

    /**
     * 按固定字段编号写入订单资金预留，保存原预留总额、累计释放与消耗及对应订单数量；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的订单资金预留业务值
     */
    private static byte[] writeOrderReservation(OrderReservation value) {
        return new SnapshotFields.Writer()
                .number(1, value.orderId()) // 1：普通订单业务编号
                .text(2, value.instrumentId()) // 2：币对或合约业务编号
                .number(3, SnapshotEnumCodes.encode(value.kind())) // 3：现货资产或衍生品保证金预留用途
                .text(4, value.asset()) // 4：资产名称
                .number(5, value.reservedUnits()) // 5：预留初始总额，不是运行时剩余额度
                .number(6, value.releasedUnits()) // 6：预留累计已释放金额
                .number(7, value.consumedUnits()) // 7：预留累计已消耗金额
                .number(8, value.orderQuantitySteps()) // 8：本笔预留对应的原订单数量
                .encode();
    }
    /**
     * 按固定字段编号读取并构造订单资金预留，恢复原预留总额、累计释放与消耗及对应订单数量；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前订单资金预留的独立字段记录字节
     */
    private static OrderReservation readOrderReservation(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new OrderReservation(
                r.number(1), // 1：普通订单业务编号
                r.text(2), // 2：币对或合约业务编号
                SnapshotEnumCodes.readReservationKind(r.integer(3)), // 3：现货资产或衍生品保证金预留用途
                r.text(4), // 4：资产名称
                r.number(5), // 5：预留初始总额，不是运行时剩余额度
                r.number(6), // 6：预留累计已释放金额
                r.number(7), // 7：预留累计已消耗金额
                r.number(8)); // 8：本笔预留对应的原订单数量
    }

    /**
     * 按固定字段编号写入用户持仓，保存保证金资产、方向、数量、成本、累计盈亏及保证金；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的用户持仓业务值
     */
    private static byte[] writeCorePositionState(CorePositionState value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId()) // 1：币对或合约业务编号
                .text(2, value.marginAsset()) // 2：持仓保证金资产名称
                .number(3, SnapshotEnumCodes.encode(value.marginMode())) // 3：全仓或逐仓保证金模式
                .number(4, SnapshotEnumCodes.encode(value.positionSide())) // 4：净持仓、多仓或空仓方向
                .number(5, value.signedQuantitySteps()) // 5：带方向的持仓数量，负数为空仓
                .number(6, value.entryPriceTicks()) // 6：持仓开仓均价
                .number(7, value.entryValueTicks()) // 7：持仓保存的开仓价值
                .number(8, value.realizedPnlUnits()) // 8：持仓累计已实现盈亏
                .number(9, value.positionMarginUnits()) // 9：持仓冻结的保证金金额
                .encode();
    }
    /**
     * 按固定字段编号读取并构造用户持仓，恢复保证金资产、方向、数量、成本、累计盈亏及保证金；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前用户持仓的独立字段记录字节
     */
    private static CorePositionState readCorePositionState(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CorePositionState(
                r.text(1), // 1：币对或合约业务编号
                r.text(2), // 2：持仓保证金资产名称
                SnapshotEnumCodes.readCoreMarginMode(r.integer(3)), // 3：全仓或逐仓保证金模式
                SnapshotEnumCodes.readCorePositionSide(r.integer(4)), // 4：净持仓、多仓或空仓方向
                r.number(5), // 5：带方向的持仓数量，负数为空仓
                r.number(6), // 6：持仓开仓均价
                r.number(7), // 7：持仓保存的开仓价值
                r.number(8), // 8：持仓累计已实现盈亏
                r.number(9)); // 9：持仓冻结的保证金金额
    }

    /**
     * 按固定字段编号写入普通订单，保存原请求价、撮合保护价、累计数量与费用、一百二十八位成交值、时间和状态；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的普通订单业务值
     */
    private static byte[] writeCoreOrderState(CoreOrderState value) {
        return new SnapshotFields.Writer()
                .number(1, value.orderId()) // 1：普通订单业务编号
                .number(3, value.userId()) // 3：所属用户编号
                .text(4, value.instrumentId()) // 4：币对或合约业务编号
                .number(5, SnapshotEnumCodes.encode(value.side())) // 5：买入或卖出方向
                .number(6, value.priceTicks()) // 6：原请求订单价格
                .number(7, value.matchingPriceTicks()) // 7：订单提交给 Matcher 的实际撮合保护价格
                .number(8, value.quantitySteps()) // 8：订单原请求数量
                .number(9, value.executedQuantitySteps()) // 9：订单累计已成交数量
                .number(10, value.remainingQuantitySteps()) // 10：订单剩余未成交数量
                .bool(11, value.reduceOnly()) // 11：是否只允许减少已有持仓
                .number(12, SnapshotEnumCodes.encode(value.marginMode())) // 12：全仓或逐仓保证金模式
                .number(13, SnapshotEnumCodes.encode(value.positionSide())) // 13：净持仓、多仓或空仓方向
                .number(14, SnapshotEnumCodes.encode(value.orderType())) // 14：限价或市价订单类型
                .number(15, SnapshotEnumCodes.encode(value.timeInForce())) // 15：GTC、IOC、FOK 或 GTX 的订单有效方式
                .bool(16, value.postOnly()) // 16：是否只允许成为挂单 Maker
                .text(17, value.clientOrderId()) // 17：客户指定的普通订单号
                .uuid(18, value.commandId()) // 18：原业务命令 UUID
                .number(19, value.makerFeeRatePpm()) // 19：Maker 手续费率，百万分比
                .number(20, value.takerFeeRatePpm()) // 20：Taker 手续费率，百万分比
                .number(21, value.cumulativeFeeUnits()) // 21：订单累计实际手续费金额
                .number(22, value.executedValueHigh()) // 22：累计成交价值的一百二十八位高半部
                .number(23, value.executedValueLow()) // 23：累计成交价值的一百二十八位低半部
                .number(24, value.createdAtEpochMillis()) // 24：记录创建时刻，毫秒
                .number(25, value.updatedAtEpochMillis()) // 25：记录最近更新时刻，毫秒
                .number(26, value.clusterPosition()) // 26：订单保存的 Cluster 日志位置
                .number(27, SnapshotEnumCodes.encode(value.status())) // 27：当前业务记录状态
                .number(28, value.revision()) // 28：当前业务记录修订号
                .encode();
    }
    /**
     * 按固定字段编号读取并构造普通订单，恢复原请求价、撮合保护价、累计数量与费用、一百二十八位成交值、时间和状态；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前普通订单的独立字段记录字节
     */
    private static CoreOrderState readCoreOrderState(byte[] encoded, ProductLine productLine) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreOrderState(
                r.number(1), // 1：普通订单业务编号
                productLine, // 产品归属取自已校验的根字段
                r.number(3), // 3：所属用户编号
                r.text(4), // 4：币对或合约业务编号
                SnapshotEnumCodes.readCoreOrderSide(r.integer(5)), // 5：买入或卖出方向
                r.number(6), // 6：原请求订单价格
                r.number(7), // 7：订单提交给 Matcher 的实际撮合保护价格
                r.number(8), // 8：订单原请求数量
                r.number(9), // 9：订单累计已成交数量
                r.number(10), // 10：订单剩余未成交数量
                r.bool(11), // 11：是否只允许减少已有持仓
                SnapshotEnumCodes.readCoreMarginMode(r.integer(12)), // 12：全仓或逐仓保证金模式
                SnapshotEnumCodes.readCorePositionSide(r.integer(13)), // 13：净持仓、多仓或空仓方向
                SnapshotEnumCodes.readCoreOrderType(r.integer(14)), // 14：限价或市价订单类型
                SnapshotEnumCodes.readCoreTimeInForce(r.integer(15)), // 15：GTC、IOC、FOK 或 GTX 的订单有效方式
                r.bool(16), // 16：是否只允许成为挂单 Maker
                r.text(17), // 17：客户指定的普通订单号
                r.uuid(18), // 18：原业务命令 UUID
                r.number(19), // 19：Maker 手续费率，百万分比
                r.number(20), // 20：Taker 手续费率，百万分比
                r.number(21), // 21：订单累计实际手续费金额
                r.number(22), // 22：累计成交价值的一百二十八位高半部
                r.number(23), // 23：累计成交价值的一百二十八位低半部
                r.number(24), // 24：记录创建时刻，毫秒
                r.number(25), // 25：记录最近更新时刻，毫秒
                r.number(26), // 26：订单保存的 Cluster 日志位置
                SnapshotEnumCodes.readCoreOrderStatus(r.integer(27)), // 27：当前业务记录状态
                r.number(28)); // 28：当前业务记录修订号
    }

    /**
     * 按固定字段编号写入账户风险快照，保存权益、未实现盈亏、维持保证金、比率和风险状态；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的账户风险快照业务值
     */
    private static byte[] writeCoreRiskSnapshot(CoreRiskSnapshot value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId()) // 1：所属用户编号
                .text(2, value.instrumentId()) // 2：币对或合约业务编号
                .number(3, SnapshotEnumCodes.encode(value.positionSide())) // 3：净持仓、多仓或空仓方向
                .number(4, value.priceSequence()) // 4：参考价格序号
                .number(5, value.equityUnits()) // 5：账户风险估值权益
                .number(6, value.unrealizedPnlUnits()) // 6：尚未实现的持仓盈亏
                .number(7, value.maintenanceMarginUnits()) // 7：估值要求的维持保证金金额
                .number(8, value.marginRatioPpm()) // 8：风险保证金比率，百万分比
                .number(9, SnapshotEnumCodes.encode(value.status())) // 9：当前业务记录状态
                .encode();
    }
    /**
     * 按固定字段编号读取并构造账户风险快照，恢复权益、未实现盈亏、维持保证金、比率和风险状态；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前账户风险快照的独立字段记录字节
     */
    private static CoreRiskSnapshot readCoreRiskSnapshot(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskSnapshot(
                r.number(1), // 1：所属用户编号
                r.text(2), // 2：币对或合约业务编号
                SnapshotEnumCodes.readCorePositionSide(r.integer(3)), // 3：净持仓、多仓或空仓方向
                r.number(4), // 4：参考价格序号
                r.number(5), // 5：账户风险估值权益
                r.number(6), // 6：尚未实现的持仓盈亏
                r.number(7), // 7：估值要求的维持保证金金额
                r.number(8), // 8：风险保证金比率，百万分比
                SnapshotEnumCodes.readCoreRiskStatus(r.integer(9))); // 9：当前业务记录状态
    }

    /**
     * 按固定字段编号写入币对参考价格，保存标记价、指数价、远期价、价格序号、生成时间及最近成交价；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的币对参考价格业务值
     */
    private static byte[] writeCoreMarkPriceState(CoreMarkPriceState value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId()) // 1：币对或合约业务编号
                .number(2, value.markPriceTicks()) // 2：标记价格
                .number(3, value.indexPriceTicks()) // 3：指数参考价格
                .number(4, value.forwardPriceTicks()) // 4：远期参考价格
                .number(5, value.priceSequence()) // 5：参考价格序号
                .number(6, value.generatedAtEpochMillis()) // 6：参考价格生成时刻，毫秒
                .number(7, value.lastPriceTicks()) // 7：最近成交价
                .encode();
    }
    /**
     * 按固定字段恢复标记价及参考价；最近成交价字段七缺失时明确使用历史值零，字段存在时仍要求八字节数值。
     * @param encoded 当前币对参考价格的独立字段记录字节
     */
    private static CoreMarkPriceState readCoreMarkPriceState(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreMarkPriceState(
                r.text(1), // 1：币对或合约业务编号
                r.number(2), // 2：标记价格
                r.number(3), // 3：指数参考价格
                r.number(4), // 4：远期参考价格
                r.number(5), // 5：参考价格序号
                r.number(6), // 6：参考价格生成时刻，毫秒
                r.number(7)); // 7：最近成交价
    }

    /**
     * 按固定字段编号写入强平业务记录，保存计划数量、执行数据、亏空、费用、业务状态和下一撤单游标；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的强平业务记录业务值
     */
    private static byte[] writeCoreLiquidationState(CoreLiquidationState value) {
        return new SnapshotFields.Writer()
                .number(1, value.liquidationId()) // 1：强平业务记录编号
                .number(2, value.userId()) // 2：所属用户编号
                .text(3, value.instrumentId()) // 3：币对或合约业务编号
                .number(4, SnapshotEnumCodes.encode(value.marginMode())) // 4：全仓或逐仓保证金模式
                .number(5, SnapshotEnumCodes.encode(value.positionSide())) // 5：净持仓、多仓或空仓方向
                .number(6, value.triggerPriceSequence()) // 6：产生强平计划时的参考价格序号
                .number(7, value.signedQuantitySteps()) // 7：带方向的持仓数量，负数为空仓
                .number(8, value.closeQuantitySteps()) // 8：强平计划需要关闭的正数数量
                .number(9, value.deficitUnits()) // 9：强平尚未覆盖的亏空金额
                .number(10, value.executionPriceTicks()) // 10：强平实际执行价
                .number(11, value.liquidationFeeRatePpm()) // 11：强平费率，百万分比
                .number(12, value.liquidationFeeUnits()) // 12：实际收取的强平费用
                .number(13, SnapshotEnumCodes.encode(value.status())) // 13：当前业务记录状态
                .number(14, value.nextCancelOrderId()) // 14：强平下一页撤单游标
                .encode();
    }
    /**
     * 按固定字段编号读取并构造强平业务记录，恢复计划数量、执行数据、亏空、费用、业务状态和下一撤单游标；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前强平业务记录的独立字段记录字节
     */
    private static CoreLiquidationState readCoreLiquidationState(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreLiquidationState(
                r.number(1), // 1：强平业务记录编号
                r.number(2), // 2：所属用户编号
                r.text(3), // 3：币对或合约业务编号
                SnapshotEnumCodes.readCoreMarginMode(r.integer(4)), // 4：全仓或逐仓保证金模式
                SnapshotEnumCodes.readCorePositionSide(r.integer(5)), // 5：净持仓、多仓或空仓方向
                r.number(6), // 6：产生强平计划时的参考价格序号
                r.number(7), // 7：带方向的持仓数量，负数为空仓
                r.number(8), // 8：强平计划需要关闭的正数数量
                r.number(9), // 9：强平尚未覆盖的亏空金额
                r.number(10), // 10：强平实际执行价
                r.number(11), // 11：强平费率，百万分比
                r.number(12), // 12：实际收取的强平费用
                SnapshotEnumCodes.readCoreLiquidationStateStatus(r.integer(13)), // 13：当前业务记录状态
                r.number(14)); // 14：强平下一页撤单游标
    }

    /**
     * 按固定字段编号写入单账户 Lane 风险扫描进度，保存用户与持仓/预留游标、阶段、中间累计值和账户/行情修订；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的单账户 Lane 风险扫描进度业务值
     */
    private static byte[] writeRiskLaneProgress(RiskLaneProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.lastUserId()) // 1：当前分页已处理到的用户编号
                .bool(2, value.complete()) // 2：本次扫描范围是否已经结束
                .number(3, value.userId()) // 3：所属用户编号
                .number(4, value.phase()) // 4：当前 Lane 估值处理阶段
                .text(5, value.positionCursor()) // 5：当前用户估值的持仓业务键游标
                .number(6, value.reservationCursor()) // 6：当前用户估值的订单预留游标
                .number(7, value.unrealizedPnlUnits()) // 7：尚未实现的持仓盈亏
                .number(8, value.maintenanceMarginUnits()) // 8：估值要求的维持保证金金额
                .number(9, value.isolatedMarginUnits()) // 9：当前估值累计的逐仓保证金
                .number(10, value.isolatedReservationUnits()) // 10：当前估值累计的逐仓订单预留
                .number(11, value.userRevision()) // 11：当前用户估值时的修订号
                .number(12, value.marketRevision()) // 12：最近行情输入修订号
                .encode();
    }
    /**
     * 按固定字段编号读取并构造单账户 Lane 风险扫描进度，恢复用户与持仓/预留游标、阶段、中间累计值和账户/行情修订；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前单账户 Lane 风险扫描进度的独立字段记录字节
     */
    private static RiskLaneProgress readRiskLaneProgress(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new RiskLaneProgress(
                r.number(1), // 1：当前分页已处理到的用户编号
                r.bool(2), // 2：本次扫描范围是否已经结束
                r.number(3), // 3：所属用户编号
                r.integer(4), // 4：当前 Lane 估值处理阶段
                r.text(5), // 5：当前用户估值的持仓业务键游标
                r.number(6), // 6：当前用户估值的订单预留游标
                r.number(7), // 7：尚未实现的持仓盈亏
                r.number(8), // 8：估值要求的维持保证金金额
                r.number(9), // 9：当前估值累计的逐仓保证金
                r.number(10), // 10：当前估值累计的逐仓订单预留
                r.number(11), // 11：当前用户估值时的修订号
                r.number(12)); // 12：最近行情输入修订号
    }

    /**
     * 按固定字段编号写入风险扫描控制，保存配置版本、规则、开关、调度参数和审计信息；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的风险扫描控制业务值
     */
    private static byte[] writeCoreRiskScanControlView(CoreRiskScanControlView value) {
        return new SnapshotFields.Writer()
                .number(1, value.version()) // 1：风险扫描控制配置版本号
                .text(2, value.ruleName()) // 2：风险扫描规则名称
                .bool(3, value.enabled()) // 3：风险扫描规则是否启用
                .number(4, value.scanDelayMs()) // 4：风险扫描调度间隔，毫秒
                .number(5, value.scanBatchSize()) // 5：每次风险扫描的用户批量上限
                .text(6, value.updatedBy()) // 6：风险规则更新人
                .text(7, value.reason()) // 7：风险规则变更原因
                .number(8, value.updatedAtEpochMillis()) // 8：记录最近更新时刻，毫秒
                .encode();
    }
    /**
     * 按固定字段编号读取并构造风险扫描控制，恢复配置版本、规则、开关、调度参数和审计信息；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前风险扫描控制的独立字段记录字节
     */
    private static CoreRiskScanControlView readCoreRiskScanControlView(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskScanControlView(
                r.number(1), // 1：风险扫描控制配置版本号
                r.text(2), // 2：风险扫描规则名称
                r.bool(3), // 3：风险扫描规则是否启用
                r.number(4), // 4：风险扫描调度间隔，毫秒
                r.integer(5), // 5：每次风险扫描的用户批量上限
                r.text(6), // 6：风险规则更新人
                r.text(7), // 7：风险规则变更原因
                r.number(8)); // 8：记录最近更新时刻，毫秒
    }

    /** 风险档按列表顺序保存上界及金融参数；档位编号和由前档上界确定的下界不落盘。 */
    private static byte[] writeCoreRiskLimitBracket(CoreRiskLimitBracket value) {
        return new SnapshotFields.Writer()
                .number(3, value.notionalCapUnits())
                .number(4, value.maxLeveragePpm())
                .number(5, value.initialMarginRatePpm())
                .number(6, value.maintenanceMarginRatePpm())
                .number(7, value.optionMarginFactorPpm())
                .encode();
    }

    /** 列表顺序及递增上界由CoreInstrumentRiskLimits统一校验，字段1、2已退役。 */
    private static CoreRiskLimitBracket readCoreRiskLimitBracket(byte[] encoded) {
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskLimitBracket(r.number(3), r.number(4), r.number(5), r.number(6), r.number(7));
    }

    /**
     * 按固定字段编号写入币对维护设置，保存维护任务编号、模式和固定结算价；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的币对维护设置业务值
     */
    private static byte[] writeCoreInstrumentMaintenance(CoreInstrumentMaintenance value) {
        return new SnapshotFields.Writer()
                .number(1, value.taskId()) // 1：币对维护任务编号
                .number(2, SnapshotEnumCodes.encode(value.mode())) // 2：币对维护模式
                .number(3, value.settlementPriceTicks()) // 3：固定到期或维护结算价格
                .encode();
    }
    /**
     * 按固定字段编号读取并构造币对维护设置，恢复维护任务编号、模式和固定结算价；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前币对维护设置的独立字段记录字节
     */
    private static CoreInstrumentMaintenance readCoreInstrumentMaintenance(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreInstrumentMaintenance(
                r.number(1), // 1：币对维护任务编号
                SnapshotEnumCodes.readCoreInstrumentMaintenanceMode(r.integer(2)), // 2：币对维护模式
                r.number(3)); // 3：固定到期或维护结算价格
    }

    /**
     * 写算法父单切片、关联子单、时间和状态，固定保留未使用的字段二十；字段二十八保存退休子单累计成交量，二十九保存等待父单关联的已关闭子单编号。
     * @param value 当前要写入的算法父单业务值
     */
    private static byte[] writeCoreAlgoOrderState(CoreAlgoOrderState value) {
        return new SnapshotFields.Writer()
                .number(1, value.algoOrderId()) // 1：算法父单编号
                .number(2, value.userId()) // 2：所属用户编号
                .text(3, value.clientAlgoOrderId()) // 3：客户指定的算法单编号
                .text(4, value.instrumentId()) // 4：币对或合约业务编号
                .number(5, value.algoTypeCode()) // 5：算法单类型编号
                .number(6, SnapshotEnumCodes.encode(value.side())) // 6：买入或卖出方向
                .number(7, value.priceTicks()) // 7：原请求订单价格
                .number(8, value.quantitySteps()) // 8：订单原请求数量
                .number(9, value.childQuantitySteps()) // 9：算法单每个子单的数量
                .number(10, value.intervalSeconds()) // 10：算法单相邻切片间隔，秒
                .number(11, value.durationSeconds()) // 11：算法单总执行时长，秒
                .number(12, SnapshotEnumCodes.encode(value.marginMode())) // 12：全仓或逐仓保证金模式
                .number(13, SnapshotEnumCodes.encode(value.positionSide())) // 13：净持仓、多仓或空仓方向
                .bool(14, value.reduceOnly()) // 14：是否只允许减少已有持仓
                .bool(15, value.postOnly()) // 15：是否只允许成为挂单 Maker
                .number(16, SnapshotEnumCodes.encode(value.timeInForce())) // 16：GTC、IOC、FOK 或 GTX 的订单有效方式
                .number(17, value.statusCode()) // 17：算法父单业务状态编号
                .number(18, value.currentOrderId()) // 18：算法父单当前正在推进的子订单编号
                .text(19, value.rejectReason()) // 19：算法或触发操作保存的拒绝原因
                .number(21, value.startAtEpochMillis()) // 21：算法单开始执行时刻，毫秒
                .number(22, value.nextSliceAtEpochMillis()) // 22：算法单下个切片计划时刻，毫秒
                .number(23, value.completedAtEpochMillis()) // 23：算法单完成时刻，毫秒
                .number(24, value.createdAtEpochMillis()) // 24：记录创建时刻，毫秒
                .number(25, value.updatedAtEpochMillis()) // 25：记录最近更新时刻，毫秒
                .number(26, value.revision()) // 26：当前业务记录修订号
                .list(27, value.childOrderIds(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode()) // 27：算法父单关联的子订单编号列表
                .number(28, value.retiredExecutedQuantitySteps())
                .number(29, value.pendingClosedChildId())
                .encode();
    }
    /**
     * 按固定编号恢复算法父单，包含字段二十八退休成交量和二十九待关联终态子单；这两字段在当前格式中为必填，最低读取需求由根字段十二声明。
     * @param encoded 当前算法父单的独立字段记录字节
     */
    private static CoreAlgoOrderState readCoreAlgoOrderState(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreAlgoOrderState(
                r.number(1), // 1：算法父单编号
                r.number(2), // 2：所属用户编号
                r.text(3), // 3：客户指定的算法单编号
                r.text(4), // 4：币对或合约业务编号
                r.integer(5), // 5：算法单类型编号
                SnapshotEnumCodes.readCoreOrderSide(r.integer(6)), // 6：买入或卖出方向
                r.number(7), // 7：原请求订单价格
                r.number(8), // 8：订单原请求数量
                r.number(9), // 9：算法单每个子单的数量
                r.number(10), // 10：算法单相邻切片间隔，秒
                r.number(11), // 11：算法单总执行时长，秒
                SnapshotEnumCodes.readCoreMarginMode(r.integer(12)), // 12：全仓或逐仓保证金模式
                SnapshotEnumCodes.readCorePositionSide(r.integer(13)), // 13：净持仓、多仓或空仓方向
                r.bool(14), // 14：是否只允许减少已有持仓
                r.bool(15), // 15：是否只允许成为挂单 Maker
                SnapshotEnumCodes.readCoreTimeInForce(r.integer(16)), // 16：GTC、IOC、FOK 或 GTX 的订单有效方式
                r.integer(17), // 17：算法父单业务状态编号
                r.number(18), // 18：算法父单当前正在推进的子订单编号
                r.text(19), // 19：算法或触发操作保存的拒绝原因
                r.number(21), // 21：算法单开始执行时刻，毫秒
                r.number(22), // 22：算法单下个切片计划时刻，毫秒
                r.number(23), // 23：算法单完成时刻，毫秒
                r.number(24), // 24：记录创建时刻，毫秒
                r.number(25), // 25：记录最近更新时刻，毫秒
                r.number(26), // 26：当前业务记录修订号
                r.list(27, /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 27：算法父单关联的子订单编号列表
                r.number(28), // 二十八：已退休子单累计成交数量
                r.number(29)); // 二十九：等待父单关联的已关闭子单编号
    }

    /**
     * 按固定字段编号写入倒计时撤单状态，保存用户范围、倒计时、触发状态、累计撤单数和修订号；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的倒计时撤单状态业务值
     */
    private static byte[] writeCoreCancelAllAfterState(CoreCancelAllAfterState value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId()) // 1：所属用户编号
                .text(2, value.symbolScope()) // 2：倒计时撤单的币对范围
                .number(3, value.countdownMillis()) // 3：倒计时撤单等待时长，毫秒
                .number(4, SnapshotEnumCodes.encode(value.status())) // 4：当前业务记录状态
                .number(5, value.triggerAtEpochMillis()) // 5：倒计时撤单触发时刻，毫秒
                .number(6, value.updatedAtEpochMillis()) // 6：记录最近更新时刻，毫秒
                .number(7, value.canceledOrders()) // 7：已撤普通订单数量
                .number(8, value.canceledTriggerOrders()) // 8：已撤触发单数量
                .number(9, value.revision()) // 9：当前业务记录修订号
                .encode();
    }
    /**
     * 按固定字段编号读取并构造倒计时撤单状态，恢复用户范围、倒计时、触发状态、累计撤单数和修订号；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前倒计时撤单状态的独立字段记录字节
     */
    private static CoreCancelAllAfterState readCoreCancelAllAfterState(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreCancelAllAfterState(
                r.number(1), // 1：所属用户编号
                r.text(2), // 2：倒计时撤单的币对范围
                r.number(3), // 3：倒计时撤单等待时长，毫秒
                SnapshotEnumCodes.readCoreCancelAllAfterStatus(r.integer(4)), // 4：当前业务记录状态
                r.number(5), // 5：倒计时撤单触发时刻，毫秒
                r.number(6), // 6：记录最近更新时刻，毫秒
                r.integer(7), // 7：已撤普通订单数量
                r.integer(8), // 8：已撤触发单数量
                r.number(9)); // 9：当前业务记录修订号
    }

    /**
     * 按固定字段编号写入杠杆设置业务键，保存用户、币对和保证金模式；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的杠杆设置业务键业务值
     */
    private static byte[] writeCoreLeverageKey(CoreLeverageKey value) {
        return new SnapshotFields.Writer()
                .number(1, value.userId()) // 1：所属用户编号
                .text(2, value.instrumentId()) // 2：币对或合约业务编号
                .number(3, SnapshotEnumCodes.encode(value.marginMode())) // 3：全仓或逐仓保证金模式
                .encode();
    }
    /**
     * 按固定字段编号读取并构造杠杆设置业务键，恢复用户、币对和保证金模式；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前杠杆设置业务键的独立字段记录字节
     */
    private static CoreLeverageKey readCoreLeverageKey(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreLeverageKey(
                r.number(1), // 1：所属用户编号
                r.text(2), // 2：币对或合约业务编号
                SnapshotEnumCodes.readCoreMarginMode(r.integer(3))); // 3：全仓或逐仓保证金模式
    }

    /**
     * 按固定字段编号写入触发单记录，保存触发条件、追踪止损状态、子单参数、OCO、费用、价格来源及终态时间；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的触发单记录业务值
     */
    private static byte[] writeCoreTriggerOrderStateView(CoreTriggerOrderStateView value) {
        return new SnapshotFields.Writer()
                .number(1, value.triggerOrderId()) // 1：触发单业务编号
                .number(3, value.userId()) // 3：所属用户编号
                .text(4, value.clientTriggerOrderId()) // 4：客户指定的触发单号
                .text(5, value.ocoGroupId()) // 5：一腿触发后取消另一腿的 OCO 分组编号
                .text(6, value.instrumentId()) // 6：币对或合约业务编号
                .number(7, SnapshotEnumCodes.encode(value.side())) // 7：买入或卖出方向
                .number(8, SnapshotEnumCodes.encode(value.triggerType())) // 8：止盈、止损或追踪止损类型
                .number(9, SnapshotEnumCodes.encode(value.triggerCondition())) // 9：价格大于等于或小于等于的触发条件
                .number(10, value.triggerPriceTicks()) // 10：触发阈值价格
                .number(11, value.activationPriceTicks()) // 11：追踪触发单激活价
                .number(12, value.callbackRatePpm()) // 12：追踪止损回调比例，百万分比
                .number(13, value.highestPriceTicks()) // 13：追踪触发单激活后保存的最高价
                .number(14, value.lowestPriceTicks()) // 14：追踪触发单激活后保存的最低价
                .number(15, value.activatedAtEpochMillis()) // 15：追踪触发单激活时刻，毫秒
                .number(16, SnapshotEnumCodes.encode(value.orderType())) // 16：限价或市价订单类型
                .number(17, SnapshotEnumCodes.encode(value.timeInForce())) // 17：GTC、IOC、FOK 或 GTX 的订单有效方式
                .number(18, value.priceTicks()) // 18：原请求订单价格
                .number(19, value.quantitySteps()) // 19：订单原请求数量
                .number(20, SnapshotEnumCodes.encode(value.marginMode())) // 20：全仓或逐仓保证金模式
                .number(21, SnapshotEnumCodes.encode(value.positionSide())) // 21：净持仓、多仓或空仓方向
                .number(22, SnapshotEnumCodes.encode(value.status())) // 22：当前业务记录状态
                .number(23, value.placedOrderId()) // 23：触发后生成的普通订单编号
                .number(24, value.triggerSequence()) // 24：实际触发时保存的价格序号
                .number(25, value.triggeredPriceTicks()) // 25：触发时保存的参考价
                .text(26, value.rejectReason()) // 26：算法或触发操作保存的拒绝原因
                .number(28, value.expiresAtEpochMillis()) // 28：触发单失效时刻，毫秒
                .number(29, value.triggeredAtEpochMillis()) // 29：触发单实际触发时刻，毫秒
                .number(30, value.createdAtEpochMillis()) // 30：记录创建时刻，毫秒
                .number(31, value.updatedAtEpochMillis()) // 31：记录最近更新时刻，毫秒
                .number(32, value.revision()) // 32：当前业务记录修订号
                .number(33, value.makerFeeRatePpm()) // 33：Maker 手续费率，百万分比
                .number(34, value.takerFeeRatePpm()) // 34：Taker 手续费率，百万分比
                .number(35, SnapshotEnumCodes.encode(value.priceSource())) // 35：触发单使用标记价、最近成交价或指数价的来源
                .encode();
    }
    /**
     * 按固定字段编号读取并构造触发单记录，恢复触发条件、追踪止损状态、子单参数、OCO、费用、价格来源及终态时间；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前触发单记录的独立字段记录字节
     */
    private static CoreTriggerOrderStateView readCoreTriggerOrderStateView(byte[] encoded, ProductLine productLine) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreTriggerOrderStateView(
                r.number(1), // 1：触发单业务编号
                productLine, // 产品归属取自已校验的根字段
                r.number(3), // 3：所属用户编号
                r.text(4), // 4：客户指定的触发单号
                r.text(5), // 5：一腿触发后取消另一腿的 OCO 分组编号
                r.text(6), // 6：币对或合约业务编号
                SnapshotEnumCodes.readCoreOrderSide(r.integer(7)), // 7：买入或卖出方向
                SnapshotEnumCodes.readCoreTriggerOrderType(r.integer(8)), // 8：止盈、止损或追踪止损类型
                SnapshotEnumCodes.readCoreTriggerCondition(r.integer(9)), // 9：价格大于等于或小于等于的触发条件
                r.number(10), // 10：触发阈值价格
                r.number(11), // 11：追踪触发单激活价
                r.number(12), // 12：追踪止损回调比例，百万分比
                r.number(13), // 13：追踪触发单激活后保存的最高价
                r.number(14), // 14：追踪触发单激活后保存的最低价
                r.number(15), // 15：追踪触发单激活时刻，毫秒
                SnapshotEnumCodes.readCoreOrderType(r.integer(16)), // 16：限价或市价订单类型
                SnapshotEnumCodes.readCoreTimeInForce(r.integer(17)), // 17：GTC、IOC、FOK 或 GTX 的订单有效方式
                r.number(18), // 18：原请求订单价格
                r.number(19), // 19：订单原请求数量
                SnapshotEnumCodes.readCoreMarginMode(r.integer(20)), // 20：全仓或逐仓保证金模式
                SnapshotEnumCodes.readCorePositionSide(r.integer(21)), // 21：净持仓、多仓或空仓方向
                SnapshotEnumCodes.readCoreTriggerOrderStatus(r.integer(22)), // 22：当前业务记录状态
                r.number(23), // 23：触发后生成的普通订单编号
                r.number(24), // 24：实际触发时保存的价格序号
                r.number(25), // 25：触发时保存的参考价
                r.text(26), // 26：算法或触发操作保存的拒绝原因
                r.number(28), // 28：触发单失效时刻，毫秒
                r.number(29), // 29：触发单实际触发时刻，毫秒
                r.number(30), // 30：记录创建时刻，毫秒
                r.number(31), // 31：记录最近更新时刻，毫秒
                r.number(32), // 32：当前业务记录修订号
                r.number(33), // 33：Maker 手续费率，百万分比
                r.number(34), // 34：Taker 手续费率，百万分比
                SnapshotEnumCodes.readCoreTriggerPriceSource(r.integer(35))); // 35：触发单使用标记价、最近成交价或指数价的来源
    }

    /**
     * 按固定字段编号写入币对风险与触发扫描进度，保存全局和各 Lane 进度、估值中间量、触发索引游标及固定价格输入；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的币对风险与触发扫描进度业务值
     */
    private static byte[] writeRiskScan(RiskScan value) {
        return new SnapshotFields.Writer()
                .text(1, value.instrumentId()) // 1：币对或合约业务编号
                .number(2, value.accountLaneId()) // 2：账户 Lane 的分页位置
                .number(3, value.priceSequence()) // 3：参考价格序号
                .number(4, value.scanStartPriceSequence()) // 4：此轮风险扫描开始时的价格序号
                .number(5, value.lastUserId()) // 5：当前分页已处理到的用户编号
                .bool(6, value.riskComplete()) // 6：本币对当前风险扫描是否结束
                .number(7, value.riskUserId()) // 7：当前正在估值的用户编号
                .number(8, value.riskPhase()) // 8：当前用户风险估值阶段
                .text(9, value.riskPositionCursor()) // 9：当前用户风险估值的持仓游标
                .number(10, value.riskReservationCursor()) // 10：当前用户风险估值的订单预留游标
                .number(11, value.riskUnrealizedPnlUnits()) // 11：本次用户风险扫描累计的未实现盈亏
                .number(12, value.riskMaintenanceMarginUnits()) // 12：本次用户风险扫描累计的维持保证金
                .number(13, value.riskIsolatedMarginUnits()) // 13：本次用户风险扫描累计的逐仓保证金
                .number(14, value.riskIsolatedReservationUnits()) // 14：本次用户风险扫描累计的逐仓订单预留
                .bool(15, value.triggerComplete()) // 15：当前币对触发单扫描是否结束
                .number(16, value.triggerPhase()) // 16：触发单扫描阶段
                .number(17, value.triggerPriceCursor()) // 17：触发条件价格索引的分页位置
                .number(18, value.triggerOrderCursor()) // 18：当前触发条件下的订单游标
                .number(19, value.triggerUpperId()) // 19：本轮触发扫描固定的编号上界
                .number(20, value.triggerMarkPriceTicks()) // 20：本轮触发扫描固定使用的价格
                .number(21, value.triggerGeneratedAtEpochMillis()) // 21：本轮触发扫描固定价格的生成时刻，毫秒
                .number(22, value.triggerOcoOrderId()) // 22：正在清理 OCO 兄弟单的触发单编号
                .number(23, value.triggerOcoCursor()) // 23：当前 OCO 兄弟单扫描游标
                .number(24, value.lastScheduledRevision()) // 24：此币对上次安排扫描时的修订号
                .list(25, value.laneProgress(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeRiskLaneProgress(item)) // 25：各账户 Lane 保存的风险扫描进度列表
                .encode();
    }
    /**
     * 按固定字段编号读取并构造币对风险与触发扫描进度，恢复全局和各 Lane 进度、估值中间量、触发索引游标及固定价格输入；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前币对风险与触发扫描进度的独立字段记录字节
     */
    private static RiskScan readRiskScan(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new RiskScan(
                r.text(1), // 1：币对或合约业务编号
                r.integer(2), // 2：账户 Lane 的分页位置
                r.number(3), // 3：参考价格序号
                r.number(4), // 4：此轮风险扫描开始时的价格序号
                r.number(5), // 5：当前分页已处理到的用户编号
                r.bool(6), // 6：本币对当前风险扫描是否结束
                r.number(7), // 7：当前正在估值的用户编号
                r.integer(8), // 8：当前用户风险估值阶段
                r.text(9), // 9：当前用户风险估值的持仓游标
                r.number(10), // 10：当前用户风险估值的订单预留游标
                r.number(11), // 11：本次用户风险扫描累计的未实现盈亏
                r.number(12), // 12：本次用户风险扫描累计的维持保证金
                r.number(13), // 13：本次用户风险扫描累计的逐仓保证金
                r.number(14), // 14：本次用户风险扫描累计的逐仓订单预留
                r.bool(15), // 15：当前币对触发单扫描是否结束
                r.integer(16), // 16：触发单扫描阶段
                r.number(17), // 17：触发条件价格索引的分页位置
                r.number(18), // 18：当前触发条件下的订单游标
                r.number(19), // 19：本轮触发扫描固定的编号上界
                r.number(20), // 20：本轮触发扫描固定使用的价格
                r.number(21), // 21：本轮触发扫描固定价格的生成时刻，毫秒
                r.number(22), // 22：正在清理 OCO 兄弟单的触发单编号
                r.number(23), // 23：当前 OCO 兄弟单扫描游标
                r.number(24), // 24：此币对上次安排扫描时的修订号
                r.list(25, /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readRiskLaneProgress(item))); // 25：各账户 Lane 保存的风险扫描进度列表
    }

    /**
     * 按固定字段编号写入资金费续页进度，保存轮次、费率、用户游标、命令 UUID 和本轮固定标记价/序号；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的资金费续页进度业务值
     */
    private static byte[] writeFundingProgress(FundingProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.settlementId()) // 1：资金费或到期结算轮次编号
                .number(2, value.fundingRatePpm()) // 2：本轮资金费率，百万分比
                .number(4, value.nextCursorUserId()) // 4：资金费或到期结算下一页用户游标
                .number(6, value.markPriceTicks()) // 6：标记价格
                .number(7, value.priceSequence()) // 7：参考价格序号
                .encode();
    }
    /**
     * 按固定字段编号读取并构造资金费续页进度，恢复轮次、费率、用户游标、命令 UUID 和本轮固定标记价/序号；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前资金费续页进度的独立字段记录字节
     */
    private static FundingProgress readFundingProgress(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new FundingProgress(r.number(1), // 1：资金费或到期结算轮次编号
                r.number(2), // 3：账户 Lane 的分页位置
                r.number(4), // 5：原业务命令 UUID
                r.number(6), // 6：标记价格
                r.number(7)); // 7：参考价格序号
    }

    /**
     * 按固定字段编号写入到期或固定价格结算续页进度，保存轮次、固定结算参数、撤单阶段、双游标及保险需求；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的到期或固定价格结算续页进度业务值
     */
    private static byte[] writeLifecycleProgress(LifecycleProgress value) {
        return new SnapshotFields.Writer()
                .number(1, value.settlementId()) // 1：资金费或到期结算轮次编号
                .number(2, value.settlementPriceTicks()) // 2：固定到期或维护结算价格
                .bool(4, value.ordersComplete()) // 4：本轮到期结算是否已完成全部撤单
                .number(6, value.nextCursorOrderId()) // 6：到期结算下一页订单游标
                .number(7, value.nextCursorUserId()) // 7：资金费或到期结算下一页用户游标
                .number(9, value.requiredInsuranceUnits()) // 9：本页到期结算需要补足的保险金额
                .encode();
    }
    /**
     * 按固定字段编号读取并构造到期或固定价格结算续页进度，恢复轮次、固定结算参数、撤单阶段、双游标及保险需求；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前到期或固定价格结算续页进度的独立字段记录字节
     */
    private static LifecycleProgress readLifecycleProgress(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new LifecycleProgress(r.number(1), // 1：资金费或到期结算轮次编号
                r.number(2), // 3：命令保存的每份期权结算金额参数
                r.bool(4), // 5：账户 Lane 的分页位置
                r.number(6), // 6：到期结算下一页订单游标
                r.number(7), // 8：原业务命令 UUID
                r.number(9)); // 9：本页到期结算需要补足的保险金额
    }

    /**
     * 按固定字段编号写入用户账户业务状态，保存产品线、用户修订、余额、预留、持仓和持仓模式；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的用户账户业务状态业务值
     */
    private static byte[] writeCoreUserState(CoreUserState value) {
        return new SnapshotFields.Writer()
                .number(2, value.userId()) // 2：所属用户编号
                .number(3, value.revision()) // 3：当前业务记录修订号
                .bytes(4, writeValues(value.balances(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeAssetBalance(item))) // 4：按资产保存的用户余额
                .bytes(5, writeValues(value.reservations(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeOrderReservation(item))) // 5：按订单编号保存的资金预留
                .bytes(6, writeValues(value.positions(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeCorePositionState(item))) // 6：按业务键保存的用户持仓
                .number(7, SnapshotEnumCodes.encode(value.positionMode())) // 7：单向或双向持仓模式
                .encode();
    }
    /**
     * 按固定字段编号读取并构造用户账户业务状态，恢复产品线、用户修订、余额、预留、持仓和持仓模式；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前用户账户业务状态的独立字段记录字节
     */
    private static CoreUserState readCoreUserState(byte[] encoded, ProductLine productLine) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreUserState(
                productLine, // 产品归属取自已校验的根字段
                r.number(2), // 2：所属用户编号
                r.number(3), // 3：当前业务记录修订号
                readValues(r.bytes(4), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readAssetBalance(item), AssetBalance::asset), // 4：按资产保存的用户余额
                readValues(r.bytes(5), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readOrderReservation(item), OrderReservation::orderId), // 5：按订单编号保存的资金预留
                readValues(r.bytes(6), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readCorePositionState(item), CorePositionState::key), // 6：按业务键保存的用户持仓
                SnapshotEnumCodes.readCorePositionMode(r.integer(7))); // 7：单向或双向持仓模式
    }

    /** 当前产品的标的配置；现货不持久化保证金、杠杆、风险档或 OI 限额字段。 */
    private static byte[] writeCoreInstrument(CoreInstrument value, ProductLine productLine) {
        if (value.contractType().productLine() != productLine)
            throw new ProtocolException("snapshot instrument product line mismatch");
        SnapshotFields.Writer writer = new SnapshotFields.Writer()
                .text(1, value.instrumentId())
                .text(3, value.baseAsset()).text(4, value.quoteAsset()).text(5, value.settleAsset())
                .number(6, value.notionalMultiplierUnits()).number(7, value.priceTickUnits())
                .number(8, value.settleScaleUnits()).number(11, value.makerFeeRatePpm())
                .number(12, value.takerFeeRatePpm()).number(13, value.expiryEpochMillis())
                .number(14, value.optionType() == null ? -1 : SnapshotEnumCodes.encode(value.optionType()))
                .number(15, value.strikePriceTicks()).bytes(21, writeCoreInstrumentMaintenance(value.maintenance()))
                .number(22, SnapshotEnumCodes.encode(value.instrumentStatus()))
                .bool(23, value.marketOrderEnabled()).bool(24, value.postOnlyEnabled()).bool(25, value.reduceOnlyEnabled())
                .number(26, value.supportedOrderTypeMask()).number(27, value.supportedTimeInForceMask())
                .number(28, value.quantityStepUnits()).number(29, value.orderProtection().marketMaxSlippagePpm())
                .number(30, value.orderProtection().marketMaxMarkAgeMs())
                .number(31, value.orderProtection().limitPriceProtectionEnabled() ? 1 : 0)
                .number(32, value.orderProtection().limitPriceBandPpm())
                .number(33, value.orderProtection().limitPriceMaxMarkAgeMs());
        if (value.riskLimits() != null) {
            var risk = value.riskLimits();
            writer.number(9, risk.initialMarginRatePpm())
                    .number(16, risk.maxLeveragePpm()).number(17, risk.maxPositionNotionalUnits())
                    .number(18, risk.userOpenInterestLimitRatePpm()).number(19, risk.userOpenInterestLimitFloorUnits())
                    .list(20, risk.riskLimitBrackets(), TradingStateSnapshotCodec::writeCoreRiskLimitBracket);
        }
        return writer.encode();
    }

    /** 根产品线决定风险字段是否存在；衍生品读取完整风险字段，现货没有风险配置。 */
    private static CoreInstrument readCoreInstrument(byte[] encoded, ProductLine productLine) {
        var r = new SnapshotFields.Reader(encoded);
        var risk = productLine.isDerivative() ? new CoreInstrumentRiskLimits(
                r.number(9), r.number(16), r.number(17), r.number(18), r.number(19),
                r.list(20, TradingStateSnapshotCodec::readCoreRiskLimitBracket)) : null;
        return new CoreInstrument(r.text(1),
                ContractType.valueOf(productLine.contractTypeCode()),
                r.text(3), r.text(4), r.text(5), r.number(6), r.number(7), r.number(8), risk,
                r.number(11), r.number(12), r.number(13),
                r.integer(14) == -1 ? null : SnapshotEnumCodes.readOptionType(r.integer(14)), r.number(15),
                readCoreInstrumentMaintenance(r.bytes(21)), SnapshotEnumCodes.readInstrumentStatus(r.integer(22)),
                r.bool(23), r.bool(24), r.bool(25), r.integer(26), r.integer(27), r.number(28),
                new com.surprising.aeron.protocol.CoreOrderProtection(r.number(29), r.number(30),
                        r.number(31) != 0, r.number(32), r.number(33)));
    }


    /**
     * 按固定字段编号写入风险业务状态，保存参考价格、账户风险、强平记录、扫描进度及扫描控制和行情修订；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的风险业务状态业务值
     */
    private static byte[] writeCoreRiskState(CoreRiskState value) {
        return new SnapshotFields.Writer()
                .bytes(1, writeValues(value.markPrices(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeCoreMarkPriceState(item))) // 1：按币对保存的标记价与参考价
                .bytes(2, writeValues(value.snapshots(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeCoreRiskSnapshot(item))) // 2：按用户币对方向保存的风险估值快照
                .bytes(3, writeValues(value.liquidations(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeCoreLiquidationState(item))) // 3：按强平编号保存的计划与处理记录
                .bytes(4, writeValues(value.scans(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeRiskScan(item))) // 4：各币对未完成的风险和触发扫描记录
                .number(5, value.nextLiquidationId()) // 5：后续分配的下一个强平编号
                .bytes(6, writeCoreRiskScanControlView(value.scanControl())) // 6：风险扫描开关、间隔及批量配置
                .number(7, value.marketRevision()) // 7：最近行情输入修订号
                .encode();
    }
    /**
     * 按固定字段编号读取并构造风险业务状态，恢复参考价格、账户风险、强平记录、扫描进度及扫描控制和行情修订；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前风险业务状态的独立字段记录字节
     */
    private static CoreRiskState readCoreRiskState(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreRiskState(
                readValues(r.bytes(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readCoreMarkPriceState(item), CoreMarkPriceState::instrumentId), // 1：按币对保存的标记价与参考价
                readValues(r.bytes(2), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readCoreRiskSnapshot(item), CoreRiskSnapshot::key), // 2：按用户币对方向保存的风险估值快照
                readValues(r.bytes(3), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readCoreLiquidationState(item), CoreLiquidationState::liquidationId), // 3：按强平编号保存的计划与处理记录
                readValues(r.bytes(4), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readRiskScan(item), RiskScan::instrumentId), // 4：各币对未完成的风险和触发扫描记录
                r.number(5), // 5：后续分配的下一个强平编号
                readCoreRiskScanControlView(r.bytes(6)), // 6：风险扫描开关、间隔及批量配置
                r.number(7)); // 7：最近行情输入修订号
    }

    /**
     * 按固定字段编号写入系统财库状态，保存七类资产金额表、已完成结算编号及未完成资金费和到期进度；枚举使用明确持久化编号，嵌套结构使用本快照专用记录编码。
     * @param value 当前要写入的系统财库状态业务值
     */
    private static byte[] writeCoreTreasuryState(CoreTreasuryState value) {
        return new SnapshotFields.Writer()
                .bytes(1, writeMap(value.feeBalances(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 1：按资产保存的手续费财库余额
                .bytes(2, writeMap(value.insuranceBalances(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 2：按资产保存的保险基金余额
                .bytes(3, writeMap(value.insuranceDeficits(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 3：按资产保存的保险亏空
                .bytes(4, writeMap(value.liquidationFeeBalances(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 4：按资产保存的强平费用财库余额
                .bytes(5, writeMap(value.fundingResidualBalances(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 5：按资产保存的实际资金费剩余差额
                .bytes(6, writeMap(value.roundingResidualBalances(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 6：按资产保存的舍入剩余差额
                .bytes(7, writeMap(value.clearingPnlBalances(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 7：按资产保存的清算盈亏财库余额
                .bytes(8, writeMap(value.fundingSettlements(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 8：各币对已完成的资金费结算编号
                .bytes(9, writeMap(value.lifecycleSettlements(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> new SnapshotFields.Writer().number(1, item).encode())) // 9：各币对已完成的到期或固定价格结算编号
                .bytes(10, writeMap(value.fundingProgress(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeFundingProgress(item))) // 10：按币对保存的未完成资金费进度
                .bytes(11, writeMap(value.lifecycleProgress(), /* 当前嵌套业务表的原始键，使用该表明确的数值或文本编码。 */ key -> new SnapshotFields.Writer().text(1, key).encode(), /* 当前嵌套列表或业务表中的一条值，按所属字段的专用记录写入。 */ item -> writeLifecycleProgress(item))) // 11：按币对保存的未完成到期或固定价格结算进度
                .encode();
    }
    /**
     * 按固定字段编号读取并构造系统财库状态，恢复七类资产金额表、已完成结算编号及未完成资金费和到期进度；字段类型由 SnapshotFields 检查，业务不变量沿用对应值类型构造器。
     * @param encoded 当前系统财库状态的独立字段记录字节
     */
    private static CoreTreasuryState readCoreTreasuryState(byte[] encoded) {
        // 当前独立业务记录的编号字段读取器，只访问本方法定义的字段。
        var r = new SnapshotFields.Reader(encoded);
        return new CoreTreasuryState(
                readMap(r.bytes(1), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 1：按资产保存的手续费财库余额
                readMap(r.bytes(2), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 2：按资产保存的保险基金余额
                readMap(r.bytes(3), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 3：按资产保存的保险亏空
                readMap(r.bytes(4), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 4：按资产保存的强平费用财库余额
                readMap(r.bytes(5), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 5：按资产保存的实际资金费剩余差额
                readMap(r.bytes(6), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 6：按资产保存的舍入剩余差额
                readMap(r.bytes(7), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 7：按资产保存的清算盈亏财库余额
                readMap(r.bytes(8), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 8：各币对已完成的资金费结算编号
                readMap(r.bytes(9), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> new SnapshotFields.Reader(item).number(1)), // 9：各币对已完成的到期或固定价格结算编号
                readMap(r.bytes(10), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readFundingProgress(item)), // 10：按币对保存的未完成资金费进度
                readMap(r.bytes(11), /* 当前嵌套 Map 键记录字节，按字段一解析其数值或文本。 */ key -> new SnapshotFields.Reader(key).text(1), /* 当前嵌套列表或 Map 值的记录字节，按所属字段的专用读取器解析。 */ item -> readLifecycleProgress(item))); // 11：按币对保存的未完成到期或固定价格结算进度
    }
}
