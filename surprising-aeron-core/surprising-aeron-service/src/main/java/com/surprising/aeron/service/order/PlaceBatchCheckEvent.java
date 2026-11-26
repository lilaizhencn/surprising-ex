package com.surprising.aeron.service.order;

import com.surprising.aeron.service.settlement.TradeSettlementChanges;
import com.surprising.aeron.service.position.Position;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.matcher.OrderCancelResult;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.result.OrderSource;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;


public final class PlaceBatchCheckEvent implements AccountLaneCommand {
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    private long timestamp, position;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    private UUID commandId;
    /** Owner 交接的稳定批次输入及原命令槽入口，Lane 完成前不得复用。 */
    private PendingOrderBatch source;
    private ResolvedPlaceOrder[] orders;
    private long[] clientKeys;
    /** 原批次槽保存的逐项获准订单租约数组，后续撮合继续使用。借用本批获准订单租约数组，调用方须遵守原批次生命周期，只使用有效项前缀。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private Order[] admittedOrders;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int itemCount;
    private int admittedCount;
    /** 本批唯一账户 Lane 编号，必须与用户拓扑一致。 */
    private int laneId;
    private int matcherShard;
    private AccountBook accountBook;
    private TradeSettlementChanges changes;
    private IdIndex identities;
    private UserAccount userBefore;
    private UserAccount userBeforeSlot;
    private RuntimeException rejection;
    /** Lane 写完结果和拒绝信息后发布的完成位，读取结果及回收前必须观察它。 */
    private volatile boolean completed;

