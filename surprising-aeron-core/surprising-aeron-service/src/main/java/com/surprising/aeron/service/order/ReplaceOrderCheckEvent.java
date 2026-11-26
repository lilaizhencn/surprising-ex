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


public final class ReplaceOrderCheckEvent extends OrderCheckEvent implements AccountLaneCommand {
    private long sequence, userId, originalOrderId;
    /** Owner 固定的 Matcher 分片，完成通知按此唤醒原撮合续步。 */
    private int matcherShard;
    private AmendOrderCommand amend;
    private ReplaceOrderCommand replace;
    /** Owner 固定的币对决策输入，Lane 读取后写入本事件继承的解析字段。 */
    private OrderCheckService.Context decision;
    private AccountBook accountBook;
    private IdIndex identities;
    private ReplaceOrderCheck orderCheck;

    /** 要求改单和替换意图恰好提供一种，绑定原订单、用户、配置和 Matcher 分片，重置结果并按产品判断是否等待账本 OI。 */
    public ReplaceOrderCheckEvent prepare(long sequence, long userId, long originalOrderId,
            AmendOrderCommand amend, ReplaceOrderCommand replace,
            OrderCheckService.Context decision, int matcherShard,
            AccountBook accountBook, IdIndex identities) {
        if (sequence <= 0 || userId <= 0 || originalOrderId <= 0 || (amend == null) == (replace == null)
                || decision == null || accountBook == null || identities == null
                || matcherShard < 0 || matcherShard >= accountBook.topology().matchingEngineCount())
            throw new IllegalArgumentException("invalid replacement admission");
        this.sequence = sequence; this.userId = userId; this.originalOrderId = originalOrderId;
        this.amend = amend; this.replace = replace; this.decision = decision;
        this.matcherShard = matcherShard; openInterestSteps = 0; this.accountBook = accountBook; this.identities = identities;
        orderCheck = null; rejection = null; completed = false; closeCapacityCancellations = List.of();
        return this;
    }

    /** 在原单所属 Lane 核对原单存在、用户、OPEN 状态及可改单类型，解析新意图并排除原单占用计算预留。 */
    @Override public void execute(AccountPartition lane) {
        AccountBook completionRuntime = accountBook;
        int completionShard = matcherShard;
        int completionLane = lane.laneId();
        if (completionLane != accountBook.topology().accountLaneId(userId))
            throw new IllegalStateException("replacement admission crossed Account Lane");
        accountBook.enterLaneCommandScope(lane);
        try {
            Order original = lane.order(originalOrderId);
            if (original == null) throw rejected("ORDER_NOT_FOUND", "order does not exist");
            if (original.userId() != userId) throw rejected("ORDER_OWNER_MISMATCH", "order belongs to another user");
            if (original.status() != CoreOrderStatus.OPEN) throw rejected("INVALID_COMMAND", "order is not replaceable");
            if (amend != null && original.orderType() != CoreOrderType.LIMIT)
                throw rejected("INVALID_COMMAND", "order is not amendable");
            PlaceOrderCommand command = replace == null ? amendCommand(original) : replace.replacement();
            OrderCheckService.resolveInto(this, decision, userId, command);
            long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
            long required = OrderCheckService.requiredReplacementReservationPrepared(
                    accountBook, lane, userId, this, openInterestSteps,
                    original, identities.findClientKeyValueInLane(lane, userId, clientOrderId()), positionKey,
                    decision.lifecycleSettled(), decision.fundingInProgress());
            if (amend != null && accountBook.productLine() == com.surprising.product.api.ProductLine.SPOT) {
                var reservation = lane.reservation(originalOrderId);
                if (reservation == null || !reservationAsset().equals(identities.asset(reservation.assetId())))
                    throw new IllegalStateException("spot amend original reservation is missing or mismatched");
                var balance = accountBook.balance(userId, reservation.assetId());
                if (balance == null || required > Math.addExact(balance.availableUnits(), reservation.reservedUnits()))
                    throw rejected("INSUFFICIENT_AVAILABLE_BALANCE", "available balance is insufficient for amended order");
            }
            Position position = positionKey == 0 ? null : lane.position(positionKey);
            if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                    && position != null && position.signedQuantitySteps() != 0
                    && (position.signedQuantitySteps() > 0)
                    != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                closeCapacityCancellations = Position.CloseCapacity.conflictsFor(
                        lane, userId, symbolId(), positionKey, positionSide(), side(), originalOrderId, quantitySteps());
            }
            if (!accountBook.selfTradeAllowed(userId)) {
                closeCapacityCancellations = lane.preMatchingCancellations(userId, symbolId(),
                        side(), matchingPriceTicks(), originalOrderId, orderId(), closeCapacityCancellations);
            }
            var user = lane.user(userId);
            orderCheck = new ReplaceOrderCheck(userId, originalOrderId, original.revision(),
                    user == null ? 0 : user.revision(), command, this, required);
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            rejection = failure;
        } finally { accountBook.exitLaneCommandScope(lane); }
        // 完成位可见后 Owner 可能推进回收或复用。
        // 通知目标和分片必须提前捕获，发布后不再读取事件字段。
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishAccountOrderCheckReadyShard(completionShard);
    }

    private PlaceOrderCommand amendCommand(Order original) {
        return new PlaceOrderCommand(amend.replacementOrderId(), decision.instrument().instrumentId(),
                original.side(), amend.hasPriceTicks() ? amend.rawPriceTicks() : original.priceTicks(),
                amend.hasQuantitySteps() ? amend.rawQuantitySteps() : original.remainingQuantitySteps(),
                original.reduceOnly(), original.marginMode(), original.positionSide(), original.orderType(),
                amend.timeInForce() == null ? original.timeInForce() : amend.timeInForce(),
                amend.hasPostOnly() ? amend.isPostOnly() : original.postOnly(),
                amend.newClientOrderId() == null ? "" : amend.newClientOrderId());
    }

    /** 返回本次改单命令的 Core 序号，准入结果和后续撮合沿用此序号。 */
    public long coreSequence() { return sequence; }
    /** 完成后返回新单、原单版本和账户版本组成的准入结果。 */
    public ReplaceOrderCheck orderCheck() { requireComplete(); return orderCheck; }

    /** 要求 Lane 已完成后清理事件。关联命令仍需先结束对解析输入的使用，不能因看到完成位就提前复用。 */
    @Override public void clear() {
        requireComplete();
        discard();
    }

    /** 清理意图、决策和结果引用并重置标记，供未投递清理或终态回收使用。 */
    public void discard() {
        clearBaseOrderCheck();
        amend = null; replace = null; decision = null; accountBook = null; identities = null;
        orderCheck = null;
    }

    private void requireComplete() {
        if (!completed) throw new IllegalStateException("replacement admission is incomplete");
    }
    private static CoreStateRejectedException rejected(String code, String message) {
        return new CoreStateRejectedException(code, message);
    }
}

