package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.account.AccountCommandService;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.adl.AdlService;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.funding.FundingSettlementService;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.settlement.ContractExpirySettlementService;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;


public final class PlaceOrderCheckEvent extends OrderCheckEvent implements AccountLaneCommand {
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    /** position：命令的集群日志位置，用于订单状态和后续确定性提交。 */
    private long timestamp, position;
    /** 下单用户编号，只能由其所属账户 Lane 处理。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    private UUID commandId;
    /** Owner 固定并借用的不可变账本事实，供 Lane 检查资金费及到期结算冲突。 */
    private SystemFunds.SettlementStatus settlementStatus;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int assetId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int laneId;
    private AccountBook accountBook;
    private IdIndex identities;
    /** Owner 绑定的本命令价格事实，Lane 据此完成价格决策。 */
    private com.surprising.aeron.service.market.MarkPrice decisionMark;
    private FeePolicyBook decisionPolicies;
    /** Owner 固定的决策业务时间，价格鲜度、费率及到期检查使用此值。 */
    private long decisionTimestamp;
    /** Matcher 已读取或明确取消等待的发布位，可见后 Matcher 不得继续访问事件字段。 */
    private volatile boolean matcherConsumed;

    /** Owner 先绑定意图和币对路由，再保存价格、费率表及决策时间引用。 */
    public void prepareDecision(com.surprising.aeron.protocol.PlaceOrderCommand intent,
            com.surprising.aeron.service.instrument.CoreInstrument instrument, int symbolId,
            com.surprising.aeron.service.market.MarkPrice mark, FeePolicyBook policies, long timestamp) {
        if (policies == null || timestamp <= 0) throw new IllegalArgumentException("invalid place decision input");
        initializeOrderIntent(intent, instrument, symbolId);
        decisionMark = mark;
        decisionPolicies = policies;
        decisionTimestamp = timestamp;
    }