    /** 校验有效项数、借用数组、命令和拓扑范围，绑定本批稳定输入及原变更缓冲，重置成功前缀和结果标记。 */
    public PlaceBatchCheckEvent prepare(
            long coreSequence, long userId, UUID commandId, ResolvedPlaceOrder[] orders,
            long[] clientKeys,
            Order[] admittedOrders,
            int itemCount, int laneId, int matcherShard,
            AccountBook accountBook, TradeSettlementChanges changes, IdIndex identities, PendingOrderBatch source, long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0 || commandId == null || orders == null
                || source == null || identities == null || clientKeys == null || admittedOrders == null || itemCount <= 0
                || itemCount > orders.length || itemCount > clientKeys.length
                || itemCount > admittedOrders.length
                || laneId < 0 || matcherShard < 0
                || accountBook == null || matcherShard >= accountBook.topology().matchingEngineCount() || changes == null) {
            throw new IllegalArgumentException("invalid place batch admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.commandId = commandId;
        this.orders = orders;
        this.source = source;
        this.clientKeys = clientKeys;
        this.admittedOrders = admittedOrders;
        this.itemCount = itemCount;
        this.laneId = laneId;
        this.matcherShard = matcherShard;
        this.accountBook = accountBook;
        this.changes = changes;
        this.identities = identities;
        admittedCount = 0;
        rejection = null;
        completed = false;
        return this;
    }

    /** 在唯一所属 Lane 捕获用户前值，按项解析决策、准备身份、检查风险及容量并冻结。 */
    public void execute(AccountPartition lane) {
        if (lane.laneId() != laneId || laneId != accountBook.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place batch admission reached the wrong Account Lane");
        }
        UserAccount currentUser = lane.user(userId);
        userBefore = currentUser == null ? null
                : (userBeforeSlot = currentUser.copyIntoCommandSlot(userBeforeSlot));
        try {
            accountBook.enterMatcherSettlementScope(lane, changes);
            lane.orderCheckIndexCapacity(Math.max(2, itemCount));
            try {
                for (int index = 0; index < itemCount; index++) {
                    var decision = source.decision(index);
                    ResolvedPlaceOrder order = OrderCheckService.resolveInto(
                            source.resolvedOrderSlot(index), decision.context(), userId, source.intent(index));
                    orders[index] = order;
                    int symbolId = order.symbolId();
                    int assetId = accountBook.productLine().isDerivative() ? decision.settleAssetId()
                            : order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                            ? decision.quoteAssetId() : decision.baseAssetId();
                    long key = IdIndex.clientKeyValue(
                            identities.prepareClientKeyInLane(lane, userId, order.clientOrderId()));
                    clientKeys[index] = key;
                    long positionKey = identities.findPositionKeyValueInLane(
                            lane, userId, order.instrument(), order.positionSide());
                    if (accountBook.productLine().isDerivative() && positionKey != 0) {
                        var position = lane.position(positionKey);
                        if (position != null && position.signedQuantitySteps() != 0
                                && (position.signedQuantitySteps() > 0) != (order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                            var summary = lane.orderCheckSummary(symbolId, userId,
                                    order.positionSide(), order.side(), order.marginMode());
                            if (summary.reduceOnlyQuantity() > 0 && Math.addExact(Math.addExact(summary.pendingQuantity(), summary.reduceOnlyQuantity()), order.quantitySteps())
                                    > Math.absExact(position.signedQuantitySteps()))
                                throw new CoreStateRejectedException("CLOSE_CAPACITY_REQUIRES_ORDERED_ADMISSION", "close commitments require ordered cancellation");
                        }
                    }
                    long requiredReservation = OrderCheckService.requiredReservationPrepared(
                            accountBook, lane, userId, order, decision.openInterestSteps(),
                            key, positionKey,
                            decision.context().lifecycleSettled(), decision.context().fundingInProgress());
                    accountBook.placeOrderInLane(lane, userId, order, commandId,
                            requiredReservation, clientKeys[index], symbolId, assetId,
                            coreSequence, null, timestamp, position);
                    Order live = lane.order(order.orderId());
                    admittedOrders[index] = source.captureAdmittedOrder(index, live);
                    admittedCount++;
                }
                changes.prepareOrderCheckLane(laneId);
            } finally {
                lane.orderCheckIndexCapacity(2);
                accountBook.exitMatcherSettlementScope(lane, changes);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            accountBook.rollbackPlaceBatchOrderCheckInLane(
                    lane, userId, coreSequence, orders, clientKeys, admittedCount, userBefore);
            for (int i = 0; i < itemCount; i++) {
                if (clientKeys[i] != 0 && orders[i] != null && orders[i].hasIntent()) {
                    identities.rollbackClientKeyInLane(lane, userId, orders[i].clientOrderId(), clientKeys[i]);
                }
                clientKeys[i] = 0;
            }
            admittedCount = 0;
            rejection = failure;
        }
        AccountBook completionRuntime = accountBook;
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishAccountOrderCheckReadyShard(matcherShard);
    }

    /** 要求 Lane 已完成且变更缓冲已交出，清理借用数组和结果引用，清空但保留用户前值槽以便后续复用。 */
    public void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete place batch admission");
        }
        source = null;
        orders = null;
        clientKeys = null;
        admittedOrders = null;
        userBefore = null;
        if (userBeforeSlot != null) userBeforeSlot.clearCommandSlot();
        accountBook = null;
        identities = null;
        rejection = null;
        itemCount = 0;
        admittedCount = 0;
        matcherShard = 0;
    }

    /** 返回 Lane 是否发布了本批准入完成，成功和已回退的业务拒绝都可完成。 */
    public boolean complete() { return completed; }
    /** 完成后读取本批原始拒绝异常，成功时为 null。 */
    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return rejection;
    }
    /** 完成后读取供本批回退的用户前值，原来没有用户时为 null。 */
    public UserAccount userBefore() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return userBefore;
    }
    /** 按有效项下标读取该项获准订单租约，不新建订单副本。 */
    Order admittedOrder(int index) { return admittedOrders[index]; }
    /** 按有效项下标读取 Lane 已解析的订单决策输入，引用仍受原批次生命周期约束。 */
    ResolvedPlaceOrder order(int index) { return orders[index]; }
    /** 完成后一次性交出原结算变更缓冲并清空事件引用，第二次获取或提前读取都报错。 */
    public TradeSettlementChanges takeChanges() {
        if (!complete() || changes == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        TradeSettlementChanges value = changes;
        changes = null;
        return value;
    }

    /** 在事件完成且仍持有变更缓冲时，把本批余额前值交给指定原有补丁缓冲，供后续正确回退使用。 */
    public void copyBalanceBeforeTo(AccountTransactionRollback.BalancePatches target) {
        if (!complete() || changes == null || target == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        changes.copyBalanceBeforeTo(target);
    }
    /** 取出并清空当前变更缓冲，同时设置完成位供清理。 */
    public TradeSettlementChanges discardChanges() {
        TradeSettlementChanges value = changes;
        changes = null;
        completed = true;
        return value;
    }

}

