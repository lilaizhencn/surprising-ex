package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.CoreMatchingOrder;
import com.surprising.aeron.service.matcher.CoreMatchingResult;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.matcher.MatchingResult;
import com.surprising.aeron.service.order.admission.PlaceAdmissionEvent;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.command.DecodedMatchingCommand;
import com.surprising.aeron.service.order.command.ResolvedMatchingAdmission;
import com.surprising.aeron.service.order.commit.OrderedCommitCoordinator;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.ResponseArena;
import com.surprising.aeron.service.settlement.MatcherSettlementEvent;
import com.surprising.aeron.service.settlement.MatcherSettlementPlan;
import com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;
import com.surprising.aeron.service.treasury.RuntimeFundsDelta;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 一条 Core 命令从准入到响应退休的可复用槽：保存输入、业务阶段、Matcher 发布、Lane 完成、挂起提交与响应租约。Owner 写编排字段，Matcher 通过约定路由和 volatile 完成引用交接；不是任意线程可写的统一状态容器。claimed 控制池复用，clear 只清生命周期，部分旧引用与序号等到 initialize 覆盖。
 * 构造约定：创建供固定环预分配的空槽，尚未占用或初始化完整命令。
 */
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public final class CommandSlot implements MatcherSettlementEvent.MatcherCompletionRoute {
    /**  当前命令的可空批次上下文，初始化或 clearReferences 断开，单纯 clear 不清所有业务引用。 返回当前命令关联的可空批次上下文。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    OrderBatchPending orderBatch;
    /**  当前槽承载的下单、撤单、触发、强平或结算等操作类别。 返回当前保留的业务操作类别。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private Operation operation;
    /**  本次完整 Core 命令引用，Owner 初始化后供固定任务读取。 返回当前命令引用，使用方须遵守槽生命周期。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreMessage command;
    /**  本次命令的确定性指纹。 返回当前命令已保存的指纹。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommandFingerprint fingerprint;
    /**  准入确定的先撤订单号列表，primitive 不可变列表可借用。 返回准入阶段确定的先撤订单号列表，不在此执行撤单。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private List<Long> preMatchingCancellationOrderIds;
    /**  本命令当前阶段的资金事实累加器，不是可写账户余额。 返回当前命令拥有的资金累加器，跨阶段交接由调用方控制。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final RuntimeFundsAccumulator fundsAccumulator = new RuntimeFundsAccumulator();
    /**  本命令已经解码的输入引用。 返回命令的解码槽引用，不能在发布后任意改写。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private DecodedMatchingCommand decodedCommand;
    /** 解析后的可空替换/改单准入信息。 */
    private ResolvedMatchingAdmission admission;
    /** 首次提交边界固定的业务时间。 */
    private long commitFenceTimestamp;
    /** 首次提交边界固定的 Cluster 日志位置。 */
    private long commitFenceClusterPosition;
    /**  是否已经保存首次提交边界。 返回首次提交边界是否已经保存。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private boolean commitFenceEstablished;
    /** 当前命令持有的结算事件，生命周期与传输槽回收相互独立。 */
    private MatcherSettlementEvent settlementContinuation;
    /** 与结算事件配套的账户应用计划。 */
    private MatcherSettlementPlan settlementPlan;
    /**  触发等路径已经形成的独立撮合输入。 返回已经形成的独立撮合输入，普通直达路径也可能不需要它。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private CoreMatchingOrder admittedMatchingOrder;

    /** 普通下单在所属 Lane 执行的准入事件。 */
    private PlaceAdmissionEvent placeAdmission;
    /** Owner 是否已收取本次准入收据。 */
    private boolean placeAdmissionOwnerCollected;
    /** 实时成交捕获仍需借用的准入输入。 */
    private ResolvedPlaceOrder realtimeResolvedTakerOrder;
    /** 触发子单已经完成冻结准入的订单引用。 */
    private OrderRuntime triggerAdmissionOrder;
    /** 触发续步已绑定的唯一源触发单收据。 */
    private com.surprising.aeron.model.CoreTriggerOrderState sourceTrigger;
    /** 保留可复用强平执行工作，回收时只清工作内部引用。 */
    private com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionWork reusableLiquidationWork;
    /** 首次使用时创建、随后复用的强平校验工作。 */
    private com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionCheck liquidationCheck;
    /** 延后撮合前固定的原 Cluster 时间、日志位置与来源键。 */
    private long deferredClusterTimestamp, deferredClusterPosition;
    private TradingCoreRuntime.SourceKey deferredSourceKey;
    /** 替换/改单绑定的所属 Lane 准入事件。 */
    private com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent replacementAdmission;

    /** Owner 观察的紧凑阶段标签，不代替结果与 Lane 完成掩码判断。 */
    private byte matchingLifecycle;
    /** 阶段值零：已经准入或空槽初始状态，尚未标记提交。 */
    static final byte MATCHING_ADMITTED = 0;
    /** 阶段值一：受既有边界阻塞，保存元数据等待再次准入推进。 */
    static final byte MATCHING_DEFERRED = 1;
    /** 阶段值二：外层已标记提交 Matcher。 */
    static final byte MATCHING_SUBMITTED = 2;
    /** 阶段值三：撮合事实已经绑定，可开始账户扇出。 */
    static final byte MATCHER_DONE = 3;
    /** 阶段值四：全部预期账户 Lane 完成位已经收齐。 */
    static final byte LANES_DONE = 4;
    /** 阶段值五：外层已标记该命令提交完成。 */
    static final byte COMMITTED = 5;
    /**
     *  跨分片控制撤单已开始的 Owner 标记，防止重复派发。
     * 返回跨分片控制撤单是否已经派发启动。
     * 设置跨分片撤单启动标记，外层据此避免重复派发。
     * @param crossShardCancellationStarted 要保存或返回的当前字段值，方法本身不复制引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean crossShardCancellationStarted;
    /**  此命令是否走不依赖 Cluster 响应窗口的内部执行路径。 返回该命令是否按独立于 Cluster 响应窗口的内部路径推进。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean clusterIndependent;
    /**
     *  本命令不再产生新资金事实的边界标记，响应租约可能仍未退休。
     * 返回本命令是否已不可能产生新的资金派发事实，响应存储租约此时仍可能存在。
     * 设置不再产生新资金事实的边界标记。
     * @param ledgerDispatchClosed 要保存或返回的当前字段值，方法本身不复制引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean ledgerDispatchClosed;
    /**
     *  只为实时推送保留的命令独占订单引用或副本，具体租约由发布路径约束。
     * 返回实时推送所需的命令独占订单副本。
     * 保存实时推送订单引用，不复制对象。
     * @param realtimeTakerOrder 要保存或返回的当前字段值，方法本身不复制引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    com.surprising.aeron.service.order.state.OrderRuntime realtimeTakerOrder;
    /** 外层统计中是否已计入当前流水结算项，take 时读出清零。 */
    private boolean pipelinedSettlementCounted;
    /**  普通命令缓存的 Matcher 分片，未缓存或初始化后负一。 返回已缓存的静态分片，尚未缓存时负一。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int cachedMatcherShard = -1;

    // 撮合控制与结算控制共用当前槽的续步入口，不再另建控制状态表。
    /**
     *  Owner 当前待轮询的控制续步，返回真表示该工作完成。
     * 返回当前待 Owner 轮询的可空控制工作。
     * 直接替换控制工作引用，与 deferControl 不同，不执行重复绑定检查。
     * @param controlWork 要保存或返回的当前字段值，方法本身不复制引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    java.util.function.BooleanSupplier controlWork;
    /** 本槽保留的到期结算工作对象，清其引用而不每次重建。 */
    private RuntimeLifecycleSettlementContinuation.SettlementWork reusableSettlementWork;
    /** 预构造的到期结算 Owner 轮询器。 */
    private final SettlementControlContinuation settlementControl = new SettlementControlContinuation();
    /** 预构造的强平执行 Owner 轮询器。 */
    private final LiquidationExecutionContinuation liquidationExecutionControl =
            new LiquidationExecutionContinuation();
    /** 预构造的 Matcher 准入收据等待任务。 */
    private final AdmissionMatchingContinuation admissionMatching = new AdmissionMatchingContinuation();
    /** 共同前置撤单输入列表，Matcher 使用期间保持稳定。 */
    private List<CancellationOrder>
            matcherPreCancellations = List.of();
    /** 预构造的控制撤单 Matcher 任务。 */
    private final ControlMatchingContinuation controlMatching = new ControlMatchingContinuation();
    /** 预构造的普通或冻结批量项下单任务。 */
    private final PlaceMatchingContinuation placeMatching = new PlaceMatchingContinuation();
    /** 预构造的普通撤单任务。 */
    private final CancelMatchingContinuation cancelMatching = new CancelMatchingContinuation();
    /** 预构造的替换/改单撮合任务。 */
    private final ReplaceMatchingContinuation replaceMatching = new ReplaceMatchingContinuation();
    /**
     *  当前等待收集的 Lane 提交事件。
     * 返回待收集的账户 Lane 提交事件。
     * 直接保存可空 Lane 提交事件引用。
     * @param commitEvent 要保存或返回的当前字段值，方法本身不复制引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    com.surprising.aeron.service.core.change.LaneCommitEvent commitEvent;

    /**
     *  当前命令是否还在等控制续步。
     * 返回当前命令是否还在等控制续步。
     * 保存控制续步等待标记。
     * @param controlPending 要保存或返回的当前字段值，方法本身不复制引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean controlPending;
    /**
     *  控制账户任务是否已提交，后续财库校验不得再次派发。
     * 返回控制账户任务是否已提交，避免后续财库验证重派。
     * 设置控制账户已提交标记。
     * @param controlAccountsCommitted 要保存或返回的当前字段值，方法本身不复制引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean controlAccountsCommitted;
    /**
     *  暂存终态响应字节引用，消费方遵守响应缓冲租约。
     * 返回暂存终态响应数据引用。
     * 保存可空终态响应字节，不复制数组。
     * @param resultData 要保存或返回的当前字段值，方法本身不复制引用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    byte[] resultData;

    /** 本槽固定的最多两笔普通订单结果及响应租约目标。 */
    private final LaneResultTarget laneResultTarget = new LaneResultTarget();

    /**
     * 非批量命令按操作选择最多两个需要收集结果的订单号，再绑定 Lane 响应目标；批量沿用自己的 OrderBatchPending 目标，不创建第二份结果容器。
     * @param responseArena 非空响应编码需要使用的缓冲 Arena
     */
    void prepareLaneResultTarget(ResponseArena responseArena) {
        if (orderBatch != null) return;
        // 当前操作需要捕获结果的第一个订单号。
        // 替换操作需要捕获的第二个订单号，否则零。
        long first = 0, second = 0;
        switch (operation) {
            case PLACE -> first = decodedCommand.placeOrder().orderId();
            case CANCEL -> first = decodedCommand.cancelOrder().orderId();
            case REPLACE, AMEND -> {
                // 替换/改单已解析准入，含旧订单号与新订单身份。
                ResolvedMatchingAdmission value = admission;
                if (value != null) {
                    first = value.originalOrderId();
                    second = value.command().orderId();
                }
            }
            case TRIGGER -> {
                if (admittedMatchingOrder != null) first = admittedMatchingOrder.orderId();
            }
            default -> { }
        }
        laneResultTarget.bind(first, second, responseArena);
    }

    /**
     * 目标已绑定正订单号时返回 Lane 写入接口，否则为空。
     */
    public com.surprising.aeron.service.order.state.LaneOrderResultTarget laneResultTarget() {
        return laneResultTarget.active() ? laneResultTarget : null;
    }

    /**
     * 从本槽已捕获的结果副本中按订单号查找，不回读 Lane 订单表。
     */
    public OrderRuntime laneResultOrder(long orderId) { return laneResultTarget.order(orderId); }
    /**
     * 从已捕获结果中取品种字符串，未绑定或未捕获时可为空。
     * @param orderId 当前逻辑命令或结果绑定的订单号
     */
    public String laneResultSymbol(long orderId) { return laneResultTarget.instrumentId(orderId); }
    /**
     * 返回 Lane 是否已经完成响应字节准备。
     */
    public boolean laneResultPrepared() { return laneResultTarget.prepared(); }
    /**
     * 返回尚归当前目标持有的响应缓冲引用，不复制数组。
     */
    public byte[] lanePreparedResponse() { return laneResultTarget.preparedResponse(); }
    /**
     * 返回当前响应有效长度，可能小于租用数组容量。
     */
    public int lanePreparedResponseLength() { return laneResultTarget.preparedResponseLength(); }
    /**
     * 把响应租约从本槽目标移交出去，只清本目标租约引用，不归还 Arena 或复制字节。
     */
    public void transferLaneResponseOwnership() { laneResultTarget.transferResponseOwnership(); }

    /**
     * 准备槽内固定的准入等待任务，Matcher 先等 Lane 收据再决定拒绝、先撤或调用原下单任务，避免每次分配捕获 lambda。
     * @param owner 所属交易 Owner 运行时
     * @param admission 对应方法签名的准入事件或已解析准入值，使用期间按租约只读
     * @param matcherShard 调用方确定的目标 Matcher 分片
     * @param settlement 当前准入等待任务要发布的直达结算事件
     * @param original 准入接受且内部先撤完成后继续执行的原下单函数
     */
    java.util.function.Supplier<?> gateAdmission(TradingCoreRuntime owner,
            PlaceAdmissionEvent admission, int matcherShard,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent settlement,
            java.util.function.Supplier<?> original) {
        admissionMatching.prepare(owner, admission, matcherShard, settlement, original);
        return admissionMatching;
    }

    /**
     * 准备本槽普通下单任务，只接受一种已准入订单表示并保留结算事件租约；实际撮合在 Matcher 调用 get 时执行。
     * @param owner 所属交易 Owner 运行时
     * @param matcherShard 调用方确定的目标 Matcher 分片
     * @param userId 请求所属用户编号
     * @param resolvedOrder 已经完成准入的下单对象
     * @param matchingOrder 独立的不可变撮合输入
     * @param direct 本命令独占并尚未退休的直达结算事件
     */
    java.util.function.Supplier<?> preparePlaceMatching(TradingCoreRuntime owner,
            int matcherShard, long userId,
            ResolvedPlaceOrder resolvedOrder, CoreMatchingOrder matchingOrder,
            MatcherSettlementEvent direct) {
        placeMatching.prepare(owner, matcherShard, userId, resolvedOrder, matchingOrder, direct);
        return placeMatching;
    }

    /**
     * 准备顺序批量中已冻结的 OrderRuntime 输入，Matcher 直接消费该项租约，不重建独立撮合 DTO。
     * @param owner 所属交易 Owner 运行时
     * @param matcherShard 调用方确定的目标 Matcher 分片
     * @param userId 请求所属用户编号
     * @param admittedOrder 已经冻结供 Matcher 消费的批量项订单租约
     * @param direct 本命令独占并尚未退休的直达结算事件
     */
    java.util.function.Supplier<?> prepareAdmittedPlaceMatching(TradingCoreRuntime owner,
            int matcherShard, long userId, OrderRuntime admittedOrder, MatcherSettlementEvent direct) {
        placeMatching.prepare(owner, matcherShard, userId, null, null, admittedOrder, direct);
        return placeMatching;
    }

    /**
     * 保存撤单身份及直达结算目标到本槽固定任务，不在 Owner 准备阶段撤订单簿。
     * @param owner 所属交易 Owner 运行时
     * @param matcherShard 调用方确定的目标 Matcher 分片
     * @param orderId 当前逻辑命令或结果绑定的订单号
     * @param userId 请求所属用户编号
     * @param instrumentId 永久品种字符串
     * @param direct 本命令独占并尚未退休的直达结算事件
     */
    java.util.function.Supplier<?> prepareCancelMatching(TradingCoreRuntime owner,
            int matcherShard, long orderId, long userId, String instrumentId, MatcherSettlementEvent direct) {
        cancelMatching.prepare(owner, matcherShard, orderId, userId, instrumentId, direct);
        return cancelMatching;
    }

    /**
     * 保存旧单号、替换准入和直达结算目标，执行时先处理共同的前置撤单，再由适配器执行撤旧下新。
     * @param owner 所属交易 Owner 运行时
     * @param matcherShard 调用方确定的目标 Matcher 分片
     * @param orderId 当前逻辑命令或结果绑定的订单号
     * @param userId 请求所属用户编号
     * @param originalOrderId 替换前要撤销的旧订单号
     * @param instrumentId 永久品种字符串
     * @param replacement 已经准入的新单输入
     * @param direct 本命令独占并尚未退休的直达结算事件
     */
    java.util.function.Supplier<?> prepareReplaceMatching(TradingCoreRuntime owner,
            int matcherShard, long orderId, long userId,
            long originalOrderId, String instrumentId, ResolvedPlaceOrder replacement,
            MatcherSettlementEvent direct) {
        replaceMatching.prepare(owner, matcherShard, orderId, userId,
                originalOrderId, instrumentId, replacement, direct);
        return replaceMatching;
    }

    /**
     * 保留非空前置撤单列表引用，不复制或在这里执行；发布到 Matcher 前后调用方保持内容稳定。
     * @param orders 执行期间保持稳定的前置或控制撤单有序列表
     */
    void matcherPreCancellations(List<CancellationOrder> orders) {
        matcherPreCancellations = Objects.requireNonNull(orders);
    }

    /**
     * 准备按既定顺序执行控制撤单的固定任务，要求 Owner 和列表非空，返回本槽同一个 Supplier。
     * @param owner 所属交易 Owner 运行时
     * @param orderId 当前逻辑命令或结果绑定的订单号
     * @param orders 执行期间保持稳定的前置或控制撤单有序列表
     */
    java.util.function.Supplier<?> prepareControlMatching(TradingCoreRuntime owner,
            List<CancellationOrder> orders) {
        controlMatching.owner = Objects.requireNonNull(owner);
        controlMatching.orders = Objects.requireNonNull(orders);
        return controlMatching;
    }

    /**
     * 按当前列表顺序执行原生前置撤单，接受时记入直达事件；首个非接受结果发布为带逻辑命令身份的失败并返回，全部接受返回空。此路径不跳过 UNKNOWN_ORDER，与下单准入等待任务的内部意图规则不同。
     * @param owner 所属交易 Owner 运行时
     * @param shard 当前分片号或要缓存的静态分片号
     * @param logicalOrderId 用于关联前置撤单失败的外层逻辑订单号
     * @param target 接收原生事实的结算目标或接收提交资金缓冲的目标，具体类型见签名
     */
    private exchange.core2.core.common.MatcherResult applyMatcherPreCancellations(
            TradingCoreRuntime owner, int shard, long logicalOrderId, MatcherSettlementEvent target) {
        for (/* 当前前置撤单或结果数组中的下标。 */ int index = 0; index < matcherPreCancellations.size(); index++) {
            // 当前按既定顺序执行的一条原生撤单身份。
            var cancellation = matcherPreCancellations.get(index);
            // 当前前置撤单的原生回执，用其状态决定记录事实或停止。
            var result = owner.matchingAdapter().cancelDirectPrefix(command.header().submittedAtEpochMillis(),
                    cancellation.userId(), cancellation.orderId(), cancellation.instrumentId());
            // 原生撤单是否返回 SUCCESS 或 ACCEPTED。
            boolean accepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                    || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
            if (accepted) target.recordDirectPreCancellation(cancellation.orderId());
            else return owner.matchingAdapter().publishDirectPrefixFailure(shard, coreSequence,
                    command.header().commandId(), logicalOrderId, result, target);
        }
        return null;
    }

    /**
     * 复用当前命令身份执行有序控制撤单的 Matcher 任务。
     */
    private final class ControlMatchingContinuation implements java.util.function.Supplier<Object> {
        /** 固定撮合任务借用的所属交易运行时。 */
        private TradingCoreRuntime owner;
        /** 控制撤单任务借用的有序请求列表。 */
        private List<CancellationOrder> orders;
        /**
         * 在 Matcher 上执行本槽整组控制撤单，Core 序号、UUID 和业务时间取当前槽命令。
         */
        @Override public Object get() {
            return owner.matchingAdapter().cancelControlOrders(coreSequence, command.header().commandId(),
                    command.header().submittedAtEpochMillis(), orders);
        }
        /**
         * 清掉控制任务借用的 Owner 及列表引用，任务对象继续复用。
         */
        void clear() { owner = null; orders = null; }
    }

    /**
     * 当前还没有控制续步时绑定非空 BooleanSupplier，之后由 Owner 轮询其完成状态。
     * @param work 已存在的非空业务续步工作，具体类型由结算或强平入口决定
     */
    public void deferControl(java.util.function.BooleanSupplier work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        controlWork = Objects.requireNonNull(work);
    }

    /**
     * 返回本槽保留的可复用到期结算工作对象，尚未创建时可为空。
     */
    public com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation.SettlementWork settlementWork() {
        return reusableSettlementWork;
    }

    /**
     * 保存非空可复用结算工作对象，回收时清内部引用而保留对象。
     * @param work 已存在的非空业务续步工作，具体类型由结算或强平入口决定
     */
    public void settlementWork(com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation.SettlementWork work) {
        reusableSettlementWork = Objects.requireNonNull(work);
    }

    /**
     * 要求尚无待控制工作，准备固定到期结算轮询器并把它设为当前 controlWork。
     * @param coordinator 当前有序提交器
     * @param work 已存在的非空业务续步工作，具体类型由结算或强平入口决定
     */
    public void deferSettlementControl(OrderedCommitCoordinator coordinator,
            com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation.SettlementWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        settlementControl.prepare(coordinator, work);
        controlWork = settlementControl;
    }

    /**
     * 懒创建并返回本槽可复用的强平校验工作。
     */
    public com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionCheck liquidationCheck() {
        if (liquidationCheck == null)
            liquidationCheck = new com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionCheck();
        return liquidationCheck;
    }

    /**
     * 返回已保留的强平执行工作，未设置时为空。
     */
    public com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionWork liquidationWork() {
        return reusableLiquidationWork;
    }

    /**
     * 保存非空强平执行工作，后续命令复用同一工作对象。
     * @param work 已存在的非空业务续步工作，具体类型由结算或强平入口决定
     */
    public void liquidationWork(com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionWork work) {
        reusableLiquidationWork = Objects.requireNonNull(work);
    }

    /**
     * 当前无待控制工作时，绑定强平执行工作及最终进度，由固定 Owner 回调轮询，不在此重新执行资金业务。
     * @param coordinator 当前有序提交器
     * @param work 已存在的非空业务续步工作，具体类型由结算或强平入口决定
     * @param progress 强平工作完成后要返回的进度值
     */
    public void deferLiquidationExecutionControl(OrderedCommitCoordinator coordinator,
            com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionWork work,
            com.surprising.aeron.protocol.CoreLiquidationProgressView progress) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        liquidationExecutionControl.prepare(coordinator, work, progress);
        controlWork = liquidationExecutionControl;
    }

    /**
     * 自动计算命令指纹，使用空前置撤单列表并解码后初始化。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param fundsDelta 初始资金事实增量值
     */
    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), List.of(), fundsDelta);
    }

    /**
     * 保留传入指纹，使用空前置撤单列表并解码后初始化。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param fingerprint 本次原命令指纹
     * @param fundsDelta 初始资金事实增量值
     */
    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, fingerprint, List.of(), fundsDelta);
    }

    /**
     * 自动计算指纹和解码值，保留指定先撤订单后初始化。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param preMatchingCancellationOrderIds 准入确定的需要先撤的订单号列表
     * @param fundsDelta 初始资金事实增量值
     */
    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                fundsDelta,
                DecodedMatchingCommand.decode(command));
    }

    /**
     * 使用传入指纹与先撤列表，自动解码命令后初始化。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param fingerprint 本次原命令指纹
     * @param preMatchingCancellationOrderIds 准入确定的需要先撤的订单号列表
     * @param fundsDelta 初始资金事实增量值
     */
    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                fundsDelta,
                DecodedMatchingCommand.decode(command));
    }

    /**
     * 使用已解码命令并计算指纹，准入结果留空后初始化。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param preMatchingCancellationOrderIds 准入确定的需要先撤的订单号列表
     * @param fundsDelta 初始资金事实增量值
     * @param decodedCommand 当前命令已经解码的输入
     */
    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, null);
    }

    /**
     * 使用已传入的指纹、解码值及先撤列表，准入结果留空。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param fingerprint 本次原命令指纹
     * @param preMatchingCancellationOrderIds 准入确定的需要先撤的订单号列表
     * @param fundsDelta 初始资金事实增量值
     * @param decodedCommand 当前命令已经解码的输入
     */
    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, null);
    }

    /**
     * 自动计算指纹，保留已解码值与可空准入结果后初始化。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param preMatchingCancellationOrderIds 准入确定的需要先撤的订单号列表
     * @param fundsDelta 初始资金事实增量值
     * @param decodedCommand 当前命令已经解码的输入
     * @param admission 对应方法签名的准入事件或已解析准入值，使用期间按租约只读
     */
    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, admission);
    }

    /**
     * 把完整输入交给 initialize 校验并建立此次槽生命周期。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param fingerprint 本次原命令指纹
     * @param preMatchingCancellationOrderIds 准入确定的需要先撤的订单号列表
     * @param fundsDelta 初始资金事实增量值
     * @param decodedCommand 当前命令已经解码的输入
     * @param admission 对应方法签名的准入事件或已解析准入值，使用期间按租约只读
     */
    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        initialize(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                fundsDelta, decodedCommand, admission);
    }

    /**
     * 要求正序号、命令类消息和必需输入非空，清旧生命周期后绑定命令、指纹、解码、先撤列表及资金增量，重置本次提交边界和业务阶段。保留槽内可复用任务对象，不是复制一份账户状态。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param fingerprint 本次原命令指纹
     * @param preMatchingCancellationOrderIds 准入确定的需要先撤的订单号列表
     * @param fundsDelta 初始资金事实增量值
     * @param decodedCommand 当前命令已经解码的输入
     * @param admission 对应方法签名的准入事件或已解析准入值，使用期间按租约只读
     */
    CommandSlot initialize(long sequence, Operation operation, CoreMessage command,
                               CommandFingerprint fingerprint, List<Long> preMatchingCancellationOrderIds,
                               RuntimeFundsDelta fundsDelta,
                               DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        if (sequence <= 0 || operation == null || command == null || preMatchingCancellationOrderIds == null
                || fingerprint == null || fundsDelta == null || decodedCommand == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
        clearLifecycleState();
        this.coreSequence = sequence;
        claimed = true;
        orderBatch = null;
        this.operation = operation;
        this.command = command;
        this.fingerprint = fingerprint;
        this.preMatchingCancellationOrderIds = retainCancellationIds(preMatchingCancellationOrderIds);
        fundsAccumulator.clear();
        fundsAccumulator.add(fundsDelta);
        this.decodedCommand = decodedCommand;
        this.admission = admission;
        commitFenceTimestamp = 0;
        commitFenceClusterPosition = 0;
        commitFenceEstablished = false;
        settlementContinuation = null;
        clearPlaceReferences();
        replacementAdmission = null;
        settlementPlan = null;
        admittedMatchingOrder = null;
        triggerAdmissionOrder = null;
        sourceTrigger = null;
        matchingLifecycle = MATCHING_ADMITTED;
        clearDeferredMatching();
        crossShardCancellationStarted = false;
        clusterIndependent = false;
        ledgerDispatchClosed = false;
        realtimeTakerOrder = null;
        pipelinedSettlementCounted = false;
        cachedMatcherShard = -1;
        return this;
    }

    /**
     * 更新先撤订单号列表并返回同一槽，复用不可变 primitive 列表，其余按规则复制。
     * @param orderIds 需要保留的先撤订单号列表，空值按空列表处理
     */
    CommandSlot withPreMatchingCancellations(List<Long> orderIds) {
        preMatchingCancellationOrderIds = retainCancellationIds(orderIds);
        return this;
    }

    /**
     * 先以空资金增量初始化，再将非空来源累加器的内容移入槽内空缓冲；来源为空也允许，不再重复合并逐账户 posting。
     * @param sequence 本次完整的正 Core 命令序号
     * @param operation 本槽承载的业务操作类别
     * @param command 本次完整 Core 命令消息
     * @param fingerprint 本次原命令指纹
     * @param preMatchingCancellationOrderIds 准入确定的需要先撤的订单号列表
     * @param sourceFunds 可空的来源累加器，非空时转交到槽内空缓冲
     * @param decodedCommand 当前命令已经解码的输入
     * @param admission 对应方法签名的准入事件或已解析准入值，使用期间按租约只读
     */
    CommandSlot initialize(long sequence, Operation operation, CoreMessage command,
                           CommandFingerprint fingerprint, List<Long> preMatchingCancellationOrderIds,
                           RuntimeFundsAccumulator sourceFunds,
                           DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        initialize(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                RuntimeFundsDelta.empty(), decodedCommand, admission);
        if (sourceFunds != null) sourceFunds.transferToEmpty(fundsAccumulator);
        return this;
    }

    /**
     * 空引用或空集合变为空列表，ImmutableLongArrayList 直接借用，其他列表用 List.copyOf；不复制 primitive 不可变存储。
     * @param orderIds 需要保留的先撤订单号列表，空值按空列表处理
     */
    private static List<Long> retainCancellationIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return List.of();
        if (orderIds instanceof ImmutableLongArrayList) {
            return orderIds;
        }
        return List.copyOf(orderIds);
    }

    /**
     * 替换可空的解析准入结果并返回同一槽，不重做准入或读取账户。
     * @param nextAdmission 准备替换的可空解析准入结果
     */
    CommandSlot withAdmission(ResolvedMatchingAdmission nextAdmission) {
        admission = nextAdmission;
        return this;
    }

    /**
     * 要求时间与日志位置非负，只在首次保存提交边界；后续合法调用直接返回，不比较是否与首次值相同。
     * @param clusterTimestamp 要固定或暂存的 Cluster 业务时间
     * @param clusterPosition 要固定或暂存的 Cluster 日志位置
     */
    public void establishCommitFence(long clusterTimestamp, long clusterPosition) {
        if (clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("matching commit fence cannot be negative");
        }
        if (commitFenceEstablished) return;
        commitFenceTimestamp = clusterTimestamp;
        commitFenceClusterPosition = clusterPosition;
        commitFenceEstablished = true;
    }

    /**
     * 返回保留的 Core 序号，槽已回收后也可能仍保留旧值；是否占用另看 claimed。
     */
    public long sequence() { return coreSequence; }
    /**
     * 返回可空的改撤等已解析准入信息。
     */
    public ResolvedMatchingAdmission matchingAdmission() { return admission; }
    /**
     * 返回替换操作绑定的 Lane 准入事件。
     */
    com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent replacementAdmission() { return replacementAdmission; }
    /**
     * 只允许 AMEND/REPLACE 首次绑定非空替换准入事件，重复或操作不符拒绝。
     * @param event 要绑定或转交的准入、结算或提交事件，具体约束见当前入口
     */
    void replacementAdmission(com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent event) {
        if (event == null || replacementAdmission != null || operation != Operation.AMEND && operation != Operation.REPLACE)
            throw new IllegalStateException("invalid replacement admission slot");
        replacementAdmission = event;
    }
    /**
     * 取走替换准入事件并清该引用，把后续使用责任交给调用方。
     */
    com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent takeReplacementAdmission() {
        // 当前方法准备取走并清引用的原值。
        var value = replacementAdmission; replacementAdmission = null; return value;
    }
    /**
     * 返回此命令保留的可空结算事件。
     */
    public com.surprising.aeron.service.settlement.MatcherSettlementEvent settlementEvent() {
        return settlementContinuation;
    }
    /**
     * 取出并断开结算事件引用，不在此完成结算或回收事件。
     */
    public com.surprising.aeron.service.settlement.MatcherSettlementEvent takeSettlementEvent() {
        // 当前方法准备取走并清引用的原值。
        var value = settlementEvent();
        settlementContinuation = null;
        return value;
    }
    /**
     * 返回与当前事件配套的结算计划引用。
     */
    public com.surprising.aeron.service.settlement.MatcherSettlementPlan settlementPlan() { return settlementPlan; }
    /**
     * 返回普通下单的 Lane 准入事件。
     */
    public PlaceAdmissionEvent placeAdmission() {
        return placeAdmission;
    }
    /**
     * 返回是否仍保留结算事件引用。
     */
    public boolean hasLaneContinuation() {
        return settlementContinuation != null;
    }
    /**
     * 有结算事件且事件自身已完成时返回真，没有事件时返回假。
     */
    public boolean laneContinuationComplete() {
        if (!hasLaneContinuation()) return false;
        return settlementContinuation.complete();
    }
    /**
     * 取出普通下单准入事件并清引用。
     */
    PlaceAdmissionEvent takePlaceAdmission() {
        // 当前方法准备取走并清引用的原值。
        PlaceAdmissionEvent value = placeAdmission;
        placeAdmission = null;
        return value;
    }
    /**
     * 只允许 PLACE 首次绑定非空准入事件，其他操作或重复绑定拒绝。
     * @param event 要绑定或转交的准入、结算或提交事件，具体约束见当前入口
     */
    public void placeAdmission(PlaceAdmissionEvent event) {
        if (event == null || placeAdmission != null || operation != Operation.PLACE) {
            throw new IllegalStateException("invalid place admission continuation");
        }
        placeAdmission = event;
    }
    /**
     * 返回 Owner 是否已收集本次下单准入收据。
     */
    boolean placeAdmissionOwnerCollected() { return placeAdmissionOwnerCollected; }
    /**
     * 标记下单准入已由 Owner 收集，不在此重新读 Lane。
     */
    void markPlaceAdmissionOwnerCollected() { placeAdmissionOwnerCollected = true; }
    /**
     * 返回为实时推送保留的准入订单引用。
     */
    public ResolvedPlaceOrder realtimeResolvedTakerOrder() { return realtimeResolvedTakerOrder; }
    /**
     * 保存可空实时准入订单引用，不复制对象。
     * @param order 需要保存的准入订单或捕获的可空结果订单，按当前接口语义使用
     */
    public void realtimeResolvedTakerOrder(ResolvedPlaceOrder order) { realtimeResolvedTakerOrder = order; }
    /**
     * 返回触发续步已经冻结的源触发单收据，避免再回读 Lane 客户端索引。
     */
    public com.surprising.aeron.model.CoreTriggerOrderState sourceTrigger() { return sourceTrigger; }
    /**
     * 首次绑定非空源触发单收据，重复绑定立即拒绝。
     * @param value 要保存或返回的当前字段值，方法本身不复制引用
     */
    public void sourceTrigger(com.surprising.aeron.model.CoreTriggerOrderState value) {
        if (value == null || sourceTrigger != null) throw new IllegalStateException("trigger receipt already bound");
        sourceTrigger = value;
    }
    /**
     * 返回触发子单已经完成准入的订单引用。
     */
    public OrderRuntime triggerAdmissionOrder() { return triggerAdmissionOrder; }
    /**
     * 只允许 TRIGGER 且尚无独立撮合输入时，保存非空冻结订单并用其剩余数量、撮合价和产品滑点生成 CoreMatchingOrder。
     * @param order 需要保存的准入订单或捕获的可空结果订单，按当前接口语义使用
     */
    void triggerAdmission(OrderRuntime order) {
        if (operation != Operation.TRIGGER || order == null || admittedMatchingOrder != null)
            throw new IllegalStateException("invalid trigger admission");
        triggerAdmissionOrder = order;
        admittedMatchingOrder = new CoreMatchingOrder(order.orderId(), order.instrument().instrumentIdValue(),
                order.side(), order.orderType(), order.timeInForce(), order.matchingPriceTicks(),
                order.remainingQuantitySteps(), CoreMatchingOrder.slippagePpm(order.productLine()));
    }
    /**
     * 按阶段标签判断是否已到提交 Matcher 或更后阶段，不探查实际传输队列。
     */
    public boolean isMatchingSubmitted() { return matchingLifecycle >= MATCHING_SUBMITTED; }

    /**
     * 把紧凑阶段值转成可读诊断名，未知值返回 UNKNOWN；真正完成判断另看结果与 Lane 掩码。
     */
    public String matchingLifecycleName() {
        return switch (matchingLifecycle) {
            case MATCHING_ADMITTED -> "ADMITTED";
            case MATCHING_DEFERRED -> "DEFERRED";
            case MATCHING_SUBMITTED -> "SUBMITTED";
            case MATCHER_DONE -> "MATCHER_DONE";
            case LANES_DONE -> "LANES_DONE";
            case COMMITTED -> "COMMITTED";
            default -> "UNKNOWN";
        };
    }
    /**
     * 登记首次 Matcher 提交；若结果阶段已先发布则保持更后阶段，否则重复提交拒绝。此处不真的向 Matcher 队列投递。
     */
    public void matchingSubmitted() {
        // 跨分片控制结果可能先于提交游标释放而可见；已进入结果阶段时，
        // 保留更后阶段即可，无需再把生命周期退回提交态。
        if (matchingLifecycle >= MATCHER_DONE) return;
        if (isMatchingSubmitted()) throw new IllegalStateException("matching command was submitted twice");
        matchingLifecycle = MATCHING_SUBMITTED;
    }

    /**
     * 要求事件和计划非空且尚无事件续步，再绑定二者，账户应用由后续流程执行。
     * @param event 要绑定或转交的准入、结算或提交事件，具体约束见当前入口
     * @param plan 与事件配套的非空结算计划
     */
    public void settlement(com.surprising.aeron.service.settlement.MatcherSettlementEvent event,
                    com.surprising.aeron.service.settlement.MatcherSettlementPlan plan) {
        if (event == null || plan == null || hasLaneContinuation()) {
            throw new IllegalStateException("invalid matcher settlement continuation");
        }
        settlementContinuation = event;
        settlementPlan = plan;
    }

    /**
     * 清普通业务引用、子上下文、资金缓冲与 Core 序号，并清可复用准入订单内容；不替代槽池的 claimed 和完成状态回收流程。
     */
    void clearReferences() {
        coreSequence = 0;
        orderBatch = null;
        operation = null;
        command = null;
        fingerprint = null;
        preMatchingCancellationOrderIds = List.of();
        fundsAccumulator.clear();
        decodedCommand = null;
        admission = null;
        settlementContinuation = null;
        clearPlaceReferences();
        replacementAdmission = null;
        settlementPlan = null;
        admittedMatchingOrder = null;
        triggerAdmissionOrder = null;
        sourceTrigger = null;
        clearLiquidationReferences();
        realtimeTakerOrder = null;
        matchingLifecycle = MATCHING_ADMITTED;
        clearDeferredMatching();
        cachedMatcherShard = -1;
    }

    /**
     * 只允许从 ADMITTED 转为 DEFERRED，保存日志时间、位置和非空来源键，等待已有批量边界先完成。
     * @param clusterTimestamp 要固定或暂存的 Cluster 业务时间
     * @param clusterPosition 要固定或暂存的 Cluster 日志位置
     * @param sourceKey 延后执行前保留的来源类型与实例键
     */
    void deferMatching(long clusterTimestamp, long clusterPosition, TradingCoreRuntime.SourceKey sourceKey) {
        if (matchingLifecycle != MATCHING_ADMITTED || sourceKey == null) {
            throw new IllegalStateException("invalid deferred matching transition");
        }
        deferredClusterTimestamp = clusterTimestamp;
        deferredClusterPosition = clusterPosition;
        deferredSourceKey = sourceKey;
        matchingLifecycle = MATCHING_DEFERRED;
    }
    /**
     * 只允许从 DEFERRED 回到 ADMITTED，同时清延后元数据，再由外层重新提交。
     */
    void activateDeferredMatching() {
        if (matchingLifecycle != MATCHING_DEFERRED) {
            throw new IllegalStateException("matching command is not deferred");
        }
        matchingLifecycle = MATCHING_ADMITTED;
        clearDeferredMatching();
    }
    /**
     * 返回当前阶段是否恰好为延后撮合。
     */
    boolean deferredMatching() { return matchingLifecycle == MATCHING_DEFERRED; }
    /**
     * 直接标记 COMMITTED，调用方先完成业务提交条件，本方法不重新核验。
     */
    void committed() {
        matchingLifecycle = COMMITTED;
    }
    /**
     * 要求仍处于延后阶段后返回原 Cluster 时间。
     */
    long deferredClusterTimestamp() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return deferredClusterTimestamp;
    }
    /**
     * 要求仍处于延后阶段后返回原日志位置。
     */
    long deferredClusterPosition() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return deferredClusterPosition;
    }
    /**
     * 要求延后阶段后返回原来源键。
     */
    TradingCoreRuntime.SourceKey deferredSourceKey() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return deferredSourceKey;
    }

    /**
     * 要求分片非负后保存，不在此检查拓扑上界。
     * @param shard 当前分片号或要缓存的静态分片号
     */
    void cachedMatcherShard(int shard) {
        if (shard < 0) throw new IllegalArgumentException("matcher shard cannot be negative");
        cachedMatcherShard = shard;
    }

    /**
     * 标记本命令计入流水结算统计，重复调用只是再次置真。
     */
    void countPipelinedSettlement() { pipelinedSettlementCounted = true; }
    /**
     * 读出并清掉已统计标记，供外层仅对已计数命令做一次扣减。
     */
    public boolean takePipelinedSettlementCounted() {
        // 读取后立即清掉的已计入流水统计标记。
        boolean value = pipelinedSettlementCounted;
        pipelinedSettlementCounted = false;
        return value;
    }
    /**
     * 要求边界已建立后返回固定业务时间。
     */
    public long commitFenceTimestamp() {
        if (!commitFenceEstablished) throw new IllegalStateException("matching commit fence is not established");
        return commitFenceTimestamp;
    }
    /**
     * 要求边界已建立后返回固定日志位置。
     */
    public long commitFenceClusterPosition() {
        if (!commitFenceEstablished) throw new IllegalStateException("matching commit fence is not established");
        return commitFenceClusterPosition;
    }

    /**
     * 命令槽支持的业务动作类别，不混用为产品线或 Matcher 结果类型。
     */
    public enum Operation {
        /**
         * 普通下单。
         */
        PLACE,
        /**
         * 普通撤单。
         */
        CANCEL,
        /**
         * 撤旧下新的订单替换。
         */
        REPLACE,
        /**
         * 改单请求，经准入后按替换路径撮合。
         */
        AMEND,
        /**
         * 触发条件达到后的子单。
         */
        TRIGGER,
        /**
         * 单笔强平执行控制。
         */
        LIQUIDATION,
        /**
         * 强平批次执行控制。
         */
        LIQUIDATION_BATCH,
        /**
         * 交割或行权等到期结算控制。
         */
        SETTLEMENT
    }

    /** 最近初始化的完整 Core 序号，空闲期间可能保留旧值。 */
    long coreSequence;
    /** 底层环是否仍占用此槽，是允许复用的关键标记。 */
    boolean claimed;
    /**  当前事实预计参与的账户 Lane 位集合。 返回此结果预计参与的账户 Lane 位集合。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long expectedLaneMask;
    /**  Owner 已收集的账户 Lane 完成位集合。 返回已经由 Owner 收集确认的 Lane 位集合。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long completedLaneMask;
    /** Matcher 用 volatile 发布、Owner 单次收取的普通结果引用。 */
    private volatile CoreMatchingResult completedMatchingResult;
    /**  已经绑定到 Lane 扇出阶段的事实引用，可为池化结算事件。 返回已经进入 Lane 扇出的撮合事实引用，可能是池化直达事件。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private MatchingResult matchingResult;
    /**  Owner 绑定而 Matcher 发布前释放的提交分片路由，负一表示无路由。 返回当前占用的 Matcher 提交路由，负一表示未占用或已释放。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile int submittedMatcherShard = -1;

    /**
     * 要求槽仍被占用、分片非负、没有现有路由或未收集结果，再由 Owner 绑定提交分片；不检查拓扑上界。
     * @param shardId 声明占用或释放的 Matcher 分片号
     */
    public void claimMatcherSubmission(int shardId) {
        if (!claimed || shardId < 0 || submittedMatcherShard != -1 || completedMatchingResult != null)
            throw new IllegalStateException("matcher command token is already routed or inactive");
        submittedMatcherShard = shardId;
    }

    /**
     * 要求释放分片与当前路由相同，再置为负一；普通发布由 Matcher 在写可见结果之前调用。
     * @param shardId 声明占用或释放的 Matcher 分片号
     */
    @Override
    public void releaseMatcherSubmission(int shardId) {
        if (submittedMatcherShard != shardId) {
            throw new IllegalStateException("matcher completion belongs to another shard");
        }
        submittedMatcherShard = -1;
    }

    /** 续步挂起时从结果构建器交换进来的 primitive 用户变更缓冲。 */
    private PrimitiveLongChangeSet commitChangedUserIds = new PrimitiveLongChangeSet();
    /** 续步挂起时交换进来的 primitive 订单变更缓冲。 */
    private PrimitiveLongChangeSet commitChangedOrderIds = new PrimitiveLongChangeSet();
    /** 是否保存了待恢复的提交输入。 */
    private boolean commitContextActive;
    /** 挂起提交期间移交到本槽的资金事实缓冲，不重复累加同一 posting。 */
    private final com.surprising.aeron.service.treasury.RuntimeFundsAccumulator commitFundsAccumulator =
            new com.surprising.aeron.service.treasury.RuntimeFundsAccumulator(32);
    /** 挂起上下文中是否需要发布快照变化。 */
    private boolean commitSnapshotDirty;
    /** 挂起变化是否只有临时快照内容。 */
    private boolean commitSnapshotProvisionalOnly;
    /** 首次记录的可空撮合拒绝结果码。 */
    private CoreResultCode matchingRejection;

    /**
     * 只有 claimed 时返回 Core 序号，否则返回零，避免内部链接把空闲槽当作有效命令。
     */
    long coreSequence() { return claimed ? coreSequence : 0; }
    /**
     * 要求非空结果匹配本槽 Core 序号且没有未收集结果，先释放 Matcher 路由再用 volatile 结果字段发布；后续 Owner 可直接读取，不能当作多写者 CAS 容器。
     * @param shardId 声明占用或释放的 Matcher 分片号
     * @param result 当前命令的撮合事实，必须关联同一 Core 序号
     */
    public void publishMatcherResult(int shardId, CoreMatchingResult result) {
        if (result == null || result.nativeCoreSequence() != coreSequence
                || completedMatchingResult != null) {
            throw new IllegalStateException("invalid matcher result publication");
        }
        releaseMatcherSubmission(shardId);
        completedMatchingResult = result;
    }

    /**
     * 校验结果与 Core 序号，只在当前完成字段为空时保存；已有结果不覆盖，也不在此释放 Matcher 路由。
     * @param result 当前命令的撮合事实，必须关联同一 Core 序号
     */
    void publishMatchingCompletion(CoreMatchingResult result) {
        if (result == null || result.nativeCoreSequence() != coreSequence) {
            throw new IllegalStateException("invalid matching completion");
        }
        if (completedMatchingResult == null) completedMatchingResult = result;
    }

    /**
     * 取出可见普通结果，只有确实读到非空时才清字段，避免空探查擦掉随后到来的发布；返回引用仍受命令生命周期约束。
     */
    public CoreMatchingResult takeMatchingCompletion() {
        // Owner 本次实际观察到的普通完成引用，非空时才清共享字段。
        CoreMatchingResult result = completedMatchingResult;
        // 空探查不写回 null，避免擦掉随后到来的 Matcher 发布。
        if (result != null) completedMatchingResult = null;
        return result;
    }

    /**
     * 只探查当前普通完成引用，不消费。
     */
    public CoreMatchingResult matchingCompletion() { return completedMatchingResult; }
    /**
     * 返回是否有普通 Matcher 完成引用。
     */
    public boolean hasMatchingCompletion() { return completedMatchingResult != null; }
    /**
     * 当前没有挂起上下文时，把 Owner 结果构建器的 primitive 用户/订单变更缓冲与本槽空缓冲交换，并转交资金累加器及快照标记，供续步让出执行后恢复；不物化 boxed 列表。
     * @param resultBuilder Owner 当前使用的结果与变更集合构建器
     * @param fundsAccumulator 可空的 Owner 提交资金缓冲，挂起时转交到槽内
     * @param snapshotDirty 挂起时保存的快照是否有待发布变化
     * @param snapshotProvisionalOnly 挂起时变化是否仅含临时快照
     */
    public void suspendCommitContext(CommandResultBuilder resultBuilder,
                              com.surprising.aeron.service.treasury.RuntimeFundsAccumulator fundsAccumulator,
                              boolean snapshotDirty, boolean snapshotProvisionalOnly) {
        if (resultBuilder == null || commitContextActive) {
            throw new IllegalStateException("invalid suspended sequence commit context");
        }
        // 本槽原有备用用户变更缓冲，交换给 Owner 继续使用。
        PrimitiveLongChangeSet reusableUserIds = commitChangedUserIds;
        // 本槽原有备用订单变更缓冲。
        PrimitiveLongChangeSet reusableOrderIds = commitChangedOrderIds;
        commitChangedUserIds = resultBuilder.changedUserIds();
        commitChangedOrderIds = resultBuilder.changedOrderIds();
        resultBuilder.changedUserIds(reusableUserIds);
        resultBuilder.changedOrderIds(reusableOrderIds);
        commitFundsAccumulator.clear();
        if (fundsAccumulator != null) fundsAccumulator.transferToEmpty(commitFundsAccumulator);
        commitSnapshotDirty = snapshotDirty;
        commitSnapshotProvisionalOnly = snapshotProvisionalOnly;
        commitContextActive = true;
    }

    /**
     * 要求已挂起上下文，把保存的 primitive 变更缓冲交回结果构建器，并收回构建器现有缓冲；本方法不自动清 commitContextActive，资金和快照标记另行处理。
     * @param resultBuilder Owner 当前使用的结果与变更集合构建器
     */
    public void restoreCommitContext(CommandResultBuilder resultBuilder) {
        requireCommit();
        if (resultBuilder == null) throw new IllegalStateException("result builder is required");
        // 挂起前从 Owner 移入、本次要交还的用户变更缓冲。
        PrimitiveLongChangeSet committedUserIds = commitChangedUserIds;
        // 挂起前移入、本次要交还的订单变更缓冲。
        PrimitiveLongChangeSet committedOrderIds = commitChangedOrderIds;
        commitChangedUserIds = resultBuilder.changedUserIds();
        commitChangedOrderIds = resultBuilder.changedOrderIds();
        resultBuilder.changedUserIds(committedUserIds);
        resultBuilder.changedOrderIds(committedOrderIds);
    }

    /**
     * 要求提交上下文活跃后返回保存的用户变更缓冲。
     */
    PrimitiveLongChangeSet commitChangedUserIds() { return requiredCommit(commitChangedUserIds); }
    /**
     * 要求提交上下文活跃后返回保存的订单变更缓冲。
     */
    PrimitiveLongChangeSet commitChangedOrderIds() { return requiredCommit(commitChangedOrderIds); }
    /**
     * 要求提交上下文活跃，目标非空时先清目标再转交本槽资金缓冲；目标为空不做转交，不重复合并逐账户 posting。
     * @param target 接收原生事实的结算目标或接收提交资金缓冲的目标，具体类型见签名
     */
    public void takeCommitFundsTo(com.surprising.aeron.service.treasury.RuntimeFundsAccumulator target) {
        requireCommit();
        if (target != null) { target.clear(); commitFundsAccumulator.transferToEmpty(target); }
    }
    /**
     * 要求上下文活跃后返回是否需要发布快照变化。
     */
    public boolean commitSnapshotDirty() { requireCommit(); return commitSnapshotDirty; }
    /**
     * 要求上下文活跃后返回是否只有临时快照变化。
     */
    public boolean commitSnapshotProvisionalOnly() { requireCommit(); return commitSnapshotProvisionalOnly; }

    /**
     * 要求上下文活跃，清掉变更集合、资金和快照标记，再标记不再挂起；缓冲对象留待复用。
     */
    public void clearCommitContext() {
        requireCommit();
        commitChangedUserIds.clear();
        commitChangedOrderIds.clear();
        commitFundsAccumulator.clear();
        commitSnapshotDirty = false;
        commitSnapshotProvisionalOnly = false;
        commitContextActive = false;
    }

    /**
     * 缺少已挂起上下文时拒绝访问提交输入。
     */
    private void requireCommit() {
        if (!commitContextActive) throw new IllegalStateException("sequence commit context is missing");
    }

    /**
     * 先检查提交上下文存在，再原样返回给定字段。
     * @param <T> 被提交上下文保护的返回值类型
     * @param value 已挂起上下文保护的字段值，检查通过后原样返回
     */
    private <T> T requiredCommit(T value) {
        requireCommit();
        return value;
    }

    /**
     * 返回是否正在保留挂起提交输入。
     */
    public boolean hasCommitContext() { return commitContextActive; }

    /**
     * 只允许首次保存非空撮合拒绝原因，不覆盖既有原因。
     * @param resultCode 首次登记的非空撮合拒绝原因
     */
    void rejectMatching(CoreResultCode resultCode) {
        if (resultCode == null || matchingRejection != null) {
            throw new IllegalStateException("invalid matching rejection");
        }
        matchingRejection = resultCode;
    }

    /**
     * 返回是否已保存拒绝原因。
     */
    public boolean hasMatchingRejection() { return matchingRejection != null; }

    /**
     * 要求拒绝原因已存在后返回原结果码。
     */
    public CoreResultCode matchingRejection() {
        if (matchingRejection == null) throw new IllegalStateException("matching rejection is missing");
        return matchingRejection;
    }

    /**
     * 首次把匹配 Core 序号的事实与合法参与 Lane 位集合绑定，阶段置 MATCHER_DONE；不立即应用账户，零预期 Lane 时 complete 已可为真。
     * @param result 当前命令的撮合事实，必须关联同一 Core 序号
     * @param expectedMask 当前事实要求参与的账户 Lane 位集合
     * @param validLaneMask 拓扑允许的 Lane 位集合，用于拒绝越界参与位
     */
    public void result(MatchingResult result, long expectedMask, long validLaneMask) {
        if (result == null || result.nativeCoreSequence() != coreSequence
                || (expectedMask & ~validLaneMask) != 0
                || matchingResult != null) {
            throw new IllegalStateException("invalid immutable matching result fanout");
        }
        matchingResult = result;
        expectedLaneMask = expectedMask;
        matchingLifecycle = MATCHER_DONE;
    }

    /**
     * 只清待收集的普通完成字段，不清 Lane 扇出结果或整个命令状态。
     */
    void resetMatchingContinuation() { completedMatchingResult = null; }

    /**
     * 要求已经绑定撮合事实且尚未收集任一 Lane，再把合法控制参与位并入预期集合。
     * @param laneMask 要追加或报告完成的账户 Lane 位集合
     * @param validLaneMask 拓扑允许的 Lane 位集合，用于拒绝越界参与位
     */
    public void includeControlLanes(long laneMask, long validLaneMask) {
        if (matchingResult == null || completedLaneMask != 0 || (laneMask & ~validLaneMask) != 0) {
            throw new IllegalStateException("invalid synchronous control lane participants");
        }
        expectedLaneMask |= laneMask;
    }

    /**
     * 要求存在事实、完成位是预期子集且无重复位，再合并完成位；全部匹配时标 LANES_DONE。零位集合允许传入，真正幂等边界由调用流程遵守。
     * @param laneMask 要追加或报告完成的账户 Lane 位集合
     */
    public void completeLanes(long laneMask) {
        if (matchingResult == null
                || (laneMask & ~expectedLaneMask) != 0
                || (completedLaneMask & laneMask) != 0) {
            throw new IllegalStateException("duplicate or unexpected account lane completion"
                    + " laneMask=" + laneMask + " expected=" + expectedLaneMask
                    + " completed=" + completedLaneMask);
        }
        completedLaneMask |= laneMask;
        if (matchingResult != null && completedLaneMask == expectedLaneMask) {
            matchingLifecycle = LANES_DONE;
        }
    }

    /**
     * 仅当已绑定事实且完成位等于预期位时为真，不单靠阶段标签。
     */
    public boolean complete() {
        return matchingResult != null && completedLaneMask == expectedLaneMask;
    }

    /**
     * 只清生命周期、回调和提交缓冲，保留部分业务引用与原序号直到 initialize 覆盖；它与 clearReferences 的清理范围不同。
     */
    void clear() {
        // 后续 initialize 会覆盖本次业务输入，这里只清生命周期。
        // 空闲期间保留部分引用，不能把 clear 当作彻底解除所有引用。
        clearLifecycleState();
    }

    /**
     * 解除当前任务借用引用、结果和路由，清提交上下文、Lane 结果目标并把 claimed 置假；不清 coreSequence、command、operation 等全部业务引用。
     */
    private void clearLifecycleState() {
        clearMatchingControlContinuations();
        if (reusableSettlementWork != null) reusableSettlementWork.clearReferences();
        clearLiquidationReferences();
        controlWork = null;
        controlPending = false;
        controlAccountsCommitted = false;
        commitEvent = null;
        resultData = null;
        claimed = false;
        expectedLaneMask = 0;
        completedLaneMask = 0;
        completedMatchingResult = null;
        matchingResult = null;
        submittedMatcherShard = -1;
        commitChangedUserIds.clear();
        commitChangedOrderIds.clear();
        commitFundsAccumulator.clear();
        commitSnapshotDirty = false;
        commitSnapshotProvisionalOnly = false;
        commitContextActive = false;
        matchingRejection = null;
        clearPlaceReferences();
        replacementAdmission = null;
        matchingLifecycle = MATCHING_ADMITTED;
        clearDeferredMatching();
        cachedMatcherShard = -1;
        laneResultTarget.clear();
    }

    /**
     * 清各固定 Matcher/控制任务持有的可变输入，并断开共同前置撤单列表，任务对象自身留在槽内复用。
     */
    private void clearMatchingControlContinuations() {
        admissionMatching.clear();
        settlementControl.clear();
        liquidationExecutionControl.clear();
        placeMatching.clear();
        cancelMatching.clear();
        replaceMatching.clear();
        controlMatching.clear();
        matcherPreCancellations = List.of();
    }

    /**
     * 在 Matcher 上等待 Lane 下单准入收据并处理内部先撤意图的固定任务。
     */
    private final class AdmissionMatchingContinuation implements java.util.function.Supplier<Object> {
        /** 固定撮合任务借用的所属交易运行时。 */
        private TradingCoreRuntime owner;
        /** Matcher 需要等待的 Lane 下单准入事件。 */
        private PlaceAdmissionEvent admission;
        /** 固定任务负责的全局 Matcher 分片。 */
        private int matcherShard;
        /** 等待准入后要发布到的直达结算事件。 */
        private MatcherSettlementEvent settlement;
        /** 准入与先撤成功后才调用的原始下单 Supplier。 */
        private java.util.function.Supplier<?> original;

        /**
         * 要求 Owner、准入事件、结算目标及原任务非空后保存，分片号沿用调用方已确定值。
         * @param owner 所属交易 Owner 运行时
         * @param admission 对应方法签名的准入事件或已解析准入值，使用期间按租约只读
         * @param matcherShard 调用方确定的目标 Matcher 分片
         * @param settlement 当前准入等待任务要发布的直达结算事件
         * @param original 准入接受且内部先撤完成后继续执行的原下单函数
         */
        void prepare(TradingCoreRuntime owner, PlaceAdmissionEvent admission, int matcherShard,
                     MatcherSettlementEvent settlement, java.util.function.Supplier<?> original) {
            this.owner = Objects.requireNonNull(owner);
            this.admission = Objects.requireNonNull(admission);
            this.matcherShard = matcherShard;
            this.settlement = Objects.requireNonNull(settlement);
            this.original = Objects.requireNonNull(original);
        }

        /**
         * 清准入等待任务的所有借用引用与分片值，保留任务对象。
         */
        void clear() {
            owner = null;
            admission = null;
            matcherShard = 0;
            settlement = null;
            original = null;
        }

        /**
         * 在 Matcher 等 Lane 准入收据；拒绝则发布拒单，否则执行准入指定的先撤订单。UNKNOWN_ORDER 表示旧挂单已不在簿中，内部意图可跳过；其他失败保留前缀事实并返回，全部处理后调用原下单任务。
         */
        @Override
        public Object get() {
            admission.awaitMatcherReceipt(settlement);
            if (!settlement.admissionAccepted()) {
                // 当前命令解码后的原始下单输入，提供逻辑订单号与品种。
                var place = decodedCommand.placeOrder();
                owner.matchingAdapter().rejectedPlaceDirect(
                        matcherShard, coreSequence, command.header().commandId(), place.orderId(),
                        command.header().submittedAtEpochMillis(),
                        settlement.admissionResultCode(), settlement);
                return settlement;
            }
            // 准入收据保存的内部先撤订单列表，可能使用 primitive 不可变存储。
            var cancellations = settlement.admissionCancellations();
            // 当前命令解码后的原始下单输入，提供逻辑订单号与品种。
            var place = decodedCommand.placeOrder();
            for (/* 当前前置撤单或结果数组中的下标。 */ int index = 0; index < cancellations.size(); index++) {
                // 当前准入表示关联的下单号或内部先撤订单号。
                long orderId = cancellations instanceof ImmutableLongArrayList /* 已经是 primitive 不可变长整数列表的先撤输入，可直接按值读取。 */ primitive
                        ? primitive.valueAt(index) : cancellations.get(index);
                // 当前前置撤单的原生回执，用其状态决定记录事实或停止。
                var result = owner.matchingAdapter().cancelDirectPrefix(
                        command.header().submittedAtEpochMillis(), command.header().userId(),
                        orderId, place.instrumentId());
                if (result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.MATCHING_UNKNOWN_ORDER_ID) {
                    // Lane 准入时可能还没消费较早的 maker 成交。内部意图只撤仍在簿中的订单；
                    // 已不存在的挂单不再追加撤单事实，也不再做一次账户释放。
                    continue;
                }
                if (result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                        || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED) {
                    settlement.recordDirectPreCancellation(orderId);
                } else {
                    return owner.matchingAdapter().publishDirectPrefixFailure(
                            matcherShard, coreSequence, command.header().commandId(), place.orderId(), result, settlement);
                }
            }
            return original.get();
        }
    }

    /**
     * 普通下单或顺序批量冻结项的固定 Matcher 任务，只借用一种准入表示与直达事件。
     */
    private final class PlaceMatchingContinuation implements java.util.function.Supplier<Object> {
        /** 固定撮合任务借用的所属交易运行时。 */
        private TradingCoreRuntime owner;
        /** 固定任务负责的全局 Matcher 分片。 */
        private int matcherShard;
        /** 固定撮合任务请求所属用户。 */
        private long userId;
        /** 普通下单已经完成 Lane 准入的输入。 */
        private ResolvedPlaceOrder resolvedOrder;
        /** 普通下单独立撮合输入，与另外两种表示互斥。 */
        private CoreMatchingOrder matchingOrder;
        /** 已冻结并可由 Matcher 消费的订单租约。 */
        private OrderRuntime admittedOrder;
        /** 固定任务借用的直达结算事件。 */
        private MatcherSettlementEvent direct;

        /**
         * 把已准入对象或独立撮合对象交给完整准备方法，不使用冻结 OrderRuntime 输入。
         * @param owner 所属交易 Owner 运行时
         * @param matcherShard 调用方确定的目标 Matcher 分片
         * @param userId 请求所属用户编号
         * @param resolvedOrder 已经完成准入的下单对象
         * @param matchingOrder 独立的不可变撮合输入
         * @param direct 本命令独占并尚未退休的直达结算事件
         */
        void prepare(TradingCoreRuntime owner, int matcherShard, long userId,
                     ResolvedPlaceOrder resolvedOrder, CoreMatchingOrder matchingOrder,
                     MatcherSettlementEvent direct) {
            prepare(owner, matcherShard, userId, resolvedOrder, matchingOrder, null, direct);
        }

        /**
         * 要求三种订单表示恰好一种非空，并保存非空直达结算目标和 Owner；只准备固定任务，不在这里撮合。
         * @param owner 所属交易 Owner 运行时
         * @param matcherShard 调用方确定的目标 Matcher 分片
         * @param userId 请求所属用户编号
         * @param resolvedOrder 已经完成准入的下单对象
         * @param matchingOrder 独立的不可变撮合输入
         * @param admittedOrder 已经冻结供 Matcher 消费的批量项订单租约
         * @param direct 本命令独占并尚未退休的直达结算事件
         */
        void prepare(TradingCoreRuntime owner, int matcherShard, long userId,
                     ResolvedPlaceOrder resolvedOrder, CoreMatchingOrder matchingOrder,
                     OrderRuntime admittedOrder, MatcherSettlementEvent direct) {
            if ((resolvedOrder == null ? 0 : 1) + (matchingOrder == null ? 0 : 1)
                    + (admittedOrder == null ? 0 : 1) != 1 || admittedOrder != null && direct == null) {
                throw new IllegalArgumentException("exactly one ordinary PLACE order representation is required");
            }
            this.owner = Objects.requireNonNull(owner);
            this.matcherShard = matcherShard;
            this.userId = userId;
            this.resolvedOrder = resolvedOrder;
            this.matchingOrder = matchingOrder;
            this.admittedOrder = admittedOrder;
            this.direct = Objects.requireNonNull(direct, "direct settlement");
        }

        /**
         * 清普通下单任务的 Owner、用户、分片、三种订单表示和结算引用。
         */
        void clear() {
            owner = null;
            matcherShard = 0;
            userId = 0;
            resolvedOrder = null;
            matchingOrder = null;
            admittedOrder = null;
            direct = null;
        }

        /**
         * 先执行共同前置撤单，失败即返回；否则从当前命令头取 UUID 两半，按冻结订单、已准入对象或独立撮合对象调用对应直接下单接口。
         */
        @Override public Object get() {
            // 当前准入表示关联的下单号或内部先撤订单号。
            long orderId = admittedOrder != null ? admittedOrder.orderId()
                    : resolvedOrder != null ? resolvedOrder.orderId() : matchingOrder.orderId();
            // 共同前置撤单返回的可空失败事实，非空时不再执行本次普通操作。
            var failure = applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
            if (failure != null) return failure;
            // 从当前命令头读取的 UUID 高六十四位。
            long msb = command.header().commandIdMsb();
            // 从当前命令头读取的 UUID 低六十四位。
            long lsb = command.header().commandIdLsb();
            if (admittedOrder != null)
                return owner.matchingAdapter().placeAdmittedDirect(matcherShard, coreSequence, msb, lsb,
                        command.header().submittedAtEpochMillis(), userId, admittedOrder, direct);
            if (resolvedOrder != null)
                return owner.matchingAdapter().placeDirect(matcherShard, coreSequence, msb, lsb,
                        command.header().submittedAtEpochMillis(), userId, resolvedOrder, direct);
            return owner.matchingAdapter().placeDirect(matcherShard, coreSequence, msb, lsb,
                    command.header().submittedAtEpochMillis(), userId, matchingOrder, direct);
        }
    }

    /**
     * 普通撤单固定任务，保存 Owner 准备的身份并在 Matcher 线程调用适配器。
     */
    private final class CancelMatchingContinuation implements java.util.function.Supplier<Object> {
        /** 固定撮合任务借用的所属交易运行时。 */
        private TradingCoreRuntime owner;
        /** 固定任务负责的全局 Matcher 分片。 */
        private int matcherShard;
        /** 固定任务关联的逻辑或实际订单号，具体用途由对应任务确定。 */
        private long orderId;
        /** 固定撮合任务请求所属用户。 */
        private long userId;
        /** 撤单或替换任务的永久品种字符串。 */
        private String instrumentId;
        /** 固定任务借用的直达结算事件。 */
        private MatcherSettlementEvent direct;

        /**
         * 保存撤单身份与非空 Owner、品种和直达结算目标，数值合法性在适配器入口继续检查。
         * @param owner 所属交易 Owner 运行时
         * @param matcherShard 调用方确定的目标 Matcher 分片
         * @param orderId 当前逻辑命令或结果绑定的订单号
         * @param userId 请求所属用户编号
         * @param instrumentId 永久品种字符串
         * @param direct 本命令独占并尚未退休的直达结算事件
         */
        void prepare(TradingCoreRuntime owner, int matcherShard, long orderId,
                     long userId, String instrumentId, MatcherSettlementEvent direct) {
            this.owner = Objects.requireNonNull(owner);
            this.matcherShard = matcherShard;
            this.orderId = orderId;
            this.userId = userId;
            this.instrumentId = Objects.requireNonNull(instrumentId);
            this.direct = Objects.requireNonNull(direct, "direct settlement");
        }

        /**
         * 清本固定撤单任务保留的身份与引用。
         */
        void clear() {
            owner = null;
            matcherShard = 0;
            orderId = 0;
            userId = 0;
            instrumentId = null;
            direct = null;
        }

        /**
         * 先执行共同前置撤单，未失败才调用直接撤单，并把本槽 Core 身份与业务时间交给原生适配器。
         */
        @Override public Object get() {
            // 共同前置撤单返回的可空失败事实，非空时不再执行本次普通操作。
            var failure = applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
            if (failure != null) return failure;
            return owner.matchingAdapter().cancelDirect(matcherShard, coreSequence,
                    command.header().commandIdMsb(), command.header().commandIdLsb(), orderId,
                    command.header().submittedAtEpochMillis(), userId, instrumentId, direct);
        }
    }

    /**
     * 替换/改单固定任务，旧单及新单准入身份在提交前固定。
     */
    private final class ReplaceMatchingContinuation implements java.util.function.Supplier<Object> {
        /** 固定撮合任务借用的所属交易运行时。 */
        private TradingCoreRuntime owner;
        /** 固定任务负责的全局 Matcher 分片。 */
        private int matcherShard;
        /** 固定任务关联的逻辑或实际订单号，具体用途由对应任务确定。 */
        private long orderId;
        /** 固定撮合任务请求所属用户。 */
        private long userId;
        /** 替换任务需要先撤掉的旧订单号。 */
        private long originalOrderId;
        /** 撤单或替换任务的永久品种字符串。 */
        private String instrumentId;
        /** 替换任务已经完成准入的新单输入。 */
        private ResolvedPlaceOrder resolvedReplacement;
        /** 固定任务借用的直达结算事件。 */
        private MatcherSettlementEvent direct;

        /**
         * 保存替换身份与非空 Owner、品种、新准入及目标事件，实际撤旧下新延后到 Matcher 执行。
         * @param owner 所属交易 Owner 运行时
         * @param matcherShard 调用方确定的目标 Matcher 分片
         * @param orderId 当前逻辑命令或结果绑定的订单号
         * @param userId 请求所属用户编号
         * @param originalOrderId 替换前要撤销的旧订单号
         * @param instrumentId 永久品种字符串
         * @param replacement 已经准入的新单输入
         * @param direct 本命令独占并尚未退休的直达结算事件
         */
        void prepare(TradingCoreRuntime owner, int matcherShard, long orderId,
                     long userId, long originalOrderId, String instrumentId, ResolvedPlaceOrder replacement,
                     MatcherSettlementEvent direct) {
            this.owner = Objects.requireNonNull(owner);
            this.matcherShard = matcherShard;
            this.orderId = orderId;
            this.userId = userId;
            this.originalOrderId = originalOrderId;
            this.instrumentId = Objects.requireNonNull(instrumentId);
            this.resolvedReplacement = Objects.requireNonNull(replacement);
            this.direct = Objects.requireNonNull(direct);
        }

        /**
         * 清替换任务所有借用引用和数值身份。
         */
        void clear() {
            owner = null;
            matcherShard = 0;
            orderId = 0;
            userId = 0;
            originalOrderId = 0;
            instrumentId = null;
            resolvedReplacement = null;
            direct = null;
        }

        /**
         * 先处理共同前置撤单，再以旧单号和新准入调用直接替换接口；前置失败不再提交新替换业务。
         */
        @Override public Object get() {
            // 共同前置撤单返回的可空失败事实，非空时不再执行本次普通操作。
            var failure = applyMatcherPreCancellations(owner, matcherShard, orderId, direct);
            if (failure != null) return failure;
            return owner.matchingAdapter().replaceDirect(matcherShard, coreSequence,
                    command.header().commandIdMsb(), command.header().commandIdLsb(), orderId,
                    command.header().submittedAtEpochMillis(), userId, originalOrderId, instrumentId, resolvedReplacement, direct);
        }
    }

    /**
     * Owner 独占的到期结算完成轮询器，完成后只汇集已有工作结果并请求发布。
     */
    private static final class SettlementControlContinuation implements java.util.function.BooleanSupplier {
        /** 固定 Owner 控制轮询器借用的有序提交器。 */
        private OrderedCommitCoordinator coordinator;
        /** 固定控制轮询器借用的既有业务工作对象。 */
        private com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation.SettlementWork work;

        /**
         * 绑定非空提交器和已有结算工作，后续在 Owner 轮询。
         * @param coordinator 当前有序提交器
         * @param work 已存在的非空业务续步工作，具体类型由结算或强平入口决定
         */
        void prepare(OrderedCommitCoordinator coordinator,
                com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation.SettlementWork work) {
            this.coordinator = Objects.requireNonNull(coordinator);
            this.work = Objects.requireNonNull(work);
        }

        /**
         * 清结算控制任务借用的提交器和工作引用。
         */
        void clear() {
            coordinator = null;
            work = null;
        }

        /**
         * 结算工作未完成返回假；完成后把选中用户及结算进度加入 Owner 结果构建器，请求发布并返回真，不在这里重新结算。
         */
        @Override
        public boolean getAsBoolean() {
            if (!work.poll()) return false;
            coordinator.owner().resultBuilder.beginChangedUsers();
            for (long /* 结算工作已选中的一个变更用户号，加入 Owner 结果构建器。 */ user : work.selectedUsers()) coordinator.owner().resultBuilder.addChangedUser(user);
            coordinator.owner().resultBuilder.commandSettlementProgress = work.result();
            coordinator.publication.request();
            return true;
        }
    }

    /**
     * Owner 独占的强平执行完成轮询器，复用本槽对象而不每次创建捕获函数。
     */
    private static final class LiquidationExecutionContinuation implements java.util.function.BooleanSupplier {
        /** 固定 Owner 控制轮询器借用的有序提交器。 */
        private OrderedCommitCoordinator coordinator;
        /** 固定控制轮询器借用的既有业务工作对象。 */
        private com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionWork work;
        /** 强平工作完成后写入结果构建器的进度值。 */
        private com.surprising.aeron.protocol.CoreLiquidationProgressView progress;

        /**
         * 绑定非空提交器、强平工作和最终进度值。
         * @param coordinator 当前有序提交器
         * @param work 已存在的非空业务续步工作，具体类型由结算或强平入口决定
         * @param progress 强平工作完成后要返回的进度值
         */
        void prepare(OrderedCommitCoordinator coordinator,
                com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionWork work,
                com.surprising.aeron.protocol.CoreLiquidationProgressView progress) {
            this.coordinator = Objects.requireNonNull(coordinator);
            this.work = Objects.requireNonNull(work);
            this.progress = Objects.requireNonNull(progress);
        }

        /**
         * 清强平控制任务借用的提交器、工作及进度。
         */
        void clear() {
            coordinator = null;
            work = null;
            progress = null;
        }

        /**
         * 强平工作尚未完成返回假；完成后写入结果进度、请求发布并返回真。
         */
        @Override
        public boolean getAsBoolean() {
            if (!work.getAsBoolean()) return false;
            coordinator.owner().resultBuilder.commandLiquidationProgress = progress;
            coordinator.publication.request();
            return true;
        }
    }

    /**
     * 普通单笔/替换命令的 Lane 结果目标，最多保留两个身份、可复用订单副本和一个响应租约；它不是 Lane 实时订单表，借用期截至清理或复用。
     */
    static final class LaneResultTarget
            implements com.surprising.aeron.service.order.state.LaneOrderResultTarget {
        /** 最多两个需要捕获的订单身份数组。 */
        private final long[] ids = new long[2];
        /** 本目标持有的最多两个可空订单结果副本引用。 */
        private final OrderRuntime[] orders = new OrderRuntime[2];
        /** 保留并复用的订单副本实例数组，清目标时清内容而不丢掉对象。 */
        private final OrderRuntime[] orderCopies = new OrderRuntime[2];
        /** 每个结果捕获时保留的品种字符串。 */
        private final String[] symbols = new String[2];
        /** 当前绑定的身份数量，最多二。 */
        private int count;
        /** 哪些绑定项已被 Lane 捕获的位集合。 */
        private int capturedMask;
        /**  当前响应编码是否完成的标记。 返回当前是否已准备响应字节。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private boolean prepared;
        /** 目标当前仍持有的响应数组引用。 */
        private byte[] response;
        /** 响应有效字节数，不等于 Arena 数组总容量。 */
        private int responseLength;
        /** 绑定的响应缓冲 Arena，清目标后保留引用供下次使用。 */
        private ResponseArena responseArena;
        /** 仍由目标持有的可空 Arena 租约，移交后清空。 */
        private ResponseArena.Slot responseSlot;

        /**
         * 先清上次结果，再保存 Arena；只有 first 为正才启用目标，second 为正且不同才增加第二项。
         * @param first 第一个目标订单号，非正时不启用结果目标
         * @param second 可选第二目标订单号，须为正且不同于 first 才加入
         * @param responseArena 非空响应编码需要使用的缓冲 Arena
         */
        void bind(long first, long second, ResponseArena responseArena) {
            clear();
            this.responseArena = responseArena;
            if (first <= 0) return;
            ids[0] = first;
            count = 1;
            if (second > 0 && second != first) {
                ids[1] = second;
                count = 2;
            }
        }

        /**
         * 返回是否绑定了至少一个正订单号。
         */
        boolean active() { return count != 0; }
        /**
         * 在最多两个已绑定身份中查结果副本，找不到返回空。
         * @param id 需要在已捕获结果中查找的订单号
         */
        OrderRuntime order(long id) {
            for (/* 当前前置撤单或结果数组中的下标。 */ int index = 0; index < count; index++) if (ids[index] == id) return orders[index];
            return null;
        }
        /**
         * 在绑定身份中返回捕获时保存的品种字符串，找不到为空。
         * @param id 需要在已捕获结果中查找的订单号
         */
        String instrumentId(long id) {
            for (/* 当前前置撤单或结果数组中的下标。 */ int index = 0; index < count; index++) if (ids[index] == id) return symbols[index];
            return null;
        }

        /**
         * 返回绑定身份数，不是当前非空订单副本数。
         */
        @Override public int resultCount() { return count; }
        /**
         * 要求 Lane 连未发生变化的目标订单也捕获结果，供响应完整描述。
         */
        @Override public boolean captureUnchangedResults() { return true; }
        /**
         * 按位判断指定结果项是否已被 Lane 捕获；调用方保证下标有效。
         * @param index 当前结果位置或压缩列表下标，范围要求见当前方法
         */
        @Override public boolean resultPrepared(int index) { return (capturedMask & (1 << index)) != 0; }
        /**
         * 返回指定绑定项的订单号，不做额外下标检查。
         * @param index 当前结果位置或压缩列表下标，范围要求见当前方法
         */
        @Override public long resultOrderId(int index) { return ids[index]; }
        /**
         * 返回零；普通替换由两个独立绑定项表达，不在此逐项另存 originalOrderId。
         * @param index 当前结果位置或压缩列表下标，范围要求见当前方法
         */
        @Override public long resultOriginalOrderId(int index) { return 0; }
        /**
         * 把非空订单复制进本目标持有的可复用副本槽，空订单保持空；保存品种并标已捕获，Owner 后续无需回读 Lane 表。副本在目标清理或复用后不能继续借用。
         * @param index 当前结果位置或压缩列表下标，范围要求见当前方法
         * @param order 需要保存的准入订单或捕获的可空结果订单，按当前接口语义使用
         * @param instrumentId 永久品种字符串
         */
        @Override public void resultOrder(int index, OrderRuntime order, String instrumentId) {
            orders[index] = order == null ? null : (orderCopies[index] = order.copyIntoCommandSlot(orderCopies[index]));
            symbols[index] = instrumentId;
            capturedMask |= 1 << index;
        }
        /**
         * 要求 Lane 对已终态订单也提供结果副本。
         */
        @Override public boolean includeTerminalAfterImage() { return true; }
        /**
         * 归还旧租约后按非空订单副本数选择空响应、单单或多单协议，向 Arena 租用字节并编码，保存有效长度；编码运行异常或 Error 时归还本次已取得的租约再抛出。
         */
        @Override public void prepareResponse() {
            if (responseSlot != null) responseArena.release(responseSlot);
            responseSlot = null;
            prepared = false;
            response = null;
            responseLength = 0;
            if (responseOrders.isEmpty()) {
                response = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
                prepared = true;
                return;
            }
            try {
                // 非空订单压缩视图是否恰好只有一项。
                boolean single = responseOrders.size() == 1;
                // 非空订单压缩视图中的首个协议字段来源，用于确定响应编码大小。
                var first = responseOrders.get(0);
                // 本次单单或多单响应要求的字节容量。
                int length = single
                        ? com.surprising.aeron.protocol.CoreCommandResultCodec.encodedSingleOrderLength(first)
                        : com.surprising.aeron.protocol.CoreCommandResultCodec.encodedLength(responseOrders, java.util.List.of());
                responseSlot = responseArena.acquireSlot(length);
                // 协议实际写入的响应有效字节数。
                int encodedLength = single
                        ? com.surprising.aeron.protocol.CoreCommandResultCodec.encodeSingleOrderInto(first, responseSlot.writer())
                        : com.surprising.aeron.protocol.CoreCommandResultCodec.encodeInto(
                                responseOrders, java.util.List.of(), responseSlot.storage(), 0);
                responseLength = encodedLength;
                response = responseSlot.storage();
                prepared = true;
            } catch (/* 本次响应编码抛出的运行异常或 Error，归还已取得租约后继续抛出。 */ RuntimeException | Error failure) {
                if (responseSlot != null) responseArena.release(responseSlot);
                responseSlot = null;
                throw failure;
            }
        }

        /** 固定位置零和一各自预构造的协议字段读取视图。 */
        private final OrderSource[] sources = {new OrderSource(0), new OrderSource(1)};
        /** 按非空订单压缩展示的只读列表视图，不另建订单对象列表。 */
        private final java.util.List<com.surprising.aeron.protocol.CoreOrderStateSource> responseOrders = new OrderSources();

        /**
         * 最多两个非空订单结果的压缩只读列表视图，不另建 boxed 条目容器。
         */
        private final class OrderSources extends java.util.AbstractList<com.surprising.aeron.protocol.CoreOrderStateSource>
                implements java.util.RandomAccess {
            /**
             * 统计最多两个绑定项中的非空订单数量，空订单不进入响应。
             */
            @Override public int size() {
                // 压缩响应视图中已统计的非空订单数量。
                int size = 0;
                for (/* 当前扫描两个结果位置或清理副本的下标。 */ int i = 0; i < count; i++) if (orders[i] != null) size++;
                return size;
            }
            /**
             * 按非空订单的压缩下标返回固定字段视图，负数或超出实际数量时抛越界异常。
             * @param index 当前结果位置或压缩列表下标，范围要求见当前方法
             */
            @Override public com.surprising.aeron.protocol.CoreOrderStateSource get(int index) {
                if (index < 0) throw new IndexOutOfBoundsException(index);
                for (/* 当前扫描两个结果位置或清理副本的下标。 */ int i = 0; i < count; i++) {
                    if (orders[i] != null && index-- == 0) return sources[i];
                }
                throw new IndexOutOfBoundsException(index);
            }
        }

        /** 固定结果位置的协议字段读取视图，读取本目标副本而非原 Lane 对象，位置复用后不能保留旧读视图作历史。 构造约定：固定绑定一个结果数组位置，字段读取始终取该位置当前副本。 参数 index：当前结果位置或压缩列表下标，范围要求见当前方法 */
        @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
        private final class OrderSource implements com.surprising.aeron.protocol.CoreOrderStateSource {
            /** 当前字段视图固定读取的结果数组位置。 */
            private final int index;
            /**
             * 返回固定位置当前的订单副本，调用方只在该位置非空时使用。
             */
            private OrderRuntime value() { return orders[index]; }
            /**
             * 返回当前结果副本的订单编号，不读取 Lane 订单表。
             */
            public long orderId() { return value().orderId(); }
            /**
             * 返回当前结果副本的订单所属产品线，不读取 Lane 订单表。
             */
            public com.surprising.product.api.ProductLine productLine() { return value().productLine(); }
            /**
             * 返回当前结果副本的订单用户编号，不读取 Lane 订单表。
             */
            public long userId() { return value().userId(); }
            /**
             * 返回当前结果捕获时保存在 symbols 数组中的品种字符串，不向运行时注册表查询。
             */
            public String instrumentId() { return symbols[index]; }
            /**
             * 返回当前结果副本的买卖方向，不读取 Lane 订单表。
             */
            public com.surprising.aeron.protocol.CoreOrderSide side() { return value().side(); }
            /**
             * 返回当前结果副本的原订单展示价 tick 数，不读取 Lane 订单表。
             */
            public long priceTicks() { return value().priceTicks(); }
            /**
             * 返回当前结果副本的原始数量步数，不读取 Lane 订单表。
             */
            public long quantitySteps() { return value().quantitySteps(); }
            /**
             * 返回当前结果副本的累计已成交数量步数，不读取 Lane 订单表。
             */
            public long executedQuantitySteps() { return value().executedQuantitySteps(); }
            /**
             * 返回当前结果副本的剩余数量步数，不读取 Lane 订单表。
             */
            public long remainingQuantitySteps() { return value().remainingQuantitySteps(); }
            /**
             * 返回当前结果副本的只减仓标记，不读取 Lane 订单表。
             */
            public boolean reduceOnly() { return value().reduceOnly(); }
            /**
             * 返回当前结果副本的全仓或逐仓模式，不读取 Lane 订单表。
             */
            public com.surprising.aeron.protocol.CoreMarginMode marginMode() { return value().marginMode(); }
            /**
             * 返回当前结果副本的净仓或双向持仓侧，不读取 Lane 订单表。
             */
            public com.surprising.aeron.protocol.CorePositionSide positionSide() { return value().positionSide(); }
            /**
             * 返回当前结果副本的市价或限价类型，不读取 Lane 订单表。
             */
            public com.surprising.aeron.protocol.CoreOrderType orderType() { return value().orderType(); }
            /**
             * 返回当前结果副本的有效期策略，不读取 Lane 订单表。
             */
            public com.surprising.aeron.protocol.CoreTimeInForce timeInForce() { return value().timeInForce(); }
            /**
             * 返回当前结果副本的只挂单标记，不读取 Lane 订单表。
             */
            public boolean postOnly() { return value().postOnly(); }
            /**
             * 返回当前结果副本的客户端订单键，不读取 Lane 订单表。
             */
            public String clientOrderId() { return value().clientOrderId(); }
            /**
             * 返回当前结果副本的下单命令 UUID，不读取 Lane 订单表。
             */
            public java.util.UUID commandId() { return value().commandId(); }
            /**
             * 返回当前结果副本的固定 maker 手续费率百万分比，不读取 Lane 订单表。
             */
            public long makerFeeRatePpm() { return value().makerFeeRatePpm(); }
            /**
             * 返回当前结果副本的固定 taker 手续费率百万分比，不读取 Lane 订单表。
             */
            public long takerFeeRatePpm() { return value().takerFeeRatePpm(); }
            /**
             * 返回当前结果副本的累计已收手续费单位数，不读取 Lane 订单表。
             */
            public long cumulativeFeeUnits() { return value().cumulativeFeeUnits(); }
            /**
             * 返回当前结果副本的累计成交金额高六十四位，不读取 Lane 订单表。
             */
            public long executedValueHigh() { return value().executedValueHigh(); }
            /**
             * 返回当前结果副本的累计成交金额低六十四位的原位模式，不读取 Lane 订单表。
             */
            public long executedValueLow() { return value().executedValueLow(); }
            /**
             * 返回当前结果副本的订单创建业务时间，不读取 Lane 订单表。
             */
            public long createdAtEpochMillis() { return value().createdAtEpochMillis(); }
            /**
             * 返回当前结果副本的最近更新业务时间，不读取 Lane 订单表。
             */
            public long updatedAtEpochMillis() { return value().updatedAtEpochMillis(); }
            /**
             * 返回当前结果副本的对应 Cluster 日志位置，不读取 Lane 订单表。
             */
            public long clusterPosition() { return value().clusterPosition(); }
            /**
             * 返回当前结果副本的订单状态枚举的名称文本，不读取 Lane 订单表。
             */
            public String status() { return value().status().name(); }
            /**
             * 返回当前结果副本的订单修订号，不读取 Lane 订单表。
             */
            public long revision() { return value().revision(); }
        }

        /**
         * 返回当前响应存储引用，移交租约后为空。
         */
        @Override public byte[] preparedResponse() { return response; }
        /**
         * 返回当前响应有效字节数，移交后为零。
         */
        @Override public int preparedResponseLength() { return responseLength; }

        /**
         * 只断开当前响应槽和数组/长度引用，不归还 Arena，prepared 标记保持原值直到 clear 或下一次准备。
         */
        void transferResponseOwnership() {
            responseSlot = null;
            response = null;
            responseLength = 0;
        }

        /**
         * 归还仍由目标持有的响应租约，清有效订单和身份、捕获位与响应标记；保留 Arena 引用及已分配副本对象以便再次使用。
         */
        void clear() {
            if (responseSlot != null && responseArena != null) {
                responseArena.release(responseSlot);
                responseSlot = null;
            }
            for (/* 当前扫描两个结果位置或清理副本的下标。 */ int i = 0; i < count; i++) {
                orders[i] = null;
                // 该位置保留的命令独占订单副本，清内容后仍保留对象。
                OrderRuntime copy = orderCopies[i];
                if (copy != null) copy.clearCommandSlot();
                symbols[i] = null;
                ids[i] = 0;
            }
            count = 0;
            capturedMask = 0;
            prepared = false;
            response = null;
            responseLength = 0;
        }
    }

    /** 解除普通下单的准入和实时引用，收据交接由调用方先完成。 */
    private void clearPlaceReferences() {
        placeAdmission = null;
        placeAdmissionOwnerCollected = false;
        realtimeResolvedTakerOrder = null;
    }

    /** 清强平工作内部引用，保留工作对象供下一次复用。 */
    private void clearLiquidationReferences() {
        if (reusableLiquidationWork != null) reusableLiquidationWork.clearReferences();
        if (liquidationCheck != null) liquidationCheck.clearReferences();
    }

    /** 清已结束的延后撮合元数据，阶段转换由调用方负责。 */
    private void clearDeferredMatching() {
        deferredClusterTimestamp = 0;
        deferredClusterPosition = 0;
        deferredSourceKey = null;
    }

}
