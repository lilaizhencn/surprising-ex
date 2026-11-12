package com.surprising.aeron.service.order;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.product.api.ProductLine;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 一个账户 Lane 持有的订单状态。Lane 可原位推进成交和终态，跨线程读取使用固定命令槽或快照。Owner 消费命令回执做有序发布，不能保留活跃权威订单当只读镜像。构造约定：只为原对象池和命令槽工厂分配空存储，调用方随后必须完整初始化再作为订单使用。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Order {
    @Accessors(fluent = true, chain = false)
    @Getter
    private long orderId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private ProductLine productLine;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int symbolId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreInstrument instrument;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreOrderSide side;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long matchingPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long quantitySteps;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long executedQuantitySteps;
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean reduceOnly;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreMarginMode marginMode;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CorePositionSide positionSide;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreOrderType orderType;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreTimeInForce timeInForce;
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean postOnly;
    @Accessors(fluent = true, chain = false)
    @Getter
    private String clientOrderId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private UUID commandId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long makerFeeRatePpm;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long takerFeeRatePpm;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long cumulativeFeeUnits;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long executedValueHigh;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long executedValueLow;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long createdAtEpochMillis;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long updatedAtEpochMillis;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long clusterPosition;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreOrderStatus status;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long revision;
    private boolean mutable;

    /** 按完整订单元数据创建可变值但未显式提供累计成交值。 */
    public Order(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side,
                        long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                        CorePositionSide positionSide, CoreOrderType orderType,
                        CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId,
                        UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                        long cumulativeFeeUnits, long createdAtEpochMillis, long updatedAtEpochMillis,
                        long clusterPosition, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbolId, instrument, side, matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedQuantitySteps == 0 ? 0 : -1, 0, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition, status, revision);
    }

    /** 创建带累计费用、成交值和完整提交元数据的可变订单，核对身份、数量守恒、时间关系和基本订单类型约束。 */
    public Order(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side,
                        long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                        CorePositionSide positionSide, CoreOrderType orderType,
                        CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId,
                        UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                        long cumulativeFeeUnits, long executedValueHigh, long executedValueLow, long createdAtEpochMillis, long updatedAtEpochMillis,
                        long clusterPosition, CoreOrderStatus status, long revision) {
        if (orderId <= 0 || productLine == null || userId <= 0 || symbolId < 0 || instrument == null
                || side == null || matchingPriceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps
                || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64 || commandId == null
                || createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis || clusterPosition < 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)
                || status == null || revision <= 0
                || status == CoreOrderStatus.OPEN && remainingQuantitySteps == 0) {
            throw new IllegalArgumentException("invalid runtime order");
        }
        this.orderId = orderId;
        this.productLine = productLine;
        this.userId = userId;
        this.symbolId = symbolId;
        this.instrument = instrument;
        this.side = side;
        this.matchingPriceTicks = matchingPriceTicks;
        this.quantitySteps = quantitySteps;
        this.executedQuantitySteps = executedQuantitySteps;
        this.reduceOnly = reduceOnly;
        this.marginMode = marginMode;
        this.positionSide = positionSide;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
        this.postOnly = postOnly;
        this.clientOrderId = clientOrderId;
        this.commandId = commandId;
        this.makerFeeRatePpm = makerFeeRatePpm;
        this.takerFeeRatePpm = takerFeeRatePpm;
        this.cumulativeFeeUnits = cumulativeFeeUnits;
        this.executedValueHigh = executedValueHigh;
        this.executedValueLow = executedValueLow;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
        this.clusterPosition = clusterPosition;
        this.status = status;
        this.revision = revision;
        this.mutable = true;
    }

    private Order(long orderId, ProductLine productLine, long userId, int symbolId,
                         CoreInstrument instrument, CoreOrderSide side,
                         long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                         long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                         CorePositionSide positionSide, CoreOrderType orderType,
                         CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId,
                         UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                         long cumulativeFeeUnits, long executedValueHigh, long executedValueLow, long createdAtEpochMillis, long updatedAtEpochMillis,
                         long clusterPosition, CoreOrderStatus status, long revision, boolean mutable) {
        this(orderId, productLine, userId, symbolId, instrument, side,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId,
                commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, status, revision);
        this.mutable = mutable;
    }

    /** 简化构造入口默认采用 U 本位永续产品线，再按给定成交量和取消标记创建订单。 */
    public Order(long orderId, long userId, int symbolId, CoreInstrument instrument,
                        CoreOrderSide side, long priceTicks, boolean reduceOnly,
                        CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce,
                        long makerFeeRatePpm, long takerFeeRatePpm, long quantitySteps,
                        long executedQuantitySteps, long remainingQuantitySteps, boolean canceled) {
        this(orderId, ProductLine.LINEAR_PERPETUAL, userId, symbolId, instrument, side,
                priceTicks, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                makerFeeRatePpm, takerFeeRatePpm, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps, canceled);
    }

    /** 按指定产品线创建简化订单，客户端标识为空、命令 UUID 由订单编号构造、提交时间位置为零，取消标记决定 CANCELED 或 OPEN。 */
    public Order(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce, long makerFeeRatePpm,
                        long takerFeeRatePpm, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean canceled) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode,
                positionSide, orderType, timeInForce, false, "", new UUID(0, orderId),
                makerFeeRatePpm, takerFeeRatePpm, 0, 0, 0, 0,
                canceled ? CoreOrderStatus.CANCELED : CoreOrderStatus.OPEN, 1);
    }

    /** 原请求价：衍生品市价为0，现货保护边界和限价由实际撮合价提供。 */
    public long priceTicks() {
        return productLine != ProductLine.SPOT && orderType == CoreOrderType.MARKET ? 0 : matchingPriceTicks;
    }

    /** 返回总量减累计成交量的历史未成交数量，撤销后仍保留。 */
    public long remainingQuantitySteps() {
        return Math.subtractExact(quantitySteps, executedQuantitySteps);
    }

    /** 名称沿用 canceled，但实际返回 status.terminal：已成交、已拒绝或其他终态也会返回 true。 */
    public boolean canceled() { return status.terminal(); }

    /** 复制当前全部业务值并标记为只读交接用途，供明确的发布、快照或回滚边界使用，不随原 Lane 后续成交改变。 */
    public Order snapshot() {
        return new Order(orderId, productLine, userId, symbolId, instrument, side,
                matchingPriceTicks, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps(), reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                status, revision, false);
    }

    /** 复制业务值到调用方持有的固定命令槽并标为非可变。目标只能是命令槽，必须等原消费者和响应退休后才能覆盖。 */
    public Order copyIntoCommandSlot(Order target) {
        if (target == null) {
            target = new Order();
            target.commandSlotValue = true;
        }
        if (!target.commandSlotValue) throw new IllegalArgumentException("order copy target is not a command slot");
        copyStateTo(target);
        target.mutable = false;
        return target;
    }

    /** 要求 Matcher 读取的是非可变交接值，用户和订单身份正确且仍有正剩余量及撮合价格，不能把活动 Lane 权威对象直接交给 Matcher。 */
    public void requireFrozenMatcherInput(long userId) {
        if (mutable || this.userId != userId || orderId <= 0 || instrument == null
                || remainingQuantitySteps() <= 0 || matchingPriceTicks <= 0)
            throw new IllegalArgumentException("matcher requires a frozen order with remaining quantity for this user");
    }

    /** 将同订单编号的普通冻结前值恢复进原可变 Lane 对象，禁止用命令槽作为恢复目标或前值。保留目标原有池归属及常驻冻结租约。 */
    public void restoreCapturedValue(Order before) {
        if (!mutable || commandSlotValue || before == null || before.mutable || before.commandSlotValue
                || orderId != before.orderId)
            throw new IllegalStateException("invalid authoritative order restoration");
        before.copyStateTo(this);
    }

    private void copyStateTo(Order target) {
        target.orderId = orderId;
        target.productLine = productLine;
        target.userId = userId;
        target.symbolId = symbolId;
        target.instrument = instrument;
        target.side = side;
        target.matchingPriceTicks = matchingPriceTicks;
        target.quantitySteps = quantitySteps;
        target.reduceOnly = reduceOnly;
        target.marginMode = marginMode;
        target.positionSide = positionSide;
        target.orderType = orderType;
        target.timeInForce = timeInForce;
        target.postOnly = postOnly;
        target.clientOrderId = clientOrderId;
        target.commandId = commandId;
        target.makerFeeRatePpm = makerFeeRatePpm;
        target.takerFeeRatePpm = takerFeeRatePpm;
        target.executedQuantitySteps = executedQuantitySteps;
        target.cumulativeFeeUnits = cumulativeFeeUnits;
        target.executedValueHigh = executedValueHigh;
        target.executedValueLow = executedValueLow;
        target.createdAtEpochMillis = createdAtEpochMillis;
        target.updatedAtEpochMillis = updatedAtEpochMillis;
        target.clusterPosition = clusterPosition;
        target.status = status;
        target.revision = revision;
        target.cachedPositionKey = cachedPositionKey;
    }

    /** 只清理命令槽的请求引用和关键身份，存储保留给下一条命令。不能拿此入口清理权威订单。 */
    public void clearCommandSlot() {
        if (!commandSlotValue) throw new IllegalStateException("cannot clear an authoritative order as a command slot");
        productLine = null; instrument = null; side = null; marginMode = null; positionSide = null;
        orderType = null; timeInForce = null; clientOrderId = null; commandId = null; status = null;
        orderId = userId = revision = cachedPositionKey = 0;
    }

    /** 是否为命令固定槽，生命周期跟随原响应及命令退休，不能当 Lane 权威槽清理。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean commandSlotValue;

    private boolean pooledAuthority;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long retiredAfterSequence;
    private long cachedPositionKey;
    private OrderFundsReservation reservationSlot;
    /** 本次权威订单是否已建立冻结，防止同一订单重复准备常驻槽。 */
    private boolean reservationPrepared;

    /** 懒计算用户、永久币对及持仓腿的派生键，并缓存到当前订单，不分配新的权威持仓身份。 */
    public long positionIdentityKey() {
        long key = cachedPositionKey;
        if (key == 0) {
            key = IdIndex.positionIdentityKey(userId, instrument.instrumentId(), positionSide);
            cachedPositionKey = key;
        }
        return key;
    }

    /** 首次创建或复用原命令槽，写入获准订单的身份、参数和初始状态，再标为只读撮合输入。 */
    public static Order prepareCommandOrder(Order target, ProductLine product, long userId,
            OrderCommand.ResolvedPlaceOrder intent, UUID commandId, int symbolId, long timestamp, long position) {
        if (target == null) { target = new Order(); target.commandSlotValue = true; }
        if (!target.commandSlotValue) throw new IllegalStateException("order is not a command slot");
        initializeOrderCheck(target, product, userId, intent, commandId, symbolId, timestamp, position);
        target.mutable = false;
        return target;
    }

    /** 首次创建或复用 Lane 权威池对象，要求目标类型正确并初始化本单。 */
    public static Order preparePooled(Order target, ProductLine product, long userId,
            OrderCommand.ResolvedPlaceOrder intent, UUID commandId, int symbolId, long timestamp, long position) {
        if (intent == null || commandId == null || userId <= 0 || symbolId < 0 || timestamp < 0 || position < 0)
            throw new IllegalArgumentException("invalid pooled order admission");
        if (target == null) { target = new Order(); target.pooledAuthority = true; }
        if (!target.pooledAuthority || target.commandSlotValue) throw new IllegalStateException("order is not a Lane pool value");
        initializeOrderCheck(target, product, userId, intent, commandId, symbolId, timestamp, position);
        return target;
    }

    private static void initializeOrderCheck(Order target, ProductLine product, long userId,
            OrderCommand.ResolvedPlaceOrder intent, UUID commandId, int symbolId, long timestamp, long position) {
        if (intent == null || commandId == null || product == null || userId <= 0 || symbolId < 0 || timestamp < 0 || position < 0)
            throw new IllegalArgumentException("invalid order admission");
        target.orderId = intent.orderId(); target.productLine = product; target.userId = userId;
        target.symbolId = symbolId; target.instrument = intent.instrument(); target.side = intent.side();
        target.matchingPriceTicks = intent.matchingPriceTicks();
        target.quantitySteps = intent.quantitySteps();
        target.executedQuantitySteps = target.cumulativeFeeUnits = target.executedValueHigh = target.executedValueLow = 0;
        target.reduceOnly = intent.reduceOnly(); target.marginMode = intent.marginMode(); target.positionSide = intent.positionSide();
        target.orderType = intent.orderType(); target.timeInForce = intent.timeInForce(); target.postOnly = intent.postOnly();
        target.clientOrderId = intent.clientOrderId(); target.commandId = commandId;
        target.makerFeeRatePpm = intent.makerFeeRatePpm(); target.takerFeeRatePpm = intent.takerFeeRatePpm();
        target.createdAtEpochMillis = target.updatedAtEpochMillis = timestamp; target.clusterPosition = position;
        target.status = CoreOrderStatus.OPEN; target.revision = 1; target.mutable = true; target.retiredAfterSequence = 0;
        target.cachedPositionKey = 0;
        target.reservationPrepared = false;
    }

    /** 普通订单创建独立冻结。池化权威订单复用自己的常驻冻结槽，同一单只能准备一次，已登记退休的订单不能再准备。 */
    public OrderFundsReservation prepareReservation(com.surprising.aeron.protocol.ReservationKind kind,
                                           int assetId, long reservedUnits) {
        if (!pooledAuthority) return new OrderFundsReservation(orderId, userId, symbolId, kind,
                assetId, reservedUnits, 0, 0, quantitySteps);
        if (retiredAfterSequence != 0 || reservationPrepared)
            throw new IllegalStateException("reservation is already prepared or its order is retired");
        reservationSlot = OrderFundsReservation.prepareForOrderSlot(reservationSlot, orderId, userId,
                symbolId, kind, assetId, reservedUnits, quantitySteps);
        reservationPrepared = true;
        return reservationSlot;
    }

    /** 非池对象返回 false，权威池对象登记一次正退休序号。 */
    public boolean retireForReuse(long sequence) {
        if (!pooledAuthority) return false;
        if (sequence <= 0 || retiredAfterSequence != 0) throw new IllegalStateException("invalid order retirement");
        retiredAfterSequence = sequence;
        return true;
    }

    /** 要求权威池对象已经登记退休，释放客户标识、命令和币对等引用并清派生键。 */
    public void clearRetiredReferences() {
        if (!pooledAuthority || retiredAfterSequence == 0) throw new IllegalStateException("order was not retired");
        clientOrderId = null; commandId = null; instrument = null; productLine = null;
        cachedPositionKey = 0;
    }

    /** 已是可变值则直接返回，否则复制成普通可变 Lane 值，避免把发布对象直接当活动状态接管。 */
    public Order laneValue() { return mutable ? this : mutableCopy(); }

    private Order mutableCopy() {
        return new Order(orderId, productLine, userId, symbolId, instrument, side,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps(),
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition, status, revision);
    }

    /** 活动可变值先捕获快照，已有非可变交接值直接返回，沿用原发布租约。 */
    public Order publicationValue() { return mutable ? snapshot() : this; }

    /** 由所属 Lane 校验数量及基本提交参数后写入成交和剩余量，再精确累加手续费并推进状态版本。非负提交时间会同时补齐创建时间和更新日志位置，异常由原命令回滚边界处理。 */
    public void applyFillInPlace(long executed, long remaining, long feeUnits,
                          CoreOrderStatus nextStatus, long nextRevision,
                          long commitTimestamp, long commitPosition) {
        if (executed < 0 || remaining < 0 || Math.addExact(executed, remaining) != quantitySteps
                || nextStatus == null || nextRevision <= 0 || commitTimestamp < -1 || commitPosition < -1
                || commitTimestamp >= 0 && commitPosition < 0) {
            throw new IllegalArgumentException("invalid runtime order execution");
        }
        executedQuantitySteps = executed;
        cumulativeFeeUnits = Math.addExact(cumulativeFeeUnits, feeUnits);
        if (commitTimestamp >= 0) {
            if (createdAtEpochMillis == 0) createdAtEpochMillis = commitTimestamp;
            updatedAtEpochMillis = commitTimestamp;
            clusterPosition = commitPosition;
        }
        status = nextStatus;
        revision = nextRevision;
    }

    /** 保留成交数量和费用，直接推进状态、版本及可选提交元数据。 */
    public void applyStatusInPlace(CoreOrderStatus nextStatus, long nextRevision,
                            long commitTimestamp, long commitPosition) {
        if (nextStatus == null || nextRevision <= 0 || commitTimestamp < -1 || commitPosition < -1
                || commitTimestamp >= 0 && commitPosition < 0) {
            throw new IllegalArgumentException("invalid runtime order execution");
        }
        if (commitTimestamp >= 0) {
            if (createdAtEpochMillis == 0) createdAtEpochMillis = commitTimestamp;
            updatedAtEpochMillis = commitTimestamp;
            clusterPosition = commitPosition;
        }
        status = nextStatus;
        revision = nextRevision;
    }

    /** 原位写入非负集群时间和日志位置，创建时间原为 0 时只补齐一次。 */
    public void applyCommitMetadataInPlace(long timestamp, long position) {
        if (timestamp < 0 || position < 0) throw new IllegalArgumentException("invalid runtime order metadata");
        if (createdAtEpochMillis == 0) createdAtEpochMillis = timestamp;
        updatedAtEpochMillis = timestamp;
        clusterPosition = position;
    }

    /** 原位写入累计成交值的高低位，高位小于 -1 时拒绝，保留 -1 的未提供哨兵。 */
    public void executionValue(long high, long low) {
        if (high < -1) throw new IllegalArgumentException("invalid execution value");
        executedValueHigh = high;
        executedValueLow = low;
    }

    /** 保留数量和费用，在同一新值中更新状态版本及提交时间位置。 */
    public Order withStatus(CoreOrderStatus nextStatus, long nextRevision,
                            long commitTimestamp, long commitPosition) {
        return new Order(orderId, productLine, userId, symbolId, instrument, side,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps(), reduceOnly,
                marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedValueHigh, executedValueLow,
                createdAtEpochMillis == 0 && commitTimestamp >= 0 ? commitTimestamp : createdAtEpochMillis,
                commitTimestamp < 0 ? updatedAtEpochMillis : commitTimestamp,
                commitTimestamp < 0 ? clusterPosition : commitPosition, nextStatus, nextRevision);
    }

    /** 创建提交时间位置已更新的后状态，原创建时间非零时保持，费用和累计成交值原样保留。 */
    public Order withCommitMetadata(long timestamp, long position) {
        return new Order(orderId, productLine, userId, symbolId, instrument, side,
                matchingPriceTicks, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps(), reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis == 0 ? timestamp : createdAtEpochMillis,
                timestamp, position, status, revision);
    }

    /** 比较全部订单业务字段和规范币对对象身份，不把池归属、可变标记、派生缓存或常驻冻结槽作为业务差异。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Order value)) return false;
        return orderId == value.orderId && userId == value.userId && symbolId == value.symbolId
                && instrument == value.instrument
                && matchingPriceTicks == value.matchingPriceTicks && quantitySteps == value.quantitySteps
                && executedQuantitySteps == value.executedQuantitySteps
                && reduceOnly == value.reduceOnly
                && postOnly == value.postOnly && makerFeeRatePpm == value.makerFeeRatePpm
                && takerFeeRatePpm == value.takerFeeRatePpm && cumulativeFeeUnits == value.cumulativeFeeUnits
                && executedValueHigh == value.executedValueHigh && executedValueLow == value.executedValueLow
                && createdAtEpochMillis == value.createdAtEpochMillis && updatedAtEpochMillis == value.updatedAtEpochMillis
                && clusterPosition == value.clusterPosition && revision == value.revision
                && productLine == value.productLine && side == value.side && marginMode == value.marginMode
                && positionSide == value.positionSide && orderType == value.orderType && timeInForce == value.timeInForce
                && status == value.status && Objects.equals(clientOrderId, value.clientOrderId)
                && Objects.equals(commandId, value.commandId);
    }

    /** 按与 equals 一致的业务字段和币对对象身份生成哈希值。 */
    @Override
    public int hashCode() {
        return Objects.hash(orderId, productLine, userId, symbolId, System.identityHashCode(instrument), side, priceTicks(),
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps(),
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId,
                commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, status, revision);
    }

    /** 输出订单身份、产品、币对、价格、数量和状态版本，供诊断和测试断言使用。 */
    @Override
    public String toString() {
        return "Order[orderId=" + orderId + ", productLine=" + productLine + ", userId=" + userId
                + ", symbolId=" + symbolId + ", instrument=" + instrument + ", side=" + side
                + ", priceTicks=" + priceTicks() + ", matchingPriceTicks=" + matchingPriceTicks
                + ", quantitySteps=" + quantitySteps + ", executedQuantitySteps=" + executedQuantitySteps
                + ", remainingQuantitySteps=" + remainingQuantitySteps() + ", status=" + status
                + ", revision=" + revision + "]";
    }
}
