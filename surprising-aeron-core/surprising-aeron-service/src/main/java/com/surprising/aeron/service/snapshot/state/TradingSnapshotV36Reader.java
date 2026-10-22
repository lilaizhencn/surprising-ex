package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskSnapshot;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 已发布交易快照三十五/三十六版的固定布局读取器。字段按原顺序读取，不能随当前接口 Codec 或 Java 声明变化；嵌入算法和触发单也使用本类冻结的磁盘读取逻辑。只恢复内存业务状态，不启动运行时线程，也不写新历史格式。 构造约定：工具类不创建实例，读游标由每次调用独立 Reader 持有。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class TradingSnapshotV36Reader {

    /** 本历史读取器支持的最高布局版本三十六，入口同时接受三十五。 */
    private static final int VERSION = 36;
    /** 旧格式普通文本的最大字节数六十四。 */
    private static final int MAX_TEXT_BYTES = 64;
    /** 旧格式审计文本的最大字节数两千零四十八。 */
    private static final int MAX_AUDIT_TEXT_BYTES = 2_048;

    /**
     * 按历史固定顺序恢复用户、余额、预留、持仓、订单、币对、风险、财库和条件业务，检查产品线、数量、重复键及最终字节耗尽。三十五版缺少最近成交价时取零，三十六版读取该值；业务值构造器继续校验自身不变量，未统一包装所有构造异常。
     * @param encoded 带相应历史版本头的完整交易或嵌入记录字节
     * @param expectedProductLine 调用方需要恢复的独立产品线
     */
    public static TradingCoreState decode(byte[] encoded, ProductLine expectedProductLine) {
        // 按已发布固定顺序读取本次载荷的小端游标，不能交换字段顺序。
        Reader reader = new Reader(encoded);
        // 本次历史快照或嵌入记录保存的版本号。
        int version = reader.intValue();
        if (version < 35 || version > VERSION) {
            throw new ProtocolException("unsupported trading snapshot version: " + version);
        }
        // 独立产品线。
        ProductLine productLine = ProductLineWireCode.decode(reader.intValue());
        if (productLine != expectedProductLine) {
            throw new ProtocolException("trading snapshot product line mismatch");
        }
        // 快照保存的全局业务修订号。
        long revision = reader.nonNegativeLong("core revision");
        // 旧快照声明的用户条目数。
        int userCount = reader.count("users");
        // 按用户编号构造的恢复结果表。
        Map<Long, CoreUserState> users = new TreeMap<>();
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < userCount; index++) {
            // 所属用户编号。
            long userId = reader.positiveLong("userId");
            // 当前用户估值时的修订号。
            long userRevision = reader.nonNegativeLong("user revision");
            // 单向或双向持仓模式。
            CorePositionMode positionMode = CorePositionMode.fromWireCode(reader.intValue());
            // 按资产保存的用户余额。
            Map<String, AssetBalance> balances = new TreeMap<>();
            // 当前用户保存的资产余额条目数。
            int balanceCount = reader.count("balances");
            for (/* 当前用户余额区段内的条目序号。 */ int balanceIndex = 0; balanceIndex < balanceCount; balanceIndex++) {
                // 资产名称。
                String asset = reader.text();
                putUnique(balances, asset, new AssetBalance(asset,
                        reader.nonNegativeLong("available units"), reader.nonNegativeLong("locked units")));
            }
            // 按订单编号保存的资金预留。
            Map<Long, OrderReservation> reservations = new TreeMap<>();
            // 当前用户保存的订单预留条目数。
            int reservationCount = reader.count("reservations");
            for (/* 当前用户预留区段内的条目序号。 */ int reservationIndex = 0; reservationIndex < reservationCount; reservationIndex++) {
                // 普通订单业务编号。
                long orderId = reader.positiveLong("reservation orderId");
                // 按旧布局读取总额、累计释放与消耗后构造的资金预留。
                OrderReservation reservation = new OrderReservation(orderId, reader.text(),
                        ReservationKind.fromWireCode(reader.intValue()), reader.text(),
                        reader.positiveLong("reserved units"), reader.nonNegativeLong("released units"),
                        reader.nonNegativeLong("consumed units"), reader.positiveLong("order quantity"));
                putUnique(reservations, orderId, reservation);
            }
            // 按业务键保存的用户持仓。
            Map<String, CorePositionState> positions = new TreeMap<>();
            // 当前用户保存的持仓条目数。
            int positionCount = reader.count("positions");
            for (/* 当前用户持仓区段内的条目序号。 */ int positionIndex = 0; positionIndex < positionCount; positionIndex++) {
                // 币对或合约业务编号。
                String instrumentId = reader.text();
                // 持仓保证金资产名称。
                String marginAsset = reader.text();
                // 全仓或逐仓保证金模式。
                CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
                // 净持仓、多仓或空仓方向。
                CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
                // 当前恢复出的用户持仓，按已发布布局中的币对键写入该用户持仓表。
                CorePositionState position = new CorePositionState(instrumentId, marginAsset, marginMode, positionSide,
                        reader.longValue(),
                        reader.nonNegativeLong("entry price"), reader.nonNegativeLong("entry value"),
                        reader.longValue(), reader.nonNegativeLong("position margin"));
                putUnique(positions, instrumentId, position);
            }
            putUnique(users, userId, new CoreUserState(productLine, userId, userRevision,
                    balances, reservations, positions, positionMode));
        }
        // 按普通订单编号构造的恢复结果表。
        Map<Long, CoreOrderState> orders = new TreeMap<>();
        // 历史快照订单区段的条目数。
        int orderCount = reader.count("orders");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < orderCount; index++) {
            // 普通订单业务编号。
            long orderId = reader.positiveLong("orderId");
            // 所属用户编号。
            long userId = reader.positiveLong("order userId");
            // 币对或合约业务编号。
            String instrumentId = reader.text();
            // 买入或卖出方向。
            CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
            // 原请求订单价格。
            long priceTicks = reader.nonNegativeLong("price ticks");
            // 订单提交给 Matcher 的实际撮合保护价格。
            long matchingPriceTicks = reader.nonNegativeLong("matching price ticks");
            // 订单原请求数量。
            long quantitySteps = reader.positiveLong("quantity steps");
            // 原订单累计已经成交的数量。
            long executedSteps = reader.nonNegativeLong("executed steps");
            // 原订单剩余未成交数量。
            long remainingSteps = reader.nonNegativeLong("remaining steps");
            // 是否只允许减少已有持仓。
            boolean reduceOnly = reader.booleanValue();
            // 当前普通订单的全仓或逐仓模式。
            CoreMarginMode orderMarginMode = CoreMarginMode.fromWireCode(reader.intValue());
            // 当前普通订单的净持仓或多空腿方向。
            CorePositionSide orderPositionSide = CorePositionSide.fromWireCode(reader.intValue());
            // 限价或市价订单类型。
            CoreOrderType orderType = CoreOrderType.fromWireCode(reader.intValue());
            // GTC、IOC、FOK 或 GTX 的订单有效方式。
            CoreTimeInForce timeInForce = CoreTimeInForce.fromWireCode(reader.intValue());
            // 是否只允许成为挂单 Maker。
            boolean postOnly = reader.booleanValue();
            // 客户指定的普通订单号。
            String clientOrderId = reader.optionalText();
            // 原业务命令 UUID。
            UUID commandId = new UUID(reader.longValue(), reader.longValue());
            // Maker 手续费率，百万分比。
            long makerFeeRatePpm = reader.longValue();
            // Taker 手续费率，百万分比。
            long takerFeeRatePpm = reader.longValue();
            // 订单累计实际手续费金额。
            long cumulativeFeeUnits = reader.longValue();
            // 订单累计一百二十八位成交价值的高半部。
            long valueHigh = reader.longValue();
            // 订单累计一百二十八位成交价值的低半部。
            long valueLow = reader.longValue();
            // 订单原创建时刻，毫秒。
            long createdAt = reader.nonNegativeLong("order created time");
            // 普通订单或倒计时撤单记录的最近更新时间，毫秒。
            long updatedAt = reader.nonNegativeLong("order updated time");
            // 订单保存的 Cluster 日志位置。
            long clusterPosition = reader.nonNegativeLong("order cluster position");
            // 当前普通订单的持久化状态数字。
            int statusCode = reader.intValue();
            if (statusCode < 0 || statusCode >= CoreOrderStatus.values().length) {
                throw new ProtocolException("invalid order status: " + statusCode);
            }
            // 由当前固定布局字段构造的普通订单业务值。
            CoreOrderState order = new CoreOrderState(orderId, productLine, userId, instrumentId,
                    side,
                    priceTicks, matchingPriceTicks, quantitySteps, executedSteps, remainingSteps, reduceOnly,
                    orderMarginMode, orderPositionSide, orderType, timeInForce, postOnly,
                    clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                    cumulativeFeeUnits, valueHigh, valueLow, createdAt, updatedAt, clusterPosition,
                    SnapshotEnumCodes.readCoreOrderStatus(statusCode), reader.positiveLong("order revision"));
            putUnique(orders, orderId, order);
        }
        // 按币对业务编号构造的注册配置恢复结果表。
        Map<String, CoreInstrument> instruments = new TreeMap<>();
        // 历史币对配置区段的条目数。
        int instrumentCount = reader.count("instruments");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < instrumentCount; index++) {
            // 币对或合约业务编号。
            String instrumentId = reader.text();
            // 旧布局中保存的合约类型数字，先做范围检查再按固定映射解析。
            int contractType = reader.intValue();
            if (contractType < 0 || contractType >= ContractType.values().length) {
                throw new ProtocolException("invalid contract type: " + contractType);
            }
            // 按持久化编号解析出的现货、永续、交割或期权合约类型。
            ContractType decodedType = SnapshotEnumCodes.readContractType(contractType);
            // 基础资产名称。
            String baseAsset = reader.text();
            // 计价资产名称。
            String quoteAsset = reader.text();
            // 结算资产名称。
            String settleAsset = reader.text();
            // 币对名义金额乘数。
            long multiplier = reader.positiveLong("notional multiplier");
            // 每个价格刻度的整数单位。
            long priceTick = reader.positiveLong("price tick units");
            // 结算金额的整数缩放单位。
            long settleScale = reader.positiveLong("settle scale");
            // 币对初始保证金比例。
            long initialMargin = reader.positiveLong("initial margin rate");
            // 币对维持保证金比例。
            long maintenanceMargin = reader.positiveLong("maintenance margin rate");
            // 币对 Maker 手续费率，按原有符号读取。
            long makerFee = reader.longValue();
            // 币对 Taker 手续费率，按原有符号读取。
            long takerFee = reader.longValue();
            // 交割或期权到期时间，永续按原记录保存零。
            long expiry = reader.nonNegativeLong("expiry time");
            // 旧期权类型编号，负一表示该币对没有期权方向。
            int optionTypeCode = reader.intValue();
            if (optionTypeCode < -1 || optionTypeCode >= OptionType.values().length) {
                throw new ProtocolException("invalid option type: " + optionTypeCode);
            }
            // 期权行权价格。
            long strikePrice = reader.nonNegativeLong("strike price");
            // 币对最大杠杆。
            long maxLeverage = reader.positiveLong("max leverage");
            // 最大持仓名义金额。
            long maxPosition = reader.positiveLong("max position notional");
            // 用户占未平仓量的限制比例。
            long openInterestRate = reader.nonNegativeLong("open interest limit rate");
            // 用户未平仓名义金额限制的最低基准。
            long openInterestFloor = reader.positiveLong("open interest limit floor");
            // 原币对维护任务编号。
            long maintenanceTaskId = reader.nonNegativeLong("maintenance task");
            // 维护模式的持久化数字。
            int maintenanceMode = reader.intValue();
            // 当前维护枚举值数组，仅用于原有数字范围检查。
            var maintenanceModes = com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.values();
            if (maintenanceMode < 0 || maintenanceMode >= maintenanceModes.length) throw new ProtocolException("invalid maintenance mode");
            // 由维护任务、模式和固定结算价构造的币对维护值。
            var maintenance = new com.surprising.aeron.protocol.CoreInstrumentMaintenance(maintenanceTaskId,
                    SnapshotEnumCodes.readCoreInstrumentMaintenanceMode(maintenanceMode), reader.nonNegativeLong("maintenance price"));
            // 币对业务交易状态。
            int instrumentStatus = reader.intValue();
            if (instrumentStatus < 0 || instrumentStatus >= com.surprising.instrument.api.model.InstrumentStatus.values().length) {
                throw new ProtocolException("invalid instrument status: " + instrumentStatus);
            }
            // 币对是否允许市价单。
            boolean marketOrderEnabled = reader.booleanValue();
            // 币对是否支持只挂单标记。
            boolean postOnlyEnabled = reader.booleanValue();
            // 币对是否支持只减仓标记。
            boolean reduceOnlyEnabled = reader.booleanValue();
            // 币对允许的订单类型位图。
            int supportedOrderTypeMask = reader.intValue();
            // 币对允许的订单有效方式位图。
            int supportedTimeInForceMask = reader.intValue();
            // 当前币对保存的非空风险档位数量。
            int bracketCount = reader.count("risk limit brackets");
            if (bracketCount == 0) throw new ProtocolException("risk limit brackets are empty");
            // 按旧布局顺序读取并在币对构造前复制的风险档位列表。
            java.util.List<CoreRiskLimitBracket> brackets = new java.util.ArrayList<>(bracketCount);
            for (/* 风险档位列表中当前条目下标。 */ int bracketIndex = 0; bracketIndex < bracketCount; bracketIndex++) {
                brackets.add(new CoreRiskLimitBracket(reader.intValue(),
                        reader.nonNegativeLong("risk bracket floor"),
                        reader.positiveLong("risk bracket cap"),
                        reader.positiveLong("risk bracket max leverage"),
                        reader.positiveLong("risk bracket initial margin"),
                        reader.positiveLong("risk bracket maintenance margin"),
                        reader.positiveLong("option margin factor")));
            }
            // 按历史固定布局恢复的注册币对配置。
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
        // 按币对名称恢复的参考价格表。
        Map<String, CoreMarkPriceState> marks = new TreeMap<>();
        // 历史标记价区段的条目数。
        int markCount = reader.count("mark prices");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < markCount; index++) {
            // 币对或合约业务编号。
            String instrumentId = reader.text();
            // 当前币对标记价及参考价记录；版本三十五没有最近成交价，明确取零。
            CoreMarkPriceState mark = new CoreMarkPriceState(instrumentId,
                    reader.positiveLong("mark price"),
                    reader.nonNegativeLong("mark index price"), reader.nonNegativeLong("mark forward price"),
                    reader.positiveLong("price sequence"), reader.positiveLong("mark generated time"),
                    version < 36 ? 0 : reader.nonNegativeLong("last price"));
            putUnique(marks, instrumentId, mark);
        }
        // 按风险快照业务键恢复的风险估值表。
        Map<String, CoreRiskSnapshot> risks = new TreeMap<>();
        // 账户风险快照区段的条目数。
        int riskCount = reader.count("risk snapshots");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < riskCount; index++) {
            // 所属用户编号。
            long userId = reader.positiveLong("risk userId");
            // 币对或合约业务编号。
            String instrumentId = reader.text();
            // 净持仓、多仓或空仓方向。
            CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
            // 参考价格序号。
            long priceSequence = reader.positiveLong("risk price sequence");
            // 风险快照保存的有符号权益。
            long equity = reader.longValue();
            // 风险快照保存的有符号未实现盈亏。
            long unrealized = reader.longValue();
            // 当前账户风险快照保存的维持保证金金额。
            long maintenance = reader.nonNegativeLong("maintenance margin");
            // 风险快照保存的非负保证金比率。
            long ratio = reader.nonNegativeLong("margin ratio");
            // 账户风险状态的持久化数字，先检查范围再解析。
            int status = reader.intValue();
            if (status < 0 || status >= CoreRiskStatus.values().length) {
                throw new ProtocolException("invalid risk status: " + status);
            }
            // 当前恢复出的账户风险快照。
            CoreRiskSnapshot risk = new CoreRiskSnapshot(userId, instrumentId, positionSide,
                    priceSequence, equity, unrealized, maintenance, ratio, SnapshotEnumCodes.readCoreRiskStatus(status));
            putUnique(risks, risk.key(), risk);
        }
        // 按强平编号保存的计划与处理记录。
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>();
        // 历史强平记录区段的条目数。
        int liquidationCount = reader.count("liquidations");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < liquidationCount; index++) {
            // 强平业务记录编号。
            long liquidationId = reader.positiveLong("liquidationId");
            // 所属用户编号。
            long userId = reader.positiveLong("liquidation userId");
            // 币对或合约业务编号。
            String instrumentId = reader.text();
            // 全仓或逐仓保证金模式。
            CoreMarginMode marginMode = CoreMarginMode.fromWireCode(reader.intValue());
            // 净持仓、多仓或空仓方向。
            CorePositionSide positionSide = CorePositionSide.fromWireCode(reader.intValue());
            // 参考价格序号。
            long priceSequence = reader.positiveLong("liquidation price sequence");
            // 强平计划原持仓的有符号数量。
            long signedQuantity = reader.longValue();
            // 本强平计划要求关闭的正数数量。
            long closeQuantity = reader.positiveLong("liquidation close quantity");
            // 强平尚未覆盖的亏空金额。
            long deficitUnits = reader.nonNegativeLong("liquidation deficit");
            // 强平实际执行价。
            long executionPriceTicks = reader.nonNegativeLong("liquidation execution price");
            // 强平费率，百万分比。
            long liquidationFeeRatePpm = reader.nonNegativeLong("liquidation fee rate");
            // 实际收取的强平费用。
            long liquidationFeeUnits = reader.nonNegativeLong("liquidation fee units");
            // 强平业务状态的持久化数字，先检查范围再解析。
            int status = reader.intValue();
            if (status < 0 || status >= CoreLiquidationState.Status.values().length) {
                throw new ProtocolException("invalid liquidation status: " + status);
            }
            // 由执行数据、亏空、费用及游标恢复的强平记录。
            CoreLiquidationState liquidation = new CoreLiquidationState(liquidationId, userId, instrumentId,
                    marginMode, positionSide, priceSequence, signedQuantity, closeQuantity,
                    deficitUnits, executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits,
                    SnapshotEnumCodes.readCoreLiquidationStateStatus(status), reader.nonNegativeLong("liquidation cancel cursor"));
            putUnique(liquidations, liquidationId, liquidation);
        }
        // 各币对未完成的风险和触发扫描记录。
        Map<String, CoreRiskState.RiskScan> scans = new TreeMap<>();
        // 币对风险与触发扫描记录数。
        int scanCount = reader.count("risk scans");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < scanCount; index++) {
            // 当前扫描记录所属币对名称。
            String scanSymbol = reader.text();
            // 按旧布局恢复的风险和触发扫描阶段、游标及累计中间值。
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
        // 后续分配的下一个强平编号。
        long nextLiquidationId = reader.positiveLong("next liquidation id");
        // 最近行情输入修订号。
        long marketRevision = reader.nonNegativeLong("market revision");
        // 风险扫描开关、间隔及批量配置。
        CoreRiskScanControlView scanControl = new CoreRiskScanControlView(
                reader.positiveLong("risk scan control version"), reader.auditText(), reader.booleanValue(),
                reader.nonNegativeLong("risk scan delay"), reader.intValue(), reader.auditText(), reader.auditText(),
                reader.nonNegativeLong("risk scan control updated time"));
        // 汇总价格、风险快照、强平、扫描及控制配置的风险业务状态。
        CoreRiskState riskState = new CoreRiskState(marks, risks, liquidations, scans,
                nextLiquidationId, scanControl, marketRevision);
        // 按资产保存的手续费财库余额。
        Map<String, Long> feeBalances = readUnits(reader, "fee balances");
        // 按资产保存的保险基金余额。
        Map<String, Long> insuranceBalances = readUnits(reader, "insurance balances");
        // 按资产保存的保险亏空。
        Map<String, Long> insuranceDeficits = readUnits(reader, "insurance deficits");
        // 按资产保存的强平费用财库余额。
        Map<String, Long> liquidationFeeBalances = readUnits(reader, "liquidation fee balances");
        // 按资产保存的实际资金费剩余差额。
        Map<String, Long> fundingResidualBalances = readUnits(reader, "funding residual balances");
        // 按资产保存的舍入剩余差额。
        Map<String, Long> roundingResidualBalances = readUnits(reader, "rounding residual balances");
        // 按资产保存的清算盈亏财库余额。
        Map<String, Long> clearingPnlBalances = readUnits(reader, "clearing pnl balances");
        // 各币对已完成的资金费结算编号。
        Map<String, Long> fundingSettlements = readUnits(reader, "funding settlements");
        // 各币对已完成的到期或固定价格结算编号。
        Map<String, Long> lifecycleSettlements = readUnits(reader, "lifecycle settlements");
        // 按币对保存的未完成资金费进度。
        Map<String, CoreTreasuryState.FundingProgress> fundingProgress = new TreeMap<>();
        // 尚未完整结束的资金费进度条目数。
        int fundingProgressCount = reader.count("funding progress");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < fundingProgressCount; index++) {
            // 币对或合约业务编号。
            String instrumentId = reader.text();
            // 资金费或到期结算轮次编号。
            long settlementId = reader.positiveLong("funding progress settlement id");
            // 本资金费进度固定使用的费率。
            long rate = reader.longValue();
            // 未完成资金费进度中保存的本轮固定标记价。
            long mark = reader.positiveLong("funding progress mark");
            // 参考价格序号。
            long priceSequence = reader.positiveLong("funding progress price sequence");
            // 保存原命令 UUID、用户游标、固定标记价及序号的资金费续页进度。
            CoreTreasuryState.FundingProgress progress = new CoreTreasuryState.FundingProgress(
                    settlementId, rate, reader.intValue(),
                    reader.nonNegativeLong("funding progress cursor"),
                    new UUID(reader.longValue(), reader.longValue()), mark, priceSequence);
            putUnique(fundingProgress, instrumentId, progress);
        }
        // 按币对保存的未完成到期或固定价格结算进度。
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = new TreeMap<>();
        // 尚未完整结束的到期或固定价格结算进度条目数。
        int lifecycleProgressCount = reader.count("lifecycle progress");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < lifecycleProgressCount; index++) {
            // 币对或合约业务编号。
            String instrumentId = reader.text();
            // 保存撤单阶段、订单/用户游标和保险需求的生命周期结算进度。
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
        // 由七类金额表、已完成编号和续页进度恢复的原财库业务值。
        CoreTreasuryState treasuryState = new CoreTreasuryState(feeBalances, insuranceBalances,
                insuranceDeficits, liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances,
                clearingPnlBalances, fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
        // 按用户、币对和保证金模式保存的杠杆设置表。
        Map<CoreLeverageKey, Long> leverages = new TreeMap<>();
        // 历史杠杆设置条目数。
        int leverageCount = reader.count("leverages");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < leverageCount; index++) {
            // 当前杠杆设置所属用户、币对和保证金模式的业务键。
            CoreLeverageKey key = new CoreLeverageKey(reader.positiveLong("leverage userId"), reader.text(),
                    CoreMarginMode.fromWireCode(reader.intValue()));
            putUnique(leverages, key, reader.positiveLong("leveragePpm"));
        }
        // 按算法父单编号恢复的算法业务状态表。
        Map<Long, CoreAlgoOrderState> algoOrders = new TreeMap<>();
        // 嵌入算法父单记录数。
        int algoCount = reader.count("algo orders");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < algoCount; index++) {
            // 当前长度前缀声明的文本或嵌入载荷字节数。
            int length = reader.count("algo payload bytes");
            // 由冻结嵌入算法视图转换成的算法父单业务状态。
            CoreAlgoOrderState algo = CoreAlgoOrderState.from(
                    readPublishedAlgo(reader.bytes(length)));
            putUnique(algoOrders, algo.algoOrderId(), algo);
        }
        // 按用户和币对范围复合键保存的倒计时撤单恢复表。
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new TreeMap<>();
        // 倒计时撤单记录数。
        int timerCount = reader.count("cancel-all-after timers");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < timerCount; index++) {
            // 所属用户编号。
            long userId = reader.positiveLong("cancel-all-after userId");
            // 倒计时撤单的币对范围。
            String symbolScope = reader.text();
            // 倒计时撤单等待时长，毫秒。
            long countdownMillis = reader.nonNegativeLong("cancel-all-after countdown");
            // 倒计时撤单的固定协议状态。
            com.surprising.aeron.protocol.CoreCancelAllAfterStatus status =
                    com.surprising.aeron.protocol.CoreCancelAllAfterStatus.fromWireCode(reader.intValue());
            // 倒计时撤单计划触发时刻，毫秒。
            long triggerAt = reader.nonNegativeLong("cancel-all-after trigger time");
            // 普通订单或倒计时撤单记录的最近更新时间，毫秒。
            long updatedAt = reader.positiveLong("cancel-all-after updated time");
            // 已撤普通订单数量。
            int canceledOrders = reader.intValue();
            // 已撤触发单数量。
            int canceledTriggerOrders = reader.intValue();
            if (canceledOrders < 0 || canceledTriggerOrders < 0) {
                throw new ProtocolException("negative cancel-all-after result count");
            }
            // 由用户范围、倒计时和结果计数构造的撤单计时器业务值。
            CoreCancelAllAfterState timer = new CoreCancelAllAfterState(userId, symbolScope, countdownMillis,
                    status, triggerAt, updatedAt, canceledOrders, canceledTriggerOrders,
                    reader.positiveLong("cancel-all-after revision"));
            putUnique(cancelAllAfterTimers, timer.key(), timer);
        }
        // 按触发单编号保存并绑定注册币对的触发单恢复表。
        Map<Long, CoreTriggerOrderState> triggerOrders = new TreeMap<>();
        // 嵌入触发单记录数。
        int triggerCount = reader.count("trigger orders");
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < triggerCount; index++) {
            // 当前长度前缀声明的文本或嵌入载荷字节数。
            int length = reader.count("trigger payload bytes");
            // 从已发布触发格式解出的业务视图，随后按币对绑定。
            var view = readPublishedTrigger(reader.bytes(length));
            // 按历史固定布局恢复的注册币对配置。
            CoreInstrument instrument = instruments.get(view.instrumentId());
            if (instrument == null) throw new ProtocolException("trigger instrument is missing");
            // 绑定注册币对后的触发单权威业务值。
            CoreTriggerOrderState trigger = CoreTriggerOrderState.from(view, instrument);
            putUnique(triggerOrders, trigger.triggerOrderId(), trigger);
        }
        reader.requireConsumed();
        return new TradingCoreState(productLine, revision, users, orders, instruments, riskState,
                treasuryState, leverages, algoOrders, cancelAllAfterTimers, triggerOrders);
    }

    // 已发布嵌入布局固定在这里；接口 Codec 的变化不能改变历史磁盘读取顺序。
    /**
     * 独立读取已发布版本一算法单列表中的单条布局，要求两个头部整数都为一；消费历史附加文本，保留子单及执行/活动数量等视图数据，最后要求载荷全部消费，不调用当前接口算法 Codec。
     * @param encoded 带相应历史版本头的完整交易或嵌入记录字节
     */
    static CoreAlgoOrderView readPublishedAlgo(byte[] encoded) {
        // 按已发布固定顺序读取本次载荷的小端游标，不能交换字段顺序。
        Reader reader = new Reader(encoded);
        if (reader.intValue() != 1 || reader.intValue() != 1)
            throw new ProtocolException("unsupported historical algo snapshot");
        // 已发布算法父单编号。
        // 所属用户编号。
        long id = reader.longValue(), userId = reader.longValue(); /* 客户指定的算法父单编号。 */ /* 币对或合约业务编号。 */ String clientId = reader.embeddedText(256), instrumentId = reader.embeddedText(256);
        // 算法单业务类型编号。
        int type = reader.intValue(); /* 买入或卖出方向。 */ CoreOrderSide side = CoreOrderSide.fromWireCode(reader.intValue());
        // 每个算法子单的计划数量。
        // 算法父单保存的订单价格。
        // 算法父单总数量。
        long price = reader.longValue(), quantity = reader.longValue(), childQuantity = reader.longValue();
        // 算法切片间隔，秒。
        // 算法总执行时长，秒。
        long interval = reader.longValue(), duration = reader.longValue();
        // 算法父单使用的全仓或逐仓模式。
        CoreMarginMode margin = CoreMarginMode.fromWireCode(reader.intValue());
        // 算法单保存的净持仓、多仓或空仓方向。
        CorePositionSide position = CorePositionSide.fromWireCode(reader.intValue());
        // 算法父单是否只减仓。
        // 算法父单是否只挂单。
        boolean reduce = reader.booleanValue(), post = reader.booleanValue(); /* 算法子单有效方式。 */ CoreTimeInForce tif = CoreTimeInForce.fromWireCode(reader.intValue());
        // 算法父单保存的业务状态编号。
        int status = reader.intValue(); /* 算法父单当前关联的子订单编号。 */ long current = reader.longValue(); /* 算法父单保存的拒绝原因，后面的历史附加文本仍消费但不保留。 */ String reason = reader.embeddedText(256); reader.embeddedText(256);
        // 算法父单下一切片时刻，毫秒。
        // 算法父单完成时刻，毫秒。
        // 算法父单开始时刻，毫秒。
        long start = reader.longValue(), next = reader.longValue(), completed = reader.longValue();
        // 嵌入算法父单保存的业务修订号。
        // 算法父单创建时刻，毫秒。
        // 算法父单最近更新时间，毫秒。
        long created = reader.longValue(), updated = reader.longValue(), revision = reader.longValue();
        // 历史算法父单关联的子订单数量。
        int childCount = reader.count("algo children"); /* 按已发布顺序保存的算法子订单编号列表。 */ List<Long> children = new ArrayList<>(childCount);
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < childCount; index++) children.add(reader.longValue());
        // 当前嵌入记录完整恢复后的算法或触发单业务视图。
        var result = new CoreAlgoOrderView(id, userId, clientId, instrumentId, type, side, price, quantity, childQuantity,
                interval, duration, margin, position, reduce, post, tif, status, current, reason,
                start, next, completed, created, updated, revision, children, reader.longValue(), reader.longValue(), reader.intValue());
        reader.requireConsumed();
        return result;
    }

    /**
     * 独立读取已发布版本二或三触发单，保留两种费用并消费已废弃的第二段文本；版本二固定 MARK 价格源，版本三读取显式价格源，要求最终没有多余字节。
     * @param encoded 带相应历史版本头的完整交易或嵌入记录字节
     */
    static CoreTriggerOrderStateView readPublishedTrigger(byte[] encoded) {
        // 按已发布固定顺序读取本次载荷的小端游标，不能交换字段顺序。
        Reader reader = new Reader(encoded); /* 本次历史快照或嵌入记录保存的版本号。 */ int version = reader.intValue();
        if (version != 2 && version != 3) throw new ProtocolException("unsupported historical trigger snapshot");
        // 当前嵌入记录完整恢复后的算法或触发单业务视图。
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
                reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"),
                discardSecondText(reader.embeddedText(128), reader.embeddedText(128)),
                reader.nonNegativeLong("trigger value"),
                reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"), reader.nonNegativeLong("trigger value"),
                reader.longValue(), reader.longValue(),
                version < 3 ? CoreTriggerPriceSource.MARK
                        : SnapshotEnumCodes.readCoreTriggerPriceSource(reader.intValue()));
        reader.requireConsumed(); return result;
    }

    /**
     * 保留第一段文本并丢弃第二段已消费的历史附加文本，确保旧磁盘偏移仍按原布局推进。
     * @param first 应保留的历史第一段业务文本
     * @param ignoredSecond 已从字节流消费但不再保留的第二段历史文本
     */
    private static String discardSecondText(String first, String ignoredSecond) {
        return first;
    }

    /**
     * 读取一个带数量前缀的文本键到 long 表，以同一旧布局恢复金额表或已完成结算编号表；不在此强制金额非负，重复文本键拒绝。
     * @param reader 当前固定布局解析游标
     * @param name 用于数量错误消息的当前业务表名称
     */
    private static Map<String, Long> readUnits(Reader reader, String name) {
        // 当前命名业务区段的文本键到 long 值表，金额和已完成编号均按此旧布局读取。
        Map<String, Long> values = new TreeMap<>();
        // 当前金额、编号表或 Lane 列表声明的条目数。
        int count = reader.count(name);
        for (/* 当前业务区段内正在读取的零起点条目序号。 */ int index = 0; index < count; index++) {
            putUnique(values, reader.text(), reader.longValue());
        }
        return values;
    }

    /**
     * 把当前非空业务值放入恢复表，已有同键旧值时按协议错误拒绝，不返回半恢复状态。
     * @param <K> 恢复表的业务键类型，需能放入对应有序 Map
     * @param <V> 恢复表的非空业务值类型
     * @param values 当前正在构建的恢复结果表
     * @param key 当前条目的唯一业务键
     * @param value 已经解析并构造的非空业务值
     */
    private static <K, V> void putUnique(Map<K, V> values, K key, V value) {
        if (values.put(key, value) != null) {
            throw new ProtocolException("duplicate trading snapshot key: " + key);
        }
    }

    /**
     * 读取最多六十四个账户 Lane 风险进度，保留用户、阶段、持仓/预留游标及估值中间值；超过 Lane 上限沿原实现抛 IllegalArgumentException，返回列表本身未在此固定为只读。
     * @param reader 当前固定布局解析游标
     */
    private static java.util.List<RiskLaneProgress> readRiskLanes(Reader reader) {
        // 当前金额、编号表或 Lane 列表声明的条目数。
        int count = reader.count("risk Lanes");
        if (count > Long.SIZE) throw new IllegalArgumentException("too many risk Lanes");
        // 按快照顺序恢复的各账户 Lane 风险进度列表。
        var lanes = new java.util.ArrayList<RiskLaneProgress>(count);
        for (/* 当前风险 Lane 进度的列表下标。 */ int i = 0; i < count; i++) lanes.add(new RiskLaneProgress(
                reader.nonNegativeLong("Lane completed user"), reader.booleanValue(),
                reader.nonNegativeLong("Lane active user"), reader.intValue(), reader.text(),
                reader.nonNegativeLong("Lane reservation cursor"), reader.longValue(),
                reader.nonNegativeLong("Lane maintenance margin"), reader.nonNegativeLong("Lane isolated margin"),
                reader.nonNegativeLong("Lane isolated reservation"),
                reader.nonNegativeLong("Lane account revision"), reader.nonNegativeLong("Lane market revision")));
        return lanes;
    }

    /**
     * 单次历史记录读取游标，借用输入字节并维护当前位置，负责原始小端数值、前缀长度和边界校验；没有并发共享或严格 UTF-8 解码保证。
     */
    private static final class Reader {
        /** 本 Reader 借用的原始字节数组，构造时不复制。 */
        private final byte[] input;
        /** 下一次读取所在的字节偏移，只由本次解析调用推进。 */
        private int offset;

        /**
         * 借用非空输入字节，偏移从零开始；调用方在解析期间须保持字节内容稳定。
         * @param input 本次解析期间保持稳定的非空原始字节
         */
        Reader(byte[] input) {
            if (input == null) {
                throw new ProtocolException("trading snapshot is required");
            }
            this.input = input;
        }

        /**
         * 先检查剩余空间，再读一个无符号字节并推进偏移。
         */
        int byteValue() {
            require(Byte.BYTES);
            return Byte.toUnsignedInt(input[offset++]);
        }

        /**
         * 先确认四字节可读，再按低字节优先合成 int 并推进偏移。
         */
        int intValue() {
            require(Integer.BYTES);
            // 按小端字节逐步合成的三十二位整数。
            int value = 0;
            for (/* 当前小端整数拼接字节对应的位移量。 */ int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
                value |= Byte.toUnsignedInt(input[offset++]) << shift;
            }
            return value;
        }

        /**
         * 先确认八字节可读，再按低字节优先合成 long 并推进偏移。
         */
        long longValue() {
            require(Long.BYTES);
            // 按小端字节逐步合成的六十四位整数。
            long value = 0;
            for (/* 当前小端整数拼接字节对应的位移量。 */ int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                value |= (long) Byte.toUnsignedInt(input[offset++]) << shift;
            }
            return value;
        }

        /**
         * 读 long 并拒绝负值，错误中带当前业务字段名。
         * @param field 错误消息中使用的业务字段名
         */
        long nonNegativeLong(String field) {
            // 刚读出的整数，要求不小于零。
            long value = longValue();
            if (value < 0) {
                throw new ProtocolException(field + " must not be negative");
            }
            return value;
        }

        /**
         * 读 long 并要求严格正数，错误中带当前业务字段名。
         * @param field 错误消息中使用的业务字段名
         */
        long positiveLong(String field) {
            // 刚读出的整数，要求严格大于零。
            long value = longValue();
            if (value <= 0) {
                throw new ProtocolException(field + " must be positive");
            }
            return value;
        }

        /**
         * 读 int 并要求在零到整个输入字节长度之间；这只是旧格式的数量粗校验，具体文本和载荷再检查自己的长度上限。
         * @param field 错误消息中使用的业务字段名
         */
        int count(String field) {
            // 刚读出的条目数，按原规则要求不超过整个输入字节数。
            int value = intValue();
            if (value < 0 || value > input.length) {
                throw new ProtocolException("invalid " + field + " count: " + value);
            }
            return value;
        }

        /**
         * 读取长度前缀后的非空普通文本，最多六十四字节；使用普通 UTF-8 String 构造，不具备版本三十七字段读取器的严格非法字节拒绝规则。
         */
        String text() {
            // 当前长度前缀声明的文本或嵌入载荷字节数。
            int length = count("text");
            if (length == 0 || length > MAX_TEXT_BYTES) {
                throw new ProtocolException("invalid snapshot text length: " + length);
            }
            require(length);
            // 按普通 UTF-8 String 构造得到的必填文本。
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        /**
         * 读取最多六十四字节的普通文本，允许空字符串；仍按历史普通 UTF-8 String 构造规则解码。
         */
        String optionalText() {
            // 当前长度前缀声明的文本或嵌入载荷字节数。
            int length = count("optional text");
            if (length > MAX_TEXT_BYTES) {
                throw new ProtocolException("invalid optional snapshot text length: " + length);
            }
            require(length);
            // 允许空内容的历史普通文本。
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        /**
         * 按调用者指定上限读取允许为空的嵌入文本，先检查完整字节再推进偏移，沿用普通 UTF-8 String 构造。
         * @param maximumBytes 当前嵌入文本允许的最大字节数
         */
        String embeddedText(int maximumBytes) {
            // 当前长度前缀声明的文本或嵌入载荷字节数。
            int length = count("embedded text");
            if (length > maximumBytes) throw new ProtocolException("historical embedded text too long");
            require(length);
            // 按嵌入格式限定长度读取的文本。
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        /**
         * 读取非空审计文本，最多两千零四十八字节，沿用旧布局普通 UTF-8 String 构造。
         */
        String auditText() {
            // 当前长度前缀声明的文本或嵌入载荷字节数。
            int length = count("audit text");
            if (length == 0 || length > MAX_AUDIT_TEXT_BYTES) {
                throw new ProtocolException("invalid snapshot audit text length: " + length);
            }
            require(length);
            // 按审计文本长度上限读取的非空文本。
            String value = new String(input, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        /**
         * 要求嵌入载荷为一到六万五千五百三十六字节，检查边界后复制该段并推进偏移。
         * @param length 要检查或复制的后续字节数
         */
        byte[] bytes(int length) {
            if (length <= 0 || length > 65_536) {
                throw new ProtocolException("invalid snapshot payload length: " + length);
            }
            require(length);
            // 按已校验长度复制出的嵌入载荷字节。
            byte[] value = java.util.Arrays.copyOfRange(input, offset, offset + length);
            offset += length;
            return value;
        }

        /**
         * 按单字节读取历史布尔，只允许零或一；与新字段格式的八字节布尔不同。
         */
        boolean booleanValue() {
            // 刚读取的无符号单字节布尔候选，只接受零或一。
            int value = byteValue();
            if (value != 0 && value != 1) {
                throw new ProtocolException("invalid boolean value: " + value);
            }
            return value == 1;
        }

        /**
         * 要求偏移恰好到输入末尾，有任何尾部字节都拒绝该记录。
         */
        void requireConsumed() {
            if (offset != input.length) {
                throw new ProtocolException("trailing bytes in trading snapshot");
            }
        }

        /**
         * 检查请求长度非负且当前位置之后有足够字节，使用减法边界避免 offset 加长度溢出；失败时不推进偏移。
         * @param length 要检查或复制的后续字节数
         */
        private void require(int length) {
            if (length < 0 || offset > input.length - length) {
                throw new ProtocolException("truncated trading snapshot");
            }
        }
    }
}
