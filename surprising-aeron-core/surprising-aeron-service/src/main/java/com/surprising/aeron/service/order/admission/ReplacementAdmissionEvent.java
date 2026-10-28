package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.command.ResolvedMatchingAdmission;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.position.PositionCloseCapacity;
import com.surprising.aeron.service.position.PositionRuntime;
import java.util.List;

/**
 * 改单或替换单的所属账户 Lane 准入事件。Owner 固定原单身份、决策输入和 Matcher 路由，账本按需提供 OI；Lane 校验原单并生成新订单准入结果，命令保留该决策输入直到终态，不在这里直接修改 Matcher 订单簿。
 */
public final class ReplacementAdmissionEvent extends BaseOrderAdmissionEvent {
    /**
     * sequence：本次改单的 Core 命令序号。
     * userId：原单和新单所属用户编号。
     * originalOrderId：需要保持版本检查的原普通订单编号。
     */
    private long sequence, userId, originalOrderId;
    /** Owner 固定的 Matcher 分片，完成通知按此唤醒原撮合续步。 */
    private int matcherShard;
    /** 选择性改单意图，与 replace 互斥。 */
    private AmendOrderCommand amend;
    /** 完整替换意图，与 amend 互斥。 */
    private ReplaceOrderCommand replace;
    /** Owner 固定的币对决策输入，Lane 读取后写入本事件继承的解析字段。 */
    private RuntimeOrderAdmission.Context decision;
    /** 提供原账户与账本交接的所属运行时。 */
    private TradingAccountRuntime runtime;
    /** 当前产品身份查询表，不在此创建订单身份镜像。 */
    private RuntimeIdentityRegistry identities;
    /** 成功后生成的新单准入结果，引用本事件的解析输入，命令须保留到终态。 */
    private ResolvedMatchingAdmission admission;

    /**
     * 要求改单和替换意图恰好提供一种，绑定原订单、用户、配置和 Matcher 分片，重置结果并按产品判断是否等待账本 OI。
     * @param sequence 本次正数 Core 命令序号
     * @param userId 原单及新单所属的正数用户编号
     * @param originalOrderId 当前要修改或替换的原订单编号
     * @param amend 选择性改单意图，与 replace 恰好提供一个
     * @param replace 完整替换意图，与 amend 恰好提供一个
     * @param decision Owner 已准备的币对、价格、费率和账本状态输入
     * @param matcherShard 固定的 Matcher 分片编号，必须在当前拓扑范围内
     * @param runtime 提供账户检查、账本输入及完成通知的所属运行时
     * @param identities 当前产品身份表，只查找原有客户和持仓身份
     */
    public ReplacementAdmissionEvent prepare(long sequence, long userId, long originalOrderId,
            AmendOrderCommand amend, ReplaceOrderCommand replace,
            RuntimeOrderAdmission.Context decision, int matcherShard,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (sequence <= 0 || userId <= 0 || originalOrderId <= 0 || (amend == null) == (replace == null)
                || decision == null || runtime == null || identities == null
                || matcherShard < 0 || matcherShard >= runtime.topology().matchingEngineCount())
            throw new IllegalArgumentException("invalid replacement admission");
        this.sequence = sequence; this.userId = userId; this.originalOrderId = originalOrderId;
        this.amend = amend; this.replace = replace; this.decision = decision;
        this.matcherShard = matcherShard; openInterestSteps = 0; this.runtime = runtime; this.identities = identities;
        admission = null; rejection = null; completed = false; closeCapacityCancellations = List.of();
        return this;
    }

