package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.RuntimeOrderStateTransitions;
import com.surprising.aeron.service.position.PositionCloseCapacity;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 顺序批量下单中一个当前项的准入和冻结任务。Owner 只推进原账本及控制邮箱，Lane 计算并冻结，订单租约保留在原批次槽；任务可复用，但必须等 Lane 释放并由控制调度器发布结果。
 */
public final class BatchPlaceReservation extends ResolvedPlaceOrder implements IntFunction<Object>, SystemLedgerLane.Entry, java.util.function.BooleanSupplier {
    /** 为本项提供原账本、账户控制派发和完成通知的所属运行时。 */
    private TradingAccountRuntime runtime;
    /** 当前产品的身份表，客户键准备不建立第二份订单字典。 */
    private RuntimeIdentityRegistry identities;
    /** 本项币对、价格、费率及结算事实的既有决策输入。 */
    private PlaceBatchIntentSource.Decision decision;
    /**
     * sequence：本项归属的 Core 命令序号。
     * userId：当前项所属用户编号。
     * openInterestSteps：账本为当前项实际准备的 OI 数量步数。
     */
    private long sequence, userId, openInterestSteps;
    /** 原批次业务命令 UUID。 */
    private UUID commandId;
    /** 按产品和买卖方向选定的冻结资产运行时编号。 */
    private int assetId;
    /**  prepareClientKeyInLane 的原始准备结果，实际客户键通过 clientKeyValue 取得。 返回本项客户身份准备的原始结果值，供原流程回退或取实际身份键，调用方先等控制任务完成。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long preparedClientKey;
    /** 原批次命令槽保存的获准订单租约，寿命长于本项可复用任务。 */
    private OrderRuntime admittedOrder;
    /** 原批次项槽访问入口，Lane 把获准订单租约写回它，不增加 Owner 订单副本。 */
    private PlaceBatchIntentSource source;
    /** 本项在原批次中的下标，用来保存到对应项槽。 */
    private int sourceIndex;
    /**  逆向非只减仓订单所需的平仓冲突撤单编号，供顺序批次后续撮合使用。 在控制回执完成后读取本项平仓容量冲突的前置撤单编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private List<Long> closeCapacityCancellations = List.of();
    /** Lane 的任务体已停止访问字段的可见性标记，Owner 仍须等控制调度器发布结果。 */
    private volatile boolean completed = true;
    /** 本项 OI 输入是否已经就绪，账本先写数值再发布。 */
    private volatile boolean inputReady = true;
    /**
     * inputQueued：本项账本输入任务是否已成功入队，防止重复投递。
     * accountDispatched：本项所属账户控制任务是否已经派发，避免重复执行冻结。
     */
    private boolean inputQueued, accountDispatched;

    /**
     * 要求上一项已不再由 Lane 持有，清理后绑定当前项、用户、命令及决策输入，并根据产品及只减仓标记决定是否需要 OI。
     * @param runtime 所属交易运行时，Owner 用它投递任务，Lane 用它完成原有账户冻结
     * @param identities 当前产品身份表，客户身份在所属 Lane 准备和回退
     * @param sequence 本批项沿用的 Core 命令序号
     * @param userId 该项所属用户的正数编号
     * @param commandId 该项所属批次命令的 UUID
     * @param command 当前项的原始下单意图
     * @param decision Owner 固定的当前币对决策输入和资产编号
     * @param source 原批次命令槽的输入及订单租约保存入口
     * @param sourceIndex 当前项在原批次中的下标
     */
    public void prepare(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            long sequence, long userId, UUID commandId, PlaceOrderCommand command,
            PlaceBatchIntentSource.Decision decision, PlaceBatchIntentSource source, int sourceIndex) {
        if (runtime == null || identities == null || sequence <= 0 || userId <= 0
                || commandId == null || command == null || decision == null || source == null || sourceIndex < 0)
            throw new IllegalArgumentException("invalid batch place reservation");
        runtime.assertOwner();
        if (!completed) throw new IllegalStateException("Account Lane still owns this batch reservation");
        clear();
        this.decision = decision;
        this.source = source;
        this.sourceIndex = sourceIndex;
        initializeOrderIntent(command, decision.context().instrument(), decision.context().symbolId());
        this.runtime = runtime;
        this.identities = identities;
        this.sequence = sequence;
        this.userId = userId;
        this.commandId = commandId;
        openInterestSteps = 0;
        inputReady = !decision.context().instrument().usesOpenInterestForUserLimit() || command.reduceOnly();
        inputQueued = accountDispatched = false;
        completed = false;
    }

    /**
     * 作为原有控制续步入口推进本项准入，返回原控制调度器是否已收齐结果。
     */
    @Override public boolean getAsBoolean() { return pollAdmission(); }

    /**
     * Owner 先尝试投递必要账本输入，未就绪就返回；就绪后仅投递一次所属 Lane 控制任务，再等待原控制调度器完成，不自行读取活动账户。
     */
    public boolean pollAdmission() {
        runtime.assertOwner();
        if (!inputReady) {
            if (!inputQueued) inputQueued = runtime.dispatchLedgerQuery(this);
            if (!inputReady) return false;
        }
        if (!accountDispatched) {
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
            accountDispatched = true;
        }
        return runtime.pollControlLanes();
    }

