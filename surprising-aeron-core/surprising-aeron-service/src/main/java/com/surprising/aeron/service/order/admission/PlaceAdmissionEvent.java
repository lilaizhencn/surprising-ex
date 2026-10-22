package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.position.PositionCloseCapacity;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.settlement.MatcherSettlementEvent;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 一笔普通下单的单向准入交接事件，同时复用 ResolvedPlaceOrder 存决策字段。Owner 先绑定路由，账本按需提供 OI，所属 Lane 决策、检查及冻结，Matcher 等回执后才能使用；对象池回收还要满足消费条件。 构造约定：创建可由对象池保存的空准入事件，后续先绑定决策输入，再准备命令归属。 */
@NoArgsConstructor
public final class PlaceAdmissionEvent extends ResolvedPlaceOrder implements SettlementLaneWorker.Command, SystemLedgerLane.Entry {
    /** 本次下单的 Core 命令序号，准入回执和冻结状态都归属此序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    /**
     * timestamp：命令复制后的集群业务时间，写订单及冻结时保持该时间。
     * position：命令的集群日志位置，用于订单状态和后续确定性提交。
     */
    private long timestamp, position;
    /** 下单用户编号，只能由其所属账户 Lane 处理。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    /** 原命令 UUID，订单及预留写入沿用它标识业务操作。 */
    private UUID commandId;
    /** 账本准备的币对 OI 数量，账户准入用它检查比例限额。 */
    private long openInterestSteps;
    /** OI 输入已经就绪的可见性标记，不需要 OI 或只减仓时可直接就绪。 */
    private volatile boolean inputReady = true;
    /** 需要的账本输入任务是否已经成功投递，仍未就绪时不能丢弃本对象。 */
    private boolean inputQueued;
    /** Owner 固定并借用的不可变账本事实，供 Lane 检查资金费及到期结算冲突。 */
    private TreasuryRuntime.SettlementStatus settlementStatus;
    /** Lane 为当前客户端订单标识准备的实际客户键，拒绝时回退并清零。 */
    private long preparedClientKey;
    /** Matcher 是否会借用此事件等待准入回执，要求消费后才能归还对象池。 */
    private boolean matcherWaitRequired;
    /** 本订单实际冻结资产的运行时编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int assetId;
    /** 本次下单目标账户 Lane 编号，用于准入就绪通知。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int laneId;
    /** 提供账户写入、账本输入和完成通知的所属运行时。 */
    private TradingAccountRuntime runtime;
    /** 当前产品身份表，客户标识由 Lane 准备，失败时回退该次准备。 */
    private RuntimeIdentityRegistry identities;
    /** Owner 绑定的本命令价格事实，Lane 据此完成价格决策。 */
    private com.surprising.aeron.service.market.MarkPriceRuntime decisionMark;
    /** 本次准入借用的费率选择表，具体用户费率由 Lane 决策。 */
    private FeePolicyBook decisionPolicies;
    /** Owner 固定的决策业务时间，价格鲜度、费率及到期检查使用此值。 */
    private long decisionTimestamp;
    /** 本次 Lane 操作带来的客户身份分配计数差，Owner 读取后清零。 */
    private long identityAllocations;
    /** 账户准入计算的应预留金额，回执连同是否获准一起交给 Matcher。 */
    private long reservedAmount;
    /** 成功写入订单后用户账户版本，用于 Matcher 结算回执核对。 */
    private long admittedAccountVersion;
    /** Lane 捕获的业务拒绝、算术或参数异常，完成后由 Owner 或 Matcher 读取。 */
    private RuntimeException rejection;
    /** 撮合前应执行的撤单编号，合并平仓容量冲突和原账户前置撤单规则，不交出可变订单实体。 */
    private List<Long> closeCapacityCancellations = List.of();
    /** Lane 写完准入数据后发布的完成位，读取回执前必须看到此位。 */
    private volatile boolean completed;
    /** Matcher 已读取或明确取消等待的发布位，可见后 Matcher 不得继续访问事件字段。 */
    private volatile boolean matcherConsumed;