    /**
     * 在原单所属 Lane 核对原单存在、用户、OPEN 状态及可改单类型，解析新意图并排除原单占用计算预留；现货改单还检查原冻结和可用金额，最后生成版本化准入结果或保存拒绝。
     * @param lane 实际执行原单检查的所属账户 Lane
     */
    @Override public void execute(AccountLaneState lane) {
        // 完成位发布前先取得的通知目标，发布后不再从可回收事件读取运行时。
        TradingAccountRuntime completionRuntime = runtime;
        // 提前捕获的 Matcher 通知分片，避免事件复用后读取新命令路由。
        int completionShard = matcherShard;
        // 实际执行的账户 Lane 编号，必须与用户所属 Lane 一致。
        int completionLane = lane.laneId();
        if (completionLane != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("replacement admission crossed Account Lane");
        runtime.enterLaneCommandScope(lane);
        try {
            // 当前 Lane 权威表中的原订单，必须属于用户且仍为 OPEN。
            OrderRuntime original = lane.order(originalOrderId);
            if (original == null) throw rejected("ORDER_NOT_FOUND", "order does not exist");
            if (original.userId() != userId) throw rejected("ORDER_OWNER_MISMATCH", "order belongs to another user");
            if (original.status() != CoreOrderStatus.OPEN) throw rejected("INVALID_COMMAND", "order is not replaceable");
            if (amend != null && original.orderType() != CoreOrderType.LIMIT)
                throw rejected("INVALID_COMMAND", "order is not amendable");
            // 完整替换意图或按原单补齐的改单新意图，交给统一决策。
            PlaceOrderCommand command = replace == null ? amendCommand(original) : replace.replacement();
            RuntimeOrderAdmission.resolveInto(this, decision, userId, command);
            // 新意图对应的已有持仓身份，没有持仓时可为 0。
            long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
            // 排除原订单占用后算出的新订单应预留金额，直接透传已核验原单，消除多余哈希表查找。
            long required = RuntimeOrderAdmission.requiredReplacementReservationPrepared(
                    runtime, lane, userId, this, openInterestSteps, lane.admissionOrderIndex(symbolId()),
                    original, identities.findClientKeyValueInLane(lane, userId, clientOrderId()), positionKey,
                    decision.lifecycleSettled(), decision.fundingInProgress());
            if (amend != null && runtime.productLine() == com.surprising.product.api.ProductLine.SPOT) {
                // 现货改单原单的冻结记录，资产必须与新单一致。
                var reservation = lane.reservation(originalOrderId);
                if (reservation == null || !reservationAsset().equals(identities.asset(reservation.assetId())))
                    throw new IllegalStateException("spot amend original reservation is missing or mismatched");
                // 原冻结资产的账户余额，新所需金额不得超过可用加原冻结金额。
                var balance = runtime.balance(userId, reservation.assetId());
                if (balance == null || required > Math.addExact(balance.availableUnits(), reservation.reservedUnits()))
                    throw rejected("INSUFFICIENT_AVAILABLE_BALANCE", "available balance is insufficient for amended order");
            }
            // 原用户当前持仓，逆向非只减仓新单可能需要先撤平仓冲突委托。
            PositionRuntime position = positionKey == 0 ? null : lane.position(positionKey);
            if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                    && position != null && position.signedQuantitySteps() != 0
                    && (position.signedQuantitySteps() > 0)
                    != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                closeCapacityCancellations = PositionCloseCapacity.conflictsFor(
                        lane, userId, symbolId(), positionKey, positionSide(), side(), originalOrderId, quantitySteps());
            }
            closeCapacityCancellations = lane.preMatchingCancellations(userId, symbolId(),
                    side(), matchingPriceTicks(), originalOrderId, orderId(), closeCapacityCancellations);
            // 当前账户用户元数据，其版本与原单版本一同封入准入结果。
            var user = lane.user(userId);
            admission = new ResolvedMatchingAdmission(userId, originalOrderId, original.revision(),
                    user == null ? 0 : user.revision(), command, this, required);
        } catch (/* 改单校验产生的业务拒绝、溢出或非法参数，保存为本次拒绝回执。 */ CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            rejection = failure;
        } finally { runtime.exitLaneCommandScope(lane); }
        // 完成位可见后 Owner 可能推进回收或复用；通知目标和分片必须提前捕获，发布后不再读取事件字段。
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishAccountAdmissionReadyShard(completionShard);
    }

    /**
     * 用原订单属性补齐未指定的价格、数量、有效期和 postOnly，改为新的订单编号；未指定新客户端标识时用空文本，不复用旧客户端标识。
     * @param original 已经核对归属和状态的原订单，用于补齐选择性改单意图
     */
    private PlaceOrderCommand amendCommand(OrderRuntime original) {
        return new PlaceOrderCommand(amend.replacementOrderId(), decision.instrument().instrumentId(),
                original.side(), amend.hasPriceTicks() ? amend.rawPriceTicks() : original.priceTicks(),
                amend.hasQuantitySteps() ? amend.rawQuantitySteps() : original.remainingQuantitySteps(),
                original.reduceOnly(), original.marginMode(), original.positionSide(), original.orderType(),
                amend.timeInForce() == null ? original.timeInForce() : amend.timeInForce(),
                amend.hasPostOnly() ? amend.isPostOnly() : original.postOnly(),
                amend.newClientOrderId() == null ? "" : amend.newClientOrderId());
    }

    /**
     * 返回本次改单命令的 Core 序号，准入结果和后续撮合沿用此序号。
     */
    public long coreSequence() { return sequence; }
    /**
     * 完成后返回新单、原单版本和账户版本组成的准入结果；拒绝时可为 null，调用方先检查 rejection。
     */
    public ResolvedMatchingAdmission admission() { requireComplete(); return admission; }

    /**
     * 要求 Lane 已完成后清理事件；关联命令仍需先结束对解析输入的使用，不能因看到完成位就提前复用。
     */
    @Override public void clear() {
        requireComplete();
        discard();
    }

    /**
     * 清理意图、决策和结果引用并重置标记，供未投递清理或终态回收使用。
     */
    public void discard() {
        clearBaseAdmission();
        amend = null; replace = null; decision = null; runtime = null; identities = null;
        admission = null;
    }

    /**
     * 保护结果读取和回收入口，未收到 Lane 完成就立即报错。
     */
    private void requireComplete() {
        if (!completed) throw new IllegalStateException("replacement admission is incomplete");
    }
    /**
     * 构造带明确业务码的改单拒绝异常，保留原失败原因供上层编码。
     * @param code 要交给上层映射的明确业务拒绝码
     * @param message 该次改单被拒绝的具体原因
     */
    private static CoreStateRejectedException rejected(String code, String message) {
        return new CoreStateRejectedException(code, message);
    }
}