    /**
     * 账本读取本项币对 OI，先捕获通知目标，再发布输入就绪并唤醒 Owner。
     * @param ignored 账本任务接口传入的原财库，本项从运行时账本索引读取 OI
     */
    @Override public void apply(TreasuryRuntime ignored) {
        openInterestSteps = runtime.ledgerAdmissionOpenInterest(decision.context().instrument().instrumentId());
        // 输入就绪发布前取得的完成通知目标，发布后只用此引用唤醒 Owner。
        TradingAccountRuntime completion = runtime;
        inputReady = true;
        completion.signalOwnerCompletion();
    }

    /**
     * 在原控制调度器建立的所属 Lane 命令范围内解析订单、准备客户身份、检查预留和平仓冲突，然后冻结批次订单并把租约交回原命令槽；失败回退本次客户身份并上抛。
     * @param laneId 原控制调度器分配的目标账户 Lane 编号，必须与用户拓扑和当前范围一致
     */
    @Override public Object apply(int laneId) {
        if (!inputReady) throw new IllegalStateException("Ledger batch admission input is incomplete");
        // 原控制任务已经进入的账户 Lane 命令范围，必须与回调编号和用户一致。
        AccountLaneState lane = runtime.currentLaneCommandScope();
        if (lane == null || lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("batch place reservation requires its Account Lane");
        // 本项客户身份准备的原始返回值，失败时用相同值回退。
        long identity = 0;
        try {
            CoreOrderDecisionResolver.resolveInto(this, decision.context(), userId, intent());
            assetId = runtime.productLine().isDerivative() ? decision.settleAssetId()
                    : side() == CoreOrderSide.BUY ? decision.quoteAssetId() : decision.baseAssetId();
            identity = identities.prepareClientKeyInLane(lane, userId, clientOrderId());
            preparedClientKey = identity;
            // 从准备返回值取出的实际客户身份键，后续账户准入和冻结使用它。
            long clientKey = RuntimeIdentityRegistry.clientKeyValue(identity);
            // 该用户当前币对及持仓腿的已有身份，0 表示无持仓身份。
            long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
            // 本项经过规则、容量和风险检查后计算出的所需预留金额。
            long required = RuntimeOrderAdmission.requiredReservationPrepared(runtime, lane, userId, this,
                    openInterestSteps, lane.admissionOrderIndex(symbolId()), clientKey, symbolId(), positionKey,
                    decision.context().lifecycleSettled(), decision.context().fundingInProgress());
            // 当前已有持仓，逆向新单可能需要先撤冲突的平仓委托。
            PositionRuntime position = positionKey == 0 ? null : lane.position(positionKey);
            if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                    && position != null && position.signedQuantitySteps() != 0
                    && (position.signedQuantitySteps() > 0) != (side() == CoreOrderSide.BUY)) {
                closeCapacityCancellations = PositionCloseCapacity.inspectLane(lane, userId, symbolId(),
                        positionKey, positionSide(), side(), orderId()).conflictsFor(quantitySteps());
            }
            RuntimeOrderStateTransitions.reserveBatchOrderInLane(runtime, userId, this, commandId,
                    required, clientKey, assetId, sequence);
            // 所属 Lane 变更缓冲中刚写出的获准订单，必须存在才能把租约交给批次项槽。
            OrderRuntime admitted = runtime.laneDelta(laneId).orders().get(orderId());
            if (admitted == null) throw new IllegalStateException("batch admission order is missing");
            admittedOrder = source.captureAdmittedOrder(sourceIndex, admitted);
            return null;
        } catch (/* 本项执行的原始运行时异常或 Error，回退已准备客户身份后原样上抛给控制调度器。 */ RuntimeException | Error failure) {
            if (identity != 0) identities.rollbackClientKeyInLane(lane, userId, clientOrderId(), identity);
            preparedClientKey = 0;
            throw failure;
        } finally {
            // 发布完成位后任务体不再读取自己的字段；结果发布仍归原控制调度器，Owner 必须等 pollControlLanes 完成再消费。
            completed = true;
        }
    }

    /**
     * 在原控制调度器完成发布后由 Owner 取出当前项获准订单，没有获准订单时不能读取。
     */
    public OrderRuntime admittedOrder() {
        if (admittedOrder == null) throw new IllegalStateException("batch reservation has not completed");
        return admittedOrder;
    }

    /**
     * Lane 仍持有任务时保留其输入；已释放时清空本项引用和标记。prepare 会拒绝未释放任务，避免故障收尾时复用仍排队的对象。
     */
    public void clear() {
        // 致命故障收尾时控制任务可能仍在队列里；先保留它的输入，prepare 会在 Lane 释放前阻止对象被新一项复用。
        if (!completed) return;
        clearResolvedOrder();
        runtime = null;
        identities = null;
        decision = null;
        commandId = null;
        sequence = userId = openInterestSteps = preparedClientKey = 0;
        assetId = 0;
        admittedOrder = null;
        source = null;
        sourceIndex = 0;
        closeCapacityCancellations = List.of();
        inputReady = true;
        inputQueued = accountDispatched = false;
    }
}