    /** 校验已绑定路由并固定命令、用户、资产、目标 Lane 和账本事实，重置回执及消费标记，按产品规则决定是否等待 OI。 */
    public PlaceOrderCheckEvent prepare(long coreSequence, long userId, UUID commandId,
                                SystemFunds.SettlementStatus settlementStatus,
                                int assetId, int laneId,
                                AccountBook accountBook, IdIndex identities,
                                long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0
                || intent() == null || commandId == null
                || symbolId() < 0 || assetId < 0 || settlementStatus == null
                || laneId < 0 || accountBook == null) {
            throw new IllegalArgumentException("invalid place admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.commandId = commandId;
        openInterestSteps = 0;
        this.settlementStatus = settlementStatus;
        this.identities = identities;
        this.assetId = assetId;
        this.laneId = laneId;
        this.accountBook = accountBook;
        rejection = null;
        closeCapacityCancellations = List.of();
        completed = false;
        matcherConsumed = false;
        return this;
    }

    /** 只有 Lane 已完成且需要的 Matcher 回执已经消费，才清理决策、引用和标记供池化复用。 */
    @Override
    public void clear() {
        if (!complete()) throw new IllegalStateException("cannot recycle an incomplete place admission");
        if (!matcherConsumed) {
            throw new IllegalStateException("cannot recycle a place admission before matcher handoff");
        }
        clearBaseOrderCheck();
        commandId = null;
        settlementStatus = null;
        accountBook = null;
        identities = null;
        decisionMark = null;
        decisionPolicies = null;
        decisionTimestamp = 0;
        matcherConsumed = false;
    }

    /** 在目标 Lane 等账本输入就绪后，补决策、准备客户身份、计算预留和前置撤单，再写订单及冻结。可预期拒绝回退客户身份，最后记录分配计数并发布回执。 */
    public void execute(AccountPartition lane) {
        if (lane.laneId() != accountBook.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place admission reached the wrong Account Lane");
        }
        long preparedClientKey = 0;
        try {
            accountBook.enterLaneCommandScope(lane);
            try {
                boolean lifecycleSettled = settlementStatus.lifecycleSettled();
                boolean fundingInProgress = settlementStatus.fundingInProgress();
                if (!resolvedOrderInitialized())
                    OrderCheckService.resolvePrepared(
                            this, decisionMark, decisionPolicies, userId, decisionTimestamp, lifecycleSettled);
                preparedClientKey = IdIndex.clientKeyValue(
                        identities.prepareClientKeyInLane(lane, userId, clientOrderId()));
                long positionKey = identities.findPositionKeyValueInLane(
                        lane, userId, instrument(), positionSide());
                int symbolId = symbolId();
                long requiredReservation = OrderCheckService.requiredReservationPrepared(
                        accountBook, lane, userId, this, openInterestSteps,
                        preparedClientKey, positionKey,
                        lifecycleSettled, fundingInProgress);
                Position currentPosition = positionKey == 0 ? null : lane.position(positionKey);
                if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                        && currentPosition != null && currentPosition.signedQuantitySteps() != 0
                        && (currentPosition.signedQuantitySteps() > 0)
                        != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                    closeCapacityCancellations = Position.CloseCapacity.conflictsFor(
                            lane, userId, symbolId, positionKey, positionSide(), side(), orderId(), quantitySteps());
                }
                if (!accountBook.selfTradeAllowed(userId)) {
                    closeCapacityCancellations = lane.preMatchingCancellations(userId, symbolId,
                            side(), matchingPriceTicks(), orderId(), orderId(), closeCapacityCancellations);
                }
                accountBook.placeOrderInLane(lane, userId, this, commandId,
                        requiredReservation, preparedClientKey, symbolId, assetId, coreSequence, null, timestamp, position);
            } finally {
                accountBook.exitLaneCommandScope(lane);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            identities.rollbackClientKeyInLane(lane, userId, clientOrderId(), preparedClientKey);
            rejection = failure;
        }
        AccountBook completionRuntime = accountBook;
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishPlaceOrderCheckReady(laneId, coreSequence);
    }

    /** 清理从未正式交给账户 Lane 的已准备事件。Lane 已完成时必须走对应交接路径，不能直接丢弃。 */
    public void discard() {
        if (complete()) throw new IllegalStateException("completed place admission must be collected");
        clearBaseOrderCheck();
        commandId = null;
        settlementStatus = null;
        accountBook = null;
        identities = null;
        decisionMark = null;
        decisionPolicies = null;
        decisionTimestamp = 0;
        coreSequence = 0;
        userId = 0;
        laneId = 0;
        matcherConsumed = false;
    }

    /** Matcher 最多等待三十秒取得 Lane 回执，区分成功、业务拒绝、溢出及非法参数并填入原结算事件。 */
    public void awaitMatcherReceipt(TradeSettlement target) {
        if (target == null) {
            throw new IllegalArgumentException("place admission target is required");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!completed) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for place admission sequence="
                        + coreSequence + " lane=" + laneId);
            }
            Thread.onSpinWait();
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("place admission wait was interrupted");
            }
        }
        CoreResultCode resultCode = rejection == null ? CoreResultCode.NONE
                : rejection instanceof CoreStateRejectedException stateRejected
                ? CoreResultCode.fromRejectionCode(stateRejected.code())
                : rejection instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW
                : CoreResultCode.INVALID_COMMAND;
        target.orderCheckReceipt(orderId(), rejection == null, resultCode.wireCode());
        if (rejection == null) target.addOrderCheckCancellations(closeCapacityCancellations);
        // Owner 可能立即清空池化事件。
        // 消费位可见后 Matcher 只使用已捕获引用，不再读取事件字段。
        // 消费位发布后，Owner 可以回收本事件，不再访问事件字段。
        matcherConsumed = true;
    }

    /** 读取实际消费发布位。 */
    public boolean matcherConsumed() { return matcherConsumed; }

    /** 已完成的准入不再需要进入 Matcher 等待路径时，显式发布消费完成。 */
    public void cancelMatcherWait() {
        if (!complete() || matcherConsumed) {
            throw new IllegalStateException("invalid matcher admission cancellation");
        }
        matcherConsumed = true;
    }

    /** 只有完整决策已完成且准入成功才交出本对象作为订单输入，不能把半成品或拒单当获准订单。 */
    public ResolvedPlaceOrder resolvedOrder() {
        if (!complete() || !resolvedOrderInitialized() || rejection != null) {
            throw new IllegalStateException("place admission is not accepted");
        }
        return this;
    }

    /** 返回已绑定的路由输入，供 Owner 预构建结算或 Matcher 借用。读取 Lane 补齐的价格和费率前仍必须等准入回执。 */
    public ResolvedPlaceOrder matchingOrder() {
        if (intent() == null) throw new IllegalStateException("place admission is not prepared");
        return this;
    }
}