    /**
     * Owner 先绑定意图和币对路由，再保存价格、费率表及决策时间引用；完整价格与费率计算由所属 Lane 进行。
     * @param intent 原始下单意图，包含订单编号、方向、数量、模式和客户端标识
     * @param instrument 本次决策绑定的币对配置，后续准入须与同一业务身份一致
     * @param symbolId 币对的运行时稠密编号，冷业务状态入口允许用 -1 表示未分配
     * @param mark Owner 为此命令绑定的价格事实，现货可不需要衍生品价格
     * @param policies 用于本次用户费率决策的费率表引用
     * @param timestamp 本命令固定的集群业务时间
     */
    public void prepareDecision(com.surprising.aeron.protocol.PlaceOrderCommand intent,
            com.surprising.aeron.service.instrument.CoreInstrument instrument, int symbolId,
            com.surprising.aeron.service.market.MarkPriceRuntime mark, FeePolicyBook policies, long timestamp) {
        if (policies == null || timestamp <= 0) throw new IllegalArgumentException("invalid place decision input");
        initializeOrderIntent(intent, instrument, symbolId);
        decisionMark = mark;
        decisionPolicies = policies;
        decisionTimestamp = timestamp;
    }

    /**
     * 在 Owner 已绑定的相同路由上补齐价格、冻结资产和费率，直接初始化决策，不能像父类通用 resolve 那样先清除路由。
     * @param intent 原始下单意图，包含订单编号、方向、数量、模式和客户端标识
     * @param instrument 本次决策绑定的币对配置，后续准入须与同一业务身份一致
     * @param symbolId 币对的运行时稠密编号，冷业务状态入口允许用 -1 表示未分配
     * @param matchingPriceTicks 实际送入 Matcher 的价格刻度，市价单也使用已经决定的保护边界
     * @param reservationPriceTicks 计算预留金额采用的价格刻度，可能比撮合边界更保守
     * @param markPriceTicks 本次决策的估值价格刻度，现货在该槽保存订单自己的价格边界
     * @param indexPriceTicks 本次决策使用的指数价格刻度，不需要该价格的入口可为 0
     * @param forwardPriceTicks 本次决策使用的远期价格刻度，有远期价格时必须同时提供指数价格
     * @param reservationKind 应冻结现货资产还是衍生品保证金的类别
     * @param reservationAsset 已经规范化的冻结资产代码，衍生品为结算资产，现货按买卖方向选择
     * @param makerFeeRatePpm Maker 费率，单位为百万分比，负数表示返佣
     * @param takerFeeRatePpm Taker 费率，单位为百万分比，且不能小于 Maker 费率
     */
    public void resolve(com.surprising.aeron.protocol.PlaceOrderCommand intent,
                        com.surprising.aeron.service.instrument.CoreInstrument instrument,
                        int symbolId, long matchingPriceTicks, long reservationPriceTicks,
                        long markPriceTicks, long indexPriceTicks, long forwardPriceTicks,
                        com.surprising.aeron.protocol.ReservationKind reservationKind,
                        String reservationAsset, long makerFeeRatePpm, long takerFeeRatePpm) {
        initializeResolvedOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                makerFeeRatePpm, takerFeeRatePpm);
    }

    /**
     * 校验已绑定路由并固定命令、用户、资产、目标 Lane 和账本事实，重置回执及消费标记，按产品规则决定是否等待 OI。
     * @param coreSequence 本次正数 Core 命令序号
     * @param userId 本次下单用户的正数编号
     * @param commandId 原下单命令的非空 UUID
     * @param settlementStatus 准入前固定的非空账本结算事实
     * @param assetId 订单冻结资产的运行时编号
     * @param laneId 所属账户 Lane 编号
     * @param matcherWaitRequired 是否需要保留事件直到 Matcher 消费其准入回执
     * @param runtime 所属交易运行时，提供原账户写入与完成交接
     * @param identities 当前产品身份表，供所属 Lane 查找持仓和准备客户键
     * @param timestamp 本命令固定的集群业务时间
     * @param position 本命令固定的集群日志位置
     */
    public PlaceAdmissionEvent prepare(long coreSequence, long userId, UUID commandId,
                                TreasuryRuntime.SettlementStatus settlementStatus,
                                int assetId, int laneId, boolean matcherWaitRequired,
                                TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0
                || intent() == null || commandId == null
                || symbolId() < 0 || assetId < 0 || settlementStatus == null
                || laneId < 0 || runtime == null) {
            throw new IllegalArgumentException("invalid place admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.commandId = commandId;
        openInterestSteps = 0;
        inputQueued = false;
        inputReady = !instrument().usesOpenInterestForUserLimit() || reduceOnly();
        this.settlementStatus = settlementStatus;
        this.preparedClientKey = 0;
        this.identities = identities;
        identityAllocations = 0;
        this.assetId = assetId;
        this.laneId = laneId;
        this.matcherWaitRequired = matcherWaitRequired;
        this.runtime = runtime;
        admittedAccountVersion = 0;
        rejection = null;
        closeCapacityCancellations = List.of();
        reservedAmount = 0;
        completed = false;
        matcherConsumed = false;
        return this;
    }

    /**
     * 只有 Lane 已完成且需要的 Matcher 回执已经消费，才清理决策、引用和标记供池化复用；提前回收立即报错。
     */
    @Override
    public void clear() {
        if (!complete()) throw new IllegalStateException("cannot recycle an incomplete place admission");
        if (matcherWaitRequired && !matcherConsumed) {
            throw new IllegalStateException("cannot recycle a place admission before matcher handoff");
        }
        clearResolvedOrder();
        commandId = null;
        settlementStatus = null;
        preparedClientKey = 0;
        runtime = null;
        identities = null;
        decisionMark = null;
        decisionPolicies = null;
        decisionTimestamp = 0;
        admittedAccountVersion = 0;
        rejection = null;
        closeCapacityCancellations = List.of();
        reservedAmount = 0;
        matcherWaitRequired = false;
        matcherConsumed = false;
        inputQueued = false;
        inputReady = true;
        openInterestSteps = 0;
        // 下一次 Owner 决策可能在 prepare 之前就拒绝；池化对象清理后应允许该未投递输入直接 discard。
        completed = false;
    }

    /**
     * 在目标 Lane 等账本输入就绪后，补决策、准备客户身份、计算预留和前置撤单，再写订单及冻结；可预期拒绝回退客户身份，最后记录分配计数并发布回执。
     * @param lane 执行准入的账户 Lane，必须与用户拓扑一致
     */
    public void execute(AccountLaneState lane) {
        if (!inputReady) throw new IllegalStateException("Ledger admission input is incomplete");
        if (lane.laneId() != runtime.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place admission reached the wrong Account Lane");
        }
        // 本次准入开始前 Lane 的客户身份分配计数，结束后取差交给 Owner。
        long allocationsBefore = lane.clientIdentityAllocations();
        try {
            runtime.enterLaneCommandScope(lane);
            try {
                // 借用的账本事实表明该合约是否已到期结算，参与价格决策和准入拒绝。
                boolean lifecycleSettled = settlementStatus.lifecycleSettled();
                // 账本事实中的资金费截面处理中标记，准入不能跨越该生命周期边界。
                boolean fundingInProgress = settlementStatus.fundingInProgress();
                if (!resolvedOrderInitialized())
                    com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver.resolvePrepared(
                            this, decisionMark, decisionPolicies, userId, decisionTimestamp, lifecycleSettled);
                preparedClientKey = RuntimeIdentityRegistry.clientKeyValue(
                        identities.prepareClientKeyInLane(lane, userId, clientOrderId()));
                // 在当前 Lane 查到的用户持仓身份，无持仓身份时为 0。
                long positionKey = identities.findPositionKeyValueInLane(
                        lane, userId, instrument(), positionSide());
                // 本事件先前已固定的币对稠密编号，不能在 Lane 改变路由。
                int symbolId = symbolId();
                // 统一账户检查及产品公式算出的本订单应预留金额，随后交账户写入执行。
                long requiredReservation = RuntimeOrderAdmission.requiredReservationPrepared(
                        runtime, lane, userId, this, openInterestSteps,
                        lane.admissionOrderIndex(symbolId), preparedClientKey, symbolId, positionKey,
                        lifecycleSettled, fundingInProgress);
                reservedAmount = requiredReservation;
                // 当前已有持仓，逆向非只减仓新单可能需要先撤冲突平仓委托。
                PositionRuntime currentPosition = positionKey == 0 ? null : lane.position(positionKey);
                if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                        && currentPosition != null && currentPosition.signedQuantitySteps() != 0
                        && (currentPosition.signedQuantitySteps() > 0)
                        != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                    closeCapacityCancellations = PositionCloseCapacity.inspectLane(
                            lane, userId, symbolId, positionKey, positionSide(), side(), orderId())
                            .conflictsFor(quantitySteps());
                }
                closeCapacityCancellations = lane.preMatchingCancellations(userId, symbolId,
                        side(), matchingPriceTicks(), orderId(), orderId(), closeCapacityCancellations);
                runtime.placeOrderInLane(lane, userId, this, commandId,
                        requiredReservation, preparedClientKey, symbolId, assetId, coreSequence, null, timestamp, position);
                admittedAccountVersion = lane.user(userId).revision();
            } finally {
                runtime.exitLaneCommandScope(lane);
            }
        } catch (/* 本次准入的业务拒绝、溢出或参数错误，回退客户身份准备并保存到拒绝回执。 */ CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            identities.rollbackClientKeyInLane(lane, userId, clientOrderId(), preparedClientKey);
            preparedClientKey = 0;
            rejection = failure;
        }
        identityAllocations = lane.clientIdentityAllocations() - allocationsBefore;
        // 完成位可见前先取得通知所需的运行时引用；排队事件仍保留到本命令的 Matcher 续步消费完回执。
        // 发布完成或消费位之前先取得的通知目标，避免对象回收清理 runtime 后再读取该字段。
        TradingAccountRuntime completionRuntime = runtime;
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishPlaceAdmissionReady(laneId, coreSequence);
    }

    /**
     * FUSED 模式在 Owner 等账本 OI 就绪后内联执行这份原准入事件，不在同线程里阻塞等待尚未执行的账户任务。
     */
    public void executeInlineIfReady() {
        if (runtime == null || !runtime.isFusedExecution())
            throw new IllegalStateException("inline admission requires the fused trading Owner");
        runtime.assertOwner();
        if (!completed && inputReady) execute(runtime.accountLane(runtime.topology().accountLaneId(userId)));
    }

    /**
     * 返回 Lane 是否已经发布准入完成，成功或业务拒绝都属于已完成。
     */
    public boolean complete() {
        return completed;
    }

    /**
     * 返回账户准入依赖的 OI 输入是否已就绪。
     */
    @Override public boolean accountReady() { return inputReady; }
    /**
     * 记录账本输入任务已经投递，后续未收到输入前不能丢弃事件。
     */
    public void inputQueued() { inputQueued = true; }
    /**
     * 判断账本任务已经投递但输入尚未就绪，供回收路径保护仍被账本借用的对象。
     */
    public boolean inputPending() { return inputQueued && !inputReady; }

    /**
     * 在账本线程读取准入 OI，再发布输入就绪位，账户消费者随后才可执行。
     * @param ignored 账本接口传入的原财库，本方法从运行时账本 OI 索引取值，不直接使用该参数
     */
    @Override public void apply(TreasuryRuntime ignored) {
        openInterestSteps = runtime.ledgerAdmissionOpenInterest(instrumentId());
        inputReady = true;
    }

    /**
     * 清理从未正式交给账户 Lane 的已准备事件；账本仍持有输入或 Lane 已完成时必须走对应交接路径，不能直接丢弃。
     */
    public void discard() {
        if (inputPending()) throw new IllegalStateException("Ledger still owns place admission input");
        if (complete()) throw new IllegalStateException("completed place admission must be collected");
        clearResolvedOrder();
        commandId = null;
        settlementStatus = null;
        preparedClientKey = 0;
        runtime = null;
        identities = null;
        decisionMark = null;
        decisionPolicies = null;
        decisionTimestamp = 0;
        admittedAccountVersion = 0;
        rejection = null;
        closeCapacityCancellations = List.of();
        reservedAmount = 0;
        identityAllocations = 0;
        coreSequence = 0;
        userId = 0;
        laneId = 0;
        inputQueued = false;
        inputReady = true;
        openInterestSteps = 0;
        matcherWaitRequired = false;
        matcherConsumed = false;
        completed = false;
    }

    /**
     * Matcher 最多等待三十秒取得 Lane 回执，区分成功、业务拒绝、溢出及非法参数并填入原结算事件；读取完撤单编号后先捕获通知对象，再发布消费位，之后不再访问本事件字段。
     * @param target Matcher 已准备的原结算事件，接收准入成功标记、金额、版本和前置撤单编号
     */
    public void awaitMatcherReceipt(MatcherSettlementEvent target) {
        if (target == null) {
            throw new IllegalArgumentException("place admission target is required");
        }
        // Matcher 等待 Lane 回执的三十秒单调时钟上限，不参与订单业务时间判断。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        // 原实现保留的局部计数，当前等待循环没有读取或递增它。
        int spins = 0;
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
        // 按原拒绝类型映射出的协议业务码，成功时使用 NONE。
        CoreResultCode resultCode = rejection == null ? CoreResultCode.NONE
                : rejection instanceof CoreStateRejectedException /* 携带明确业务拒绝码的准入异常，用其 code 映射协议结果。 */ stateRejected
                ? CoreResultCode.fromRejectionCode(stateRejected.code())
                : rejection instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW
                : CoreResultCode.INVALID_COMMAND;
        target.admissionReceipt(orderId(), admittedAccountVersion, reservedAmount,
                rejection == null, resultCode.wireCode());
        if (rejection == null) target.addAdmissionCancellations(closeCapacityCancellations);
        // 先取得通知目标，再发布消费位。Owner 可能立即清空池化事件；消费位可见后 Matcher 只使用已捕获引用，不再读取事件字段。
        // 发布完成或消费位之前先取得的通知目标，避免对象回收清理 runtime 后再读取该字段。
        TradingAccountRuntime completionRuntime = runtime;
        matcherConsumed = true;
        completionRuntime.signalOwnerCompletion();
    }

    /**
     * 无需 Matcher 等待的事件直接视为可消费完成，否则读取实际消费发布位。
     */
    public boolean matcherConsumed() { return !matcherWaitRequired || matcherConsumed; }

    /**
     * 要求准入已完成；成功返回撮合前撤单编号，拒绝时返回空列表，不能执行失败准入的撤单建议。
     */
    public List<Long> closeCapacityCancellations() {
        if (!complete()) throw new IllegalStateException("place admission is incomplete");
        return rejection == null ? closeCapacityCancellations : List.of();
    }

    /**
     * 已完成的准入不再需要进入 Matcher 等待路径时，显式发布消费完成；重复取消或条件不符视为内部错误。
     */
    public void cancelMatcherWait() {
        if (!complete() || !matcherWaitRequired || matcherConsumed) {
            throw new IllegalStateException("invalid matcher admission cancellation");
        }
        matcherConsumed = true;
    }

    /**
     * 取走本次客户身份分配计数差并清零，避免 Owner 重复统计。
     */
    public long takeIdentityAllocations() { /* 本次取走的客户身份分配计数差，返回后原字段已经清零。 */ long result = identityAllocations; identityAllocations = 0; return result; }
    /**
     * 返回本次准入准备的实际客户端订单身份键，读取前由调用方遵守完成边界。
     */
    public long clientKey() { return preparedClientKey; }
    /**
     * 只有完整决策已完成且准入成功才交出本对象作为订单输入，不能把半成品或拒单当获准订单。
     */
    public ResolvedPlaceOrder resolvedOrder() {
        if (!complete() || !resolvedOrderInitialized() || rejection != null) {
            throw new IllegalStateException("place admission is not accepted");
        }
        return this;
    }

    /**
     * 路由准备好就允许 Matcher 保留同一输入引用，但读取 Lane 补齐的价格和费率前仍必须等准入回执。
     */
    public ResolvedPlaceOrder matchingOrder() {
        if (intent() == null) throw new IllegalStateException("place admission is not prepared");
        return this;
    }

    /**
     * Owner 仅使用已经绑定的路由输入预构建结算事件，不代表账户已准入或价格字段已经可跨线程读取。
     */
    public ResolvedPlaceOrder preparedOrder() {
        if (intent() == null) throw new IllegalStateException("place admission is not prepared");
        return this;
    }

    /**
     * 要求 Lane 已完成后返回原拒绝异常，成功时为 null。
     */
    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place admission is incomplete");
        return rejection;
    }
}
