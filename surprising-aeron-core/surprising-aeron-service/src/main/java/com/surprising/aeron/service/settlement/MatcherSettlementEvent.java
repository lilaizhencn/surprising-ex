package com.surprising.aeron.service.settlement;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.LaneOrderResultTarget;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 下单步骤 4.2–4.6 的可复用结算事件。Owner 准备命令输入，Matcher 整理并发布事实，各账户 Lane 写自己的变化和完成位，系统账本交回财务完成位，Owner 收集后有序提交。readyForLane 只要求结果已发布，ready 还等待发布方交出租约，complete 再核对全部路由账户及已登记财务工作；事件不是永久不可变快照，清理前还须交出 changes。
 * 构造约定：创建空事件壳，命令输入、批量槽和完成标记由后续准备入口填写。
 */
@NoArgsConstructor
public final class MatcherSettlementEvent implements
        SettlementLaneWorker.Command,
        com.surprising.aeron.service.matcher.MatchingResult {
    /**
     * 原 Matcher 传输占用的释放边界，事件复用现有上下文回调，不新增逐命令任务或状态副本。
     */
    public interface MatcherCompletionRoute {
        /**
         * 由事件发布方通知原 Matcher 传输上下文释放本分片提交占用，仍须由事件发布租约保护后续 Lane 投递。
         * @param matcherShard 本代结果或释放回调对应的 Matcher 分片编号
         */
        void releaseMatcherSubmission(int matcherShard);
    }

    /** 无财务差额时共用的空返回值或只读输入，不向其中累加真实成交金额。 */
    private static final RuntimeTreasuryDelta EMPTY_TREASURY_DELTA = new RuntimeTreasuryDelta(1);
    /** 不同 Lane 完成位之间相隔十六个 long，降低并发写相邻位置的竞争。 */
    private static final int CACHE_LINE_LONGS = 16;
    /** 完成数组的 VarHandle，Lane release 发布、Owner 及账本 acquire 读取。 */
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    /**  本段账户最终提交序号，顺序批次中间项可为零。 返回本段账户处理完成后需要推进的 Lane 提交序号，零表示本段不推进最终水位。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long commitSequence;
    /**  当前事实真正需要应用账户业务变化的 Lane 位图。 返回本代事实实际需要应用业务变化的账户 Lane 位图。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long requiredLaneMask;
    /** 复制日志中的业务时间，用于订单及触发终态元数据。 */
    private long commitTimestamp;
    /** 本次复制命令的日志位置，随账户结果发布。 */
    private long commitClusterPosition;
    /**  当前代所属运行时，事件归还前解除引用。 借用事件关联的所属交易运行时，回收后引用为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private TradingAccountRuntime runtime;
    /**  当前代解析资产、币对及持仓身份的运行时字典。 借用当前代运行时身份字典，事件回收后引用为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private RuntimeIdentityRegistry identities;
    /** 按实际参与业务 Lane 紧凑排列的财务差额槽，各 Lane 只写自己的槽。 */
    private RuntimeTreasuryDelta[] touchedLaneTreasuryDeltas;
    /** 当前代实体、余额及资金变化缓冲，完成后由 Owner 接管并解除引用。 */
    private MatcherSettlementChanges changes;
    /** 本代有效计划中是否至少有一个成交，用于选择财务处理路径。 */
    private boolean treasuryTrades;
    /** 带固定间隔的各路由 Lane 完成标记，每 Lane 单写自己的有效位置。 */
    private long[] completedLanes;
    /** 本代财务结果是否已被收集，防止重复消费。 */
    private boolean collected;
    /** 本事件保留并复用的唯一财务任务槽，系统资金权威仍在原 Treasury。 */
    private TreasurySettlement treasurySettlement;
    /** Owner 已 acquire 观察完成的路由位图，本代只增加，下一代准备时清零。 */
    private long observedCompletedLaneMask;

    // 批量存储归本池化事件独占，不借用共享 Owner 暂存数组。
    /** 本事件独占的计划及命令结果存储，不借用共享 Owner 暂存数组。 */
    private BatchStorage batchStorage;
    /** 本代有效计划项数，保留数组容量不决定业务执行范围。 */
    private int batchPlanCount;
    /** 本代是否走直接撤单业务，而非普通放单或改单。 */
    private boolean cancellation;
    /** 直接撤单目标所属用户编号。 */
    private long cancellationUserId;
    /** 本代改单已经校验的输入及原单身份，普通命令为空。 */
    private com.surprising.aeron.service.order.command.ResolvedMatchingAdmission replacement;
    /** 改单新预留使用的资产编号。 */
    private int replacementAssetId;
    /** 触发子订单对应的源触发状态，Matcher 结果据此准备终态。 */
    private com.surprising.aeron.model.CoreTriggerOrderState sourceTrigger;
    /** 源触发单本次触发的复制业务时间。 */
    private long triggeredAt;
    /** 直达事实的 Core 证据序号，可不同于最终 Lane 提交序号。 */
    private long directCoreSequence;
    /**  Owner 已预约逻辑发布标记，不代表各 Lane 已入队。 返回是否已预约逻辑发布状态，不表示 Lane 队列已经收到事件。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean dispatched;
    /**  单 Matcher 发布方已登记投递的路由 Lane 位图，不是完成位图。 返回发布方已登记投递的 Lane 位图，不能代替账户完成位。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long queuedLaneMask;
    /** 初始只含一个 Lane 时允许随 maker 成交扩展路由的标记。 */
    private boolean expandLaneRoute;
    /** Matcher 已通过 volatile 发布结果或故障，Lane 据此允许消费。 */
    private volatile boolean directPublished;
    /** 发布方仍保留事件租约的 volatile 标记，解除前 Owner 不得回收。 */
    private volatile boolean matcherPublicationDeferred;
    /** Matcher 构建或发布前捕获的执行故障，不能作为普通拒单继续提交。 */
    private Throwable directFailure;
    /**  必须消费本事件票据的 Lane 位图，固定多 Lane 预约可含本代无业务变化的 Lane。 返回必须消费本事件票据的路由 Lane 位图，可能包含本代已无业务变化的预约 Lane。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long routedLaneMask;
    /** 本代命令 UUID，供结果证据校验。 */
    private java.util.UUID directCommandId;
    /**  本代命令固定的 Matcher 分片。 返回本代命令固定的 Matcher 分片编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int directShard;
    /** Owner 与已接受准入回执合并后的授权撤单列表，不等于成功列表。 */
    private java.util.List<Long> authorizedCancellations = java.util.List.of();
    /** 本代原生结果引用，可能借用事件命令槽，只在事件租约内有效。 */
    private exchange.core2.core.common.MatcherResult directNativeResult;
    /**  本代首项原生或合成结果的 Matcher 证据序号。 返回本代首项 Matcher 证据序号。 */
    private long directMatcherSequence;
    /**  本代最后保存项的 Matcher 序号，批次输入必须递增。 返回本代最后已保存项的 Matcher 序号，批量构建据此要求递增。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long directLastMatcherSequence;
    /** 已保存原生或合成结果的有效项数，批次全部齐备后才发布。 */
    private int directNativeItemCount;
    /** 本事件独占并复用的实际成功前置撤单编号数组。 */
    private long[] directAcceptedCancellations;
    /** 本代实际成功撤单原语前缀的有效长度。 */
    private int directAcceptedCancellationCount;
    /** 本代单项或批次首项的接受标记，不能当整批每项结果。 */
    private boolean directNativeAccepted;
    /** 新单拒绝但前置撤单已成功等部分状态变化标记，不代表所有成功成交。 */
    private boolean directNativeStateChanged;
    /** 普通下单准入拒绝的合成业务码，非空时无原生成交或行情。 */
    private String directSyntheticResultCode;
    /** 原 Matcher 传输上下文的释放回调，先保存后在发布时移到局部并清除。 */
    private MatcherCompletionRoute matcherCompletionRoute;
    /** 交给传输释放回调的 Matcher 分片编号。 */
    private int matcherCompletionShard;
    /**  本代是否要求复制普通下单的账户准入回执。 返回本代是否登记了普通下单准入回执边界。 */
    private boolean admissionRequired;
    /** 事件租约内保存的普通下单不可变准入输入。 */
    private ResolvedPlaceOrder admissionOrder;
    /** 普通下单准入所属用户编号。 */
    private long admissionUserId;
    /** 普通下单准入的订单及预留编号。 */
    private long admissionOrderId;
    /** 准入回执字段已复制完成的 volatile 发布标记。 */
    private volatile boolean admissionResolved;
    /** 从准入回执复制的接受标记。 */
    private boolean admissionAccepted;
    /** 从准入回执复制的业务结果编码。 */
    private int admissionResultCode;
    /** 当前命令持有的订单最终值接收槽，事件完成前不允许归还，回收时解除引用。 */
    LaneOrderResultTarget resultTarget;
    /**
     * 本事件独占复用的批量计划、准入引用、原生结果槽及币对资产列，容量与本代有效前缀分开管理。
     */
    static final class BatchStorage {
        /** Owner 准备阶段的币对编号到首个元数据槽映射，负一表示没有缓存，回收时清空。 */
        final org.agrona.collections.Int2IntHashMap metadataSlots =
                new org.agrona.collections.Int2IntHashMap(8, 0.65f, -1, false);
        /** 按容量预创建并由事件独占复用的结算计划列，只读取本代有效前缀。 */
        final MatcherSettlementPlan[] plans;
        /** 每有效项对应的规范币对引用，撤单等路径可以不填写。 */
        final CoreInstrument[] instruments;
        /** 各有效项持有的准入订单值或命令槽引用，普通不可变准入路径可以为空。 */
        final OrderRuntime[] admittedOrders;
        /** 直接单笔或批量撤单目标的原语编号列。 */
        final long[] cancellationOrderIds;
        /** 各项按需创建的原生结果命令槽，Matcher 在结果发布前填写，回收时清空内容但保留对象。 */
        final exchange.core2.core.common.MatcherResult[] nativeResults;
        /** 本事件独占复用的替换原单撤单结果槽。 */
        exchange.core2.core.common.MatcherResult replacementCancellation;
        /** 本事件独占复用的新单命令槽，仍须遵守事件代次租约。 */
        OrderRuntime replacementOrder;
        /**
         * baseAssetIds：各有效项预解析的基础资产编号列，不依赖容量尾部旧值。
         * quoteAssetIds：各有效项预解析的计价资产编号列。
         * settleAssetIds：各有效项预解析的结算资产编号列。
         */
        final int[] baseAssetIds, quoteAssetIds, settleAssetIds;
        /** 本次准备使用的存储有效前缀，清理按此前缀释放引用。 */
        private int activeSize;
        /**
         * 按所需容量预创建计划和各元数据列；原生结果命令槽按需建立，不为每一项提前创建。
         * @param size 本次批量存储需要的容量或有效前缀数量
         */
        BatchStorage(int size) {
            plans = new MatcherSettlementPlan[size];
            for (/* 创建每个计划槽时使用的有效数组下标。 */ int i = 0; i < size; i++) plans[i] = new MatcherSettlementPlan();
            instruments = new CoreInstrument[size];
            admittedOrders = new OrderRuntime[size];
            cancellationOrderIds = new long[size];
            nativeResults = new exchange.core2.core.common.MatcherResult[size];
            baseAssetIds = new int[size]; quoteAssetIds = new int[size]; settleAssetIds = new int[size];
        }
        /**
         * 设置本次准备会使用的有效前缀，必须为正且不超过已有计划容量。
         * @param size 本次批量存储需要的容量或有效前缀数量
         */
        void activate(int size) {
            if (size <= 0 || size > plans.length) throw new IllegalArgumentException("invalid active batch size");
            activeSize = size;
        }
        /**
         * 只清理本代有效前缀中的计划引用、币对、订单和撤单编号，并重置原生命令槽；保留槽对象和原语数组容量，资产列旧尾部不再有效。
         */
        void clear() {
            metadataSlots.clear();
            for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; index < activeSize; index++) {
                plans[index].clearReferences();
                instruments[index] = null;
                admittedOrders[index] = null;
                cancellationOrderIds[index] = 0;
                if (nativeResults[index] != null) nativeResults[index].clearCommandSlot();
            }
            if (replacementCancellation != null) replacementCancellation.clearCommandSlot();
            if (replacementOrder != null) replacementOrder.clearCommandSlot();
            activeSize = 0;
        }
    }
    /**
     * 在当前结果尚未发布且下标有效时借用事件独占的原生命令结果槽，首次使用按需创建；消费者结束前不得改作另一条命令。
     * @param index 本代有效计划或原生成交结果槽的下标
     */
    public exchange.core2.core.common.MatcherResult nativeResultSlot(int index) {
        if (batchStorage == null || index < 0 || index >= batchPlanCount || directPublished)
            throw new IllegalStateException("native result slot is not writable");
        // 当前项已经创建或需要按需创建的事件自有原生命令槽。
        var result = batchStorage.nativeResults[index];
        if (result == null) batchStorage.nativeResults[index] = result = exchange.core2.core.common.MatcherResult.commandSlot();
        return result;
    }

    /**
     * 在结果尚未发布时借用本事件专属的替换撤单原生命令槽，首次使用按需创建。
     */
    public exchange.core2.core.common.MatcherResult replacementCancellationSlot() {
        if (batchStorage == null || directPublished)
            throw new IllegalStateException("replacement cancellation is not writable");
        if (batchStorage.replacementCancellation == null)
            batchStorage.replacementCancellation = exchange.core2.core.common.MatcherResult.commandSlot();
        return batchStorage.replacementCancellation;
    }

    /**
     * 事件尚未激活时准备批量存储，容量不足才重建；设置本次有效前缀，调用方仍须确保没有旧代消费者。
     * @param size 本次批量存储需要的容量或有效前缀数量
     */
    BatchStorage batchStorage(int size) {
        if (batchPlanCount != 0) throw new IllegalStateException("settlement event is still active");
        if (size <= 0) throw new IllegalArgumentException("settlement batch must not be empty");
        if (batchStorage == null || batchStorage.plans.length < size)
            batchStorage = new BatchStorage(size);
        batchStorage.activate(size);
        return batchStorage;
    }

    /**
     * 准备一代直达事件，默认以同一 Core 序号作为最终 Lane 提交序号，委托完整入口登记租约输入。
     * @param sequence 当前事实的 Core 序号，简化入口也用它推进最终 Lane 水位
     * @param laneMask 准备时已知的账户 Lane 路由位图
     * @param timestamp 复制日志中的业务时间或本次触发业务时间
     * @param position 复制日志中的命令位置
     * @param commandId 本代复制命令 UUID
     * @param shard 本代固定的 Matcher 分片编号
     * @param runtime 事件关联的交易运行时
     * @param identities 本代运行时身份字典
     * @param count 本代批量事件的有效计划项数
     * @param cancellations 准入已授权的前置撤单编号，只读保留到本代事件结束
     * @param target 在事件租约内接收最终订单值的命令目标，可为空
     */
    void prepareDirect(long sequence, long laneMask, long timestamp, long position,
                       java.util.UUID commandId, int shard, TradingAccountRuntime runtime,
                       RuntimeIdentityRegistry identities, int count, java.util.List<Long> cancellations,
                       LaneOrderResultTarget target) {
        prepareDirect(sequence, sequence, laneMask, timestamp, position, commandId, shard,
                runtime, identities, count, cancellations, target);
    }

    /**
     * 检查序号、时间、路由及有效数量，重置本代准入和发布状态，保存命令证据并借用变化缓冲。顺序批次可用零提交序号先应用本项事实，整批终态再统一推进水位；这里不投递 Lane 队列。
     * @param coreSequence 需要与本代事件身份一致的 Core 证据序号
     * @param commitSequence 最终账户提交序号，中间批次项可用零表示本段不推进水位
     * @param laneMask 准备时已知的账户 Lane 路由位图
     * @param timestamp 复制日志中的业务时间或本次触发业务时间
     * @param position 复制日志中的命令位置
     * @param commandId 本代复制命令 UUID
     * @param shard 本代固定的 Matcher 分片编号
     * @param runtime 事件关联的交易运行时
     * @param identities 本代运行时身份字典
     * @param count 本代批量事件的有效计划项数
     * @param cancellations 准入已授权的前置撤单编号，只读保留到本代事件结束
     * @param target 在事件租约内接收最终订单值的命令目标，可为空
     */
    void prepareDirect(long coreSequence, long commitSequence, long laneMask, long timestamp, long position,
                       java.util.UUID commandId, int shard, TradingAccountRuntime runtime,
                       RuntimeIdentityRegistry identities, int count, java.util.List<Long> cancellations,
                       LaneOrderResultTarget target) {
        if (coreSequence <= 0 || commitSequence < 0 || laneMask == 0 || timestamp < 0 || position < 0 || commandId == null
                || count <= 0 || batchStorage == null || count > batchStorage.plans.length)
            throw new IllegalArgumentException("invalid direct settlement reservation");
        cancellation = false; directPublished = false; matcherPublicationDeferred = false;
        dispatched = false; queuedLaneMask = 0;
        expandLaneRoute = Long.bitCount(laneMask) == 1;
        directFailure = null;
        admissionRequired = false;
        admissionOrder = null;
        admissionUserId = admissionOrderId = 0;
        admissionResolved = false;
        admissionAccepted = false;
        admissionResultCode = CoreResultCode.NONE.wireCode();
        directCoreSequence = coreSequence;
        this.commitSequence = commitSequence; routedLaneMask = requiredLaneMask = laneMask;
        commitTimestamp = timestamp; commitClusterPosition = position;
        directCommandId = commandId;
        directShard = shard;
        authorizedCancellations = java.util.Objects.requireNonNull(cancellations);
        this.runtime = runtime; this.identities = identities; resultTarget = target;
        batchPlanCount = count;
        changes = runtime.acquireMatcherSettlementChanges(laneMask);
        changes.directPositionIdentities = true;
        collected = false;
        resetCompletions(runtime.topology().accountLaneCount());
        // 直达事件通过自己带间隔的完成数组交回回执，
        // 不再登记物化计划路径使用的逐 Lane 通知预期。
    }

    /**
     * Owner 在预约 Matcher 发布前选定撤单业务及用户，后续 Lane 只应用 Matcher 接受的撤单。
     * @param userId 当前撤单、普通准入或改单所属用户编号
     */
    void cancellation(long userId) {
        if (batchPlanCount == 0 || dispatched || directPublished || userId <= 0)
            throw new IllegalStateException("invalid direct cancellation preparation");
        cancellation = true;
        cancellationUserId = userId;
    }

    /**
     * 在单项事件发布前保存源触发单和业务触发时间，后续按 Matcher 结果生成成功或失败终态；每代只可准备一次。
     * @param trigger 触发子订单所关联的源触发单状态
     * @param timestamp 复制日志中的业务时间或本次触发业务时间
     */
    public void triggerCompletion(com.surprising.aeron.model.CoreTriggerOrderState trigger,
                                  long timestamp) {
        if (dispatched || directPublished || batchPlanCount != 1 || trigger == null
                || timestamp < 0 || sourceTrigger != null)
            throw new IllegalStateException("invalid direct trigger preparation");
        sourceTrigger = trigger;
        triggeredAt = timestamp;
    }

    /**
     * 在单项事件预约发布前保存改单准入输入及新预留资产编号，供所属 Lane 原位执行。
     * @param admission 已经校验的改单输入及原单身份
     * @param assetId 改单新预留所用资产编号
     */
    void replacement(com.surprising.aeron.service.order.command.ResolvedMatchingAdmission admission, int assetId) {
        if (dispatched || directPublished || batchPlanCount != 1 || admission == null)
            throw new IllegalStateException("invalid direct replacement preparation");
        replacement = admission;
        replacementAssetId = assetId;
    }

    /**
     * 仅允许单项事件借用首项准入订单引用，普通不可变准入输入路径可能没有此运行时值。
     */
    public OrderRuntime admittedOrder() {
        if (batchPlanCount != 1) throw new IllegalStateException("single direct order is required");
        return batchStorage.admittedOrders[0];
    }

    /**
     * 检查当前事件是否有正数有效计划项，不表示结果已经发布或账户已经完成。
     */
    public boolean direct() { return batchPlanCount != 0; }
    /**
     * 返回直达 Matcher 证据和 Lane 合并使用的 Core 序号，可能不同于最终提交序号。
     */
    public long coreSequence() { return directCoreSequence; }
    /**
     * 单 Matcher 发布方为实际路由中的 Lane 登记一次投递，拒绝越界、未预约、非路由和重复投递。
     * @param laneId 当前操作的账户 Lane 编号
     */
    void markLaneQueued(int laneId) {
        if (batchPlanCount == 0 || !dispatched || laneId < 0 || laneId >= Long.SIZE)
            throw new IllegalStateException("invalid direct settlement Lane queue");
        // 当前账户 Lane 在位图中的单个位。
        long bit = 1L << laneId;
        if ((routedLaneMask & bit) == 0 || (queuedLaneMask & bit) != 0) {
            throw new IllegalStateException("direct settlement Lane queue is outside its route");
        }
        queuedLaneMask |= bit;
    }
    /**
     * 供 Owner 检查结果已发布且 Matcher 已结束发布过程；尚不表示账户或财务工作完成。
     */
    public boolean ready() { return directPublished && !matcherPublicationDeferred; }

    /**
     * 账户只等待结果发布即可消费；其他 Lane 的投递可能仍在继续，事件仍受发布租约保护。
     */
    public boolean readyForLane() {
        return directPublished;
    }

    /**
     * 把准入回执编码转换为业务名称，尚为 NONE 时按当前接口约定返回 SUCCESS；不能据此判断回执已到达。
     */
    public String admissionResultCode() {
        return admissionResultCode == CoreResultCode.NONE.wireCode()
                ? "SUCCESS" : CoreResultCode.fromWireCode(admissionResultCode).name();
    }

    /**
     * 未要求普通下单准入时视为接受，否则返回已复制的准入接受标记；调用方仍须遵守回执发布顺序。
     */
    public boolean admissionAccepted() { return !admissionRequired || admissionAccepted; }

    /**
     * 在已登记普通下单准入路由的事件中保存不可变订单输入与用户，预留回执须匹配该订单编号。
     * @param order 普通下单已经解析的不可变准入订单输入
     * @param userId 当前撤单、普通准入或改单所属用户编号
     */
    void admissionOrder(ResolvedPlaceOrder order, long userId) {
        if (batchPlanCount == 0 || !admissionRequired || order == null || userId <= 0) {
            throw new IllegalStateException("invalid place admission order");
        }
        admissionOrder = order;
        admissionUserId = userId;
        admissionOrderId = order.orderId();
    }

    /**
     * Matcher 校验 Lane 固定收据槽的订单身份，只保存接受标记和结果码，最后通过 volatile 标记发布回执；同代不允许重复发布。
     * @param reservationId 准入回执中的预留编号，必须等于本代准入订单编号
     * @param accepted 账户准入是否接受本单
     * @param resultCode 准入编码或合成拒绝业务码，类型及约束以对应入口为准
     */
    public void admissionReceipt(long reservationId, boolean accepted, int resultCode) {
        if (batchPlanCount == 0 || !admissionRequired || admissionResolved || reservationId <= 0
                || reservationId != admissionOrderId
                || resultCode < 0) {
            throw new IllegalStateException("invalid place admission receipt");
        }
        admissionAccepted = accepted;
        admissionResultCode = resultCode;
        admissionResolved = true;
    }

    /**
     * 检查原生结果、合成拒绝码或执行故障是否已准备；包含故障的准备状态不等于成功或允许 Lane 消费。
     */
    public boolean resultPrepared() {
        return directNativeResult != null
                || directSyntheticResultCode != null || directFailure != null;
    }

    /**
     * 已预约且未发布时取得发布租约，阻止 Owner 在 Matcher 仍持有事件期间回收。
     */
    public void beginMatcherPublication() {
        if (batchPlanCount == 0 || !dispatched || directPublished || matcherPublicationDeferred) {
            throw new IllegalStateException("invalid matcher publication start");
        }
        matcherPublicationDeferred = true;
    }

    /**
     * 结束当前发布过程：已有结果或故障则继续通知 Lane 并交出租约，否则只解除延后标记；非活动或未延后的事件直接返回。
     */
    public void completeMatcherPublication() {
        if (batchPlanCount == 0 || !matcherPublicationDeferred) return;
        if (resultPrepared()) notifyDirectPublication();
        else matcherPublicationDeferred = false;
    }

    /**
     * 校验单项结果的命令、序号、分片及订单证据，按撤单、普通准入或已有订单构建计划；保留已成功前置撤单，触发单补终态，最后准备实际路由和发布。构建异常登记为直达故障后继续抛出。
     * @param result 当前需要保存的原生 Matcher 结果，借用有效期受事件租约限制
     * @param coreSequence 需要与本代事件身份一致的 Core 证据序号
     * @param commandIdMostSignificantBits Matcher 证据携带的命令 UUID 高六十四位
     * @param commandIdLeastSignificantBits Matcher 证据携带的命令 UUID 低六十四位
     * @param orderId 需要核对证据或记录已接受撤单的订单编号
     * @param matcherSequence 当前 Matcher 结果证据序号，批量项要求严格递增
     * @param matcherShard 本代结果或释放回调对应的 Matcher 分片编号
     */
    public void publishDirectNativeResult(exchange.core2.core.common.MatcherResult result,
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        if (!dispatched || directPublished || batchPlanCount != 1 || result == null
                || coreSequence != directCoreSequence || directCommandId == null
                || commandIdMostSignificantBits != directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != directCommandId.getLeastSignificantBits()
                || matcherShard != directShard || matcherSequence <= 0) {
            throw new IllegalStateException("direct native matcher result proof is inconsistent");
        }
        try {
            // 命令或计划保留的准入订单引用，普通不可变准入路径可以为空。
            OrderRuntime admitted = batchStorage.admittedOrders[0];
            if (cancellation) {
                if (batchStorage.cancellationOrderIds[0] != orderId)
                    throw new IllegalStateException("direct cancellation order is inconsistent");
                batchStorage.plans[0].buildDirectNativeCancellation(directCoreSequence, cancellationUserId,
                        orderId, result, runtime);
            } else if (admissionRequired && admissionAccepted) {
                if (admissionOrder == null || admissionOrder.orderId() != orderId)
                    throw new IllegalStateException("direct admission order is inconsistent");
                batchStorage.plans[0].buildDirectNative(directCoreSequence, admissionUserId, admissionOrder,
                        batchStorage.instruments[0], result, runtime);
            } else {
                if (admitted == null || admitted.orderId() != orderId)
                    throw new IllegalStateException("direct matcher order is inconsistent");
                batchStorage.plans[0].buildDirectNative(directCoreSequence, admitted,
                        batchStorage.instruments[0], result, runtime);
            }
            directNativeResult = result;
            directMatcherSequence = matcherSequence;
            directLastMatcherSequence = matcherSequence;
            directNativeItemCount = 1;
            directNativeAccepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                    || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
            // 新单拒绝也可能已有成功撤单前缀，
            // 保留部分变化结果，提交时仍核对 Lane 最终授权。
            directNativeStateChanged = directAcceptedCancellationCount != 0 && !directNativeAccepted;
            if (sourceTrigger != null) {
                batchStorage.plans[0].completeTrigger(directNativeAccepted
                        ? sourceTrigger.toMatchedCompletion(
                                batchStorage.admittedOrders[0].orderId(), triggeredAt)
                        : sourceTrigger.toRejectedCompletion(
                                result.resultCode().name(), triggeredAt));
            }
            batchStorage.plans[0].acceptedPreCancellations(
                    directAcceptedCancellations, directAcceptedCancellationCount);
            finishDirectPublication();
        } catch (/* 构建或保存原生结果时捕获的执行异常，登记到本事件后继续抛出。 */ Throwable failure) { failDirect(failure); throw failure; }
    }

    /**
     * 严格按项下标和递增 Matcher 序号保存批量原生事实，首项承载已成功前置撤单及汇总接口的首项结果；每项记录接受标记，全部有效项齐备后才结束发布准备。
     * @param result 当前需要保存的原生 Matcher 结果，借用有效期受事件租约限制
     * @param index 本代有效计划或原生成交结果槽的下标
     * @param coreSequence 需要与本代事件身份一致的 Core 证据序号
     * @param commandIdMostSignificantBits Matcher 证据携带的命令 UUID 高六十四位
     * @param commandIdLeastSignificantBits Matcher 证据携带的命令 UUID 低六十四位
     * @param orderId 需要核对证据或记录已接受撤单的订单编号
     * @param matcherSequence 当前 Matcher 结果证据序号，批量项要求严格递增
     * @param matcherShard 本代结果或释放回调对应的 Matcher 分片编号
     */
    public void publishDirectNativeBatchItem(exchange.core2.core.common.MatcherResult result,
            int index, long coreSequence,
            long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        if (!dispatched || directPublished || result == null || batchPlanCount <= 0
                || index != directNativeItemCount || index < 0 || index >= batchPlanCount
                || coreSequence != directCoreSequence || directCommandId == null
                || commandIdMostSignificantBits != directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != directCommandId.getLeastSignificantBits()
                || matcherShard != directShard || matcherSequence <= directLastMatcherSequence) {
            throw new IllegalStateException("direct native matcher batch proof is inconsistent");
        }
        try {
            // 命令或计划保留的准入订单引用，普通不可变准入路径可以为空。
            OrderRuntime admitted = batchStorage.admittedOrders[index];
            if (cancellation ? batchStorage.cancellationOrderIds[index] != orderId
                    : admitted == null || admitted.orderId() != orderId)
                throw new IllegalStateException("direct matcher batch order is inconsistent");
            if (cancellation) {
                batchStorage.plans[index].buildDirectNativeCancellation(directCoreSequence, cancellationUserId,
                        orderId, result, runtime);
            } else {
                batchStorage.plans[index].buildDirectNative(directCoreSequence, admitted,
                        batchStorage.instruments[index], result, runtime);
            }
            if (index == 0) {
                batchStorage.plans[index].acceptedPreCancellations(
                        directAcceptedCancellations, directAcceptedCancellationCount);
                directNativeResult = result;
                directMatcherSequence = matcherSequence;
                directNativeAccepted = nativeAccepted(result);
            }
            if (resultTarget != null) resultTarget.matcherResult(index, nativeAccepted(result));
            directLastMatcherSequence = matcherSequence;
            directNativeItemCount++;
            if (directNativeItemCount == batchPlanCount) finishDirectPublication();
        } catch (/* 构建或保存原生结果时捕获的执行异常，登记到本事件后继续抛出。 */ Throwable failure) { failDirect(failure); throw failure; }
    }

    /**
     * 关联原单撤单与新单结果，区分新单接受、post-only 拒绝和其他部分变化；记录允许保留的原单撤单，未下出的新单从变化身份中移除，随后发布完整结算事实。
     * @param cancellation 替换原单撤单的原生结果，区别于新单原生结果
     * @param result 当前需要保存的原生 Matcher 结果，借用有效期受事件租约限制
     * @param coreSequence 需要与本代事件身份一致的 Core 证据序号
     * @param commandIdMostSignificantBits Matcher 证据携带的命令 UUID 高六十四位
     * @param commandIdLeastSignificantBits Matcher 证据携带的命令 UUID 低六十四位
     * @param orderId 需要核对证据或记录已接受撤单的订单编号
     * @param matcherSequence 当前 Matcher 结果证据序号，批量项要求严格递增
     * @param matcherShard 本代结果或释放回调对应的 Matcher 分片编号
     */
    public void publishDirectNativeReplacement(
            exchange.core2.core.common.MatcherResult cancellation,
            exchange.core2.core.common.MatcherResult result,
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        if (!dispatched || directPublished || replacement == null || batchPlanCount != 1
                || cancellation == null || result == null || coreSequence != directCoreSequence
                || directCommandId == null
                || commandIdMostSignificantBits != directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != directCommandId.getLeastSignificantBits()
                || matcherShard != directShard || matcherSequence <= 0
                || replacement.resolved().orderId() != orderId) {
            throw new IllegalStateException("direct native replacement proof is inconsistent");
        }
        try {
            // 替换原单的撤单是否已被 Matcher 接受。
            boolean cancellationAccepted = nativeAccepted(cancellation);
            // 原单撤单成功且新订单原生结果也被接受的标记。
            boolean placementAccepted = cancellationAccepted && nativeAccepted(result);
            // 原单已撤但新单被 post-only 规则拒绝的已知部分变化。
            boolean businessRejection = cancellationAccepted && !placementAccepted
                    && result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.MATCHING_POST_ONLY_FAILED;
            // 命令或计划保留的准入订单引用，普通不可变准入路径可以为空。
            OrderRuntime admitted = batchStorage.admittedOrders[0];
            if (admitted == null) throw new IllegalStateException("replacement order is missing");
            batchStorage.plans[0].buildDirectNative(directCoreSequence, admitted,
                    batchStorage.instruments[0], result, runtime);
            if (placementAccepted || businessRejection)
                batchStorage.plans[0].acceptedReplacementCancellation(replacement.originalOrderId());
            batchStorage.plans[0].acceptedPreCancellations(
                    directAcceptedCancellations, directAcceptedCancellationCount);
            if (!placementAccepted) batchStorage.plans[0].omitUnplacedOrder();
            directNativeResult = result;
            directMatcherSequence = matcherSequence;
            directLastMatcherSequence = matcherSequence;
            directNativeItemCount = 1;
            directNativeAccepted = placementAccepted;
            directNativeStateChanged = cancellationAccepted && !placementAccepted;
            finishDirectPublication();
        } catch (/* 构建或保存原生结果时捕获的执行异常，登记到本事件后继续抛出。 */ Throwable failure) { failDirect(failure); throw failure; }
    }

    /**
     * 为普通下单准入拒绝校验命令证据并构造无原生成交的拒绝计划，使已登记账户路径能够交回回执，不创建订单后值。
     * @param resultCode 准入编码或合成拒绝业务码，类型及约束以对应入口为准
     * @param coreSequence 需要与本代事件身份一致的 Core 证据序号
     * @param commandIdMostSignificantBits Matcher 证据携带的命令 UUID 高六十四位
     * @param commandIdLeastSignificantBits Matcher 证据携带的命令 UUID 低六十四位
     * @param orderId 需要核对证据或记录已接受撤单的订单编号
     * @param matcherSequence 当前 Matcher 结果证据序号，批量项要求严格递增
     * @param matcherShard 本代结果或释放回调对应的 Matcher 分片编号
     */
    public void publishDirectRejection(String resultCode, long coreSequence,
            long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        if (batchPlanCount == 0 || !dispatched || directPublished || !admissionRequired || admissionAccepted
                || resultCode == null || resultCode.isBlank() || coreSequence != directCoreSequence
                || directCommandId == null
                || commandIdMostSignificantBits != directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != directCommandId.getLeastSignificantBits()
                || orderId != admissionOrderId || matcherSequence <= 0 || matcherShard != directShard)
            throw new IllegalStateException("direct matcher rejection proof is inconsistent");
        try {
            batchStorage.plans[0].prepareRejectedDirect(
                    directCoreSequence, admissionUserId, admissionOrderId, routedLaneMask);
            directSyntheticResultCode = resultCode;
            directMatcherSequence = directLastMatcherSequence = matcherSequence;
            directNativeAccepted = false;
            directNativeItemCount = 1;
            finishDirectPublication();
        } catch (/* 构建或保存原生结果时捕获的执行异常，登记到本事件后继续抛出。 */ Throwable failure) { failDirect(failure); throw failure; }
    }

    /**
     * 仅原生 SUCCESS 与 ACCEPTED 视为接受。
     * @param result 当前需要保存的原生 Matcher 结果，借用有效期受事件租约限制
     */
    private static boolean nativeAccepted(exchange.core2.core.common.MatcherResult result) {
        return result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
    }

    /**
     * 准入回执已复制且接受后，将 Owner 已授权撤单与 Lane 新授权撤单合并去重并按编号排序，保存为本代只读授权列表；不表示这些撤单已经成功。
     * @param orderIds 本次准入授权或待读取的撤单编号列表
     */
    public void addAdmissionCancellations(java.util.List<Long> orderIds) {
        if (batchPlanCount == 0 || directPublished || !admissionResolved || !admissionAccepted)
            throw new IllegalStateException("place admission cancellations are not ready");
        if (orderIds.isEmpty()) return;
        // 本次合并 Owner 与账户准入授权撤单的临时原语去重集合。
        var combined = new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; index < authorizedCancellations.size(); index++)
            combined.add(cancellationIdAt(authorizedCancellations, index));
        for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; index < orderIds.size(); index++) {
            // 当前授权、取消或发布最终值的原语订单编号。
            long orderId = cancellationIdAt(orderIds, index);
            if (orderId <= 0) throw new IllegalArgumentException("invalid admission cancellation order");
            combined.add(orderId);
        }
        // 去重后按订单编号排序的自有原语数组，交给只读授权列表接管。
        long[] sorted = combined.toArray();
        java.util.Arrays.sort(sorted);
        authorizedCancellations = com.surprising.aeron.support.collection.ImmutableLongArrayList.takeOwnership(sorted);
    }

    /**
     * 准入成功后供 Matcher 借用合并后的授权撤单列表，未满足回执条件时拒绝读取。
     */
    public java.util.List<Long> admissionCancellations() {
        if (batchPlanCount == 0 || !admissionResolved || !admissionAccepted)
            throw new IllegalStateException("place admission cancellations are not ready");
        return authorizedCancellations;
    }

    /**
     * 结果发布并确认无故障后，向 Owner 返回最终授权列表以核对成功前缀；返回的是授权全集，不是实际成功项数组。
     */
    public java.util.List<Long> completedPreMatchingCancellations() {
        requireDirectResult();
        return authorizedCancellations;
    }

    /**
     * 按下标读取撤单编号，专用原语列表直接读取 long，普通列表走既有装箱接口。
     * @param orderIds 本次准入授权或待读取的撤单编号列表
     * @param index 本代有效计划或原生成交结果槽的下标
     */
    private static long cancellationIdAt(java.util.List<Long> orderIds, int index) {
        return orderIds instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList /* 已经识别为专用原语只读列表的撤单编号视图。 */ primitive
                ? primitive.valueAt(index) : orderIds.get(index);
    }

    /**
     * 结果发布前记录一项已经接受的授权撤单，必要时扩容自有数组；这里检查授权但不另行去重，调用方保证记录顺序。
     * @param orderId 需要核对证据或记录已接受撤单的订单编号
     */
    public void recordDirectPreCancellation(long orderId) {
        // 当前实际成功撤单编号是否属于本代授权列表。
        boolean authorized = authorizedCancellations instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList /* 已经识别为专用原语只读列表的撤单编号视图。 */ primitive
                ? primitive.containsLong(orderId) : authorizedCancellations.contains(orderId);
        if (batchPlanCount == 0 || directPublished || orderId <= 0 || !authorized)
            throw new IllegalStateException("unauthorized direct pre-cancellation");
        if (directAcceptedCancellations == null)
            directAcceptedCancellations = new long[Math.max(4, authorizedCancellations.size())];
        if (directAcceptedCancellationCount == directAcceptedCancellations.length)
            directAcceptedCancellations = java.util.Arrays.copyOf(
                    directAcceptedCancellations, directAcceptedCancellations.length * 2);
        directAcceptedCancellations[directAcceptedCancellationCount++] = orderId;
    }

    /**
     * 检查是否保存了原生结果引用，合成准入拒绝没有原生结果。
     */
    public boolean hasDirectNativeResult() { return directNativeResult != null; }
    /**
     * 返回本代结果证据所属 Matcher 分片。
     */
    public int directMatcherShard() { return directShard; }
    /**
     * 合并各有效计划的实际业务 Lane 与订单数量，核对原依赖范围；单 Lane 初始路由允许扩展，固定多 Lane 路由保留所有票据。准备财务差额及变化容量，未延后时立即交接发布。
     */
    private void finishDirectPublication() {
        // 所有有效计划合并得到的实际业务 Lane 位图。
        long actualLanes = 0; /* 所有有效计划需要准备的订单变化槽总数，以精确加法累计。 */ int orders = 0;
        for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; index < batchPlanCount; index++) {
            actualLanes |= batchStorage.plans[index].requiredLaneMask();
            orders = Math.addExact(orders, batchStorage.plans[index].orderCount());
        }
        // 初始单 Lane 路由保留主动账户，Matcher 构建事实时补充发现的 maker Lane。
        // 各 Lane 从自己的 Matcher 邮箱按序应用；固定多 Lane 路由的既有票据也必须消费。
        if (actualLanes == 0
                || !expandLaneRoute && (actualLanes & ~routedLaneMask) != 0
                || expandLaneRoute && (routedLaneMask & ~actualLanes) != 0)
            throw new IllegalStateException("matcher result escaped its admitted Lane dependency scope");
        if (expandLaneRoute) routedLaneMask = actualLanes;
        requiredLaneMask = actualLanes;
        treasuryTrades = hasTrade(batchStorage.plans, batchPlanCount);
        prepareTreasuryDeltas(actualLanes, treasuryTrades);
        changes.ensureOrderCapacity(orders, actualLanes);
        if (!matcherPublicationDeferred) notifyDirectPublication();
    }

    /**
     * 为已预约且未发布的活动事件保留执行异常；未延后时继续发布故障事实，非活动或已发布事件不重复处理。
     * @param failure 本代 Matcher 执行或构建失败的非空原因
     */
    public void failDirect(Throwable failure) {
        if (batchPlanCount == 0 || directPublished) return;
        if (!dispatched) throw new IllegalStateException("Matcher publication was not reserved");
        directFailure = java.util.Objects.requireNonNull(failure);
        if (!matcherPublicationDeferred) notifyDirectPublication();
    }

    /**
     * 下单步骤 4.3：持有发布租约时释放原 Matcher 提交占用，再发布结果并完成各 Lane 投递，最后解除租约。此后不再读可被回收的事件字段。
     */
    private void notifyDirectPublication() {
        // 发布完成前提前保存的运行时通知目标，完成位发布后只使用此局部引用。
        TradingAccountRuntime completionRuntime = runtime;
        // 队列中的已发布事实一旦可见，Lane 就能开始消费；
        // 发布方释放传输占用并完成全部入队前，Owner 仍不能回收事件。
        matcherPublicationDeferred = true;
        releaseMatcherCompletionBeforeSignal();
        directPublished = true;
        completionRuntime.settlements().publishMatcherOwned(this);
        matcherPublicationDeferred = false;
    }

    /**
     * 由 Matcher 管线保存已有传输上下文的释放回调与分片，避免每命令另建回调；同代不重复登记，发布后不能修改。
     * @param route 复用已有 Matcher 传输上下文的占用释放回调
     * @param matcherShard 本代结果或释放回调对应的 Matcher 分片编号
     */
    public void matcherCompletionRoute(MatcherCompletionRoute route, int matcherShard) {
        if (batchPlanCount == 0 || directPublished || matcherCompletionRoute != null || matcherShard < 0) {
            throw new IllegalStateException("invalid matcher completion release callback");
        }
        matcherCompletionRoute = java.util.Objects.requireNonNull(route);
        matcherCompletionShard = matcherShard;
    }

    /**
     * 先把释放回调及分片移到局部值并清除事件引用，再通知传输上下文释放占用，后续 Lane 投递仍受发布租约保护。
     */
    private void releaseMatcherCompletionBeforeSignal() {
        // 从事件移到局部的原 Matcher 提交占用释放回调。
        MatcherCompletionRoute route = matcherCompletionRoute;
        // 与本次传输释放回调一起取出的 Matcher 分片编号。
        int shard = matcherCompletionShard;
        matcherCompletionRoute = null;
        matcherCompletionShard = -1;
        if (route != null) route.releaseMatcherSubmission(shard);
    }

    /**
     * 检查已发布且无执行故障后，以本事件自身提供 MatchingResult 视图，视图只在事件租约内有效。
     */
    public com.surprising.aeron.service.matcher.MatchingResult directResult() {
        requireDirectResult();
        return this;
    }
    /**
     * 检查结果已发布、没有直达故障且持有原生结果或合成拒绝码；不要求账户或财务工作已完成。
     */
    public boolean directResultAvailable() {
        return directPublished && directFailure == null
                && hasEventOwnedResult();
    }
    /**
     * 作为 MatchingResult 接口返回已保存接受标记；调用方先遵守结果发布边界，批量对应首项。
     */
    @Override public boolean accepted() {
        return directNativeAccepted;
    }
    /**
     * 返回合成准入拒绝码或原生结果码名称，调用方须先确认可读结果。
     */
    @Override public String resultCode() {
        if (directSyntheticResultCode != null) return directSyntheticResultCode;
        return directNativeResult.resultCode().name();
    }
    /**
     * 直达接口不另建逐单撤单对象列表，始终返回空列表；成功前缀由计划原语编号和授权事实提供。
     */
    @Override public java.util.List<com.surprising.aeron.service.matcher.CoreCancellationResult> cancellations() {
        return java.util.List.of();
    }
    /**
     * 按部分变化和接受标记分类：post-only 拒绝保留已知前缀，其他未能确认的部分变化视为致命分歧；无部分变化时返回已应用或未变化拒绝。
     */
    @Override public com.surprising.aeron.service.matcher.MatchingResult.Outcome outcome() {
        if (directNativeStateChanged) {
            return directNativeResult != null
                    && directNativeResult.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.MATCHING_POST_ONLY_FAILED
                    ? com.surprising.aeron.service.matcher.MatchingResult.Outcome.KNOWN_PREFIX_APPLIED
                    : com.surprising.aeron.service.matcher.MatchingResult.Outcome.FATAL_DIVERGENCE;
        }
        return directNativeAccepted
                ? com.surprising.aeron.service.matcher.MatchingResult.Outcome.APPLIED
                : com.surprising.aeron.service.matcher.MatchingResult.Outcome.REJECTED_UNCHANGED;
    }
    /**
     * 为证据接口返回本代 Core 序号。
     */
    @Override public long nativeCoreSequence() {
        return directCoreSequence;
    }
    /**
     * 为证据接口返回本代首项 Matcher 序号。
     */
    @Override public long nativeMatcherSequence() {
        return directMatcherSequence;
    }
    /**
     * 为证据接口返回本代 Matcher 分片。
     */
    @Override public int nativeMatcherShardId() {
        return directShard;
    }
    /**
     * 直接读取本代命令 UUID 的高低位进行比较，调用方须在有效事件代次内使用。
     * @param most 命令 UUID 高六十四位
     * @param least 命令 UUID 低六十四位
     */
    @Override public boolean nativeMatches(long most, long least) {
        return directCommandId == null ? most == 0 && least == 0
                : directCommandId.getMostSignificantBits() == most
                && directCommandId.getLeastSignificantBits() == least;
    }
    /**
     * 为结果接口借用本代原生结果引用，合成拒绝时为空；调用方负责先遵守发布边界。
     */
    public exchange.core2.core.common.MatcherResult nativeMatcherResult() {
        return directNativeResult;
    }
    /**
     * 合成准入拒绝返回空事件列表，其他结果借用原生事件列表；不复制事件。
     */
    @Override public java.util.List<exchange.core2.core.common.MatcherResult.MatcherEvent> matcherEvents() {
        if (directSyntheticResultCode != null) return java.util.List.of();
        return directNativeResult.events();
    }
    /**
     * 检查本事件是否持有原生结果或合成拒绝码，执行故障本身不算可推进证据的结果。
     */
    private boolean hasEventOwnedResult() {
        return directNativeResult != null || directSyntheticResultCode != null;
    }
    /**
     * 要求存在活动计划且直达结果已发布；如有执行故障，以其为原因抛出，防止当成普通业务拒绝。
     */
    private void requireDirectResult() {
        if (batchPlanCount == 0 || !directPublished) throw new IllegalStateException("direct result is not published");
        if (directFailure != null) throw new IllegalStateException("direct matcher failed", directFailure);
    }

    /**
     * 校验普通下单准入 Lane，并为活动事件设置一次需要准入回执的边界。
     * @param laneId 当前操作的账户 Lane 编号
     */
    void admissionRoute(int laneId) {
        if (batchPlanCount == 0 || admissionRequired || laneId < 0) {
            throw new IllegalStateException("invalid place admission route");
        }
        admissionRequired = true;
    }

    /**
     * Owner 在交给 Matcher 前预约逻辑派发状态，仅设置预约标记，不接触任何 Lane 队列。
     */
    public void reserveMatcherPublication() {
        if (batchPlanCount == 0 || dispatched || directPublished) {
            throw new IllegalStateException("invalid Matcher publication reservation");
        }
        dispatched = true;
    }

    /**
     * 仅在事件完成且 changes 已交出后清理命令、准入、结果及槽引用，保留批量数组和财务槽容量；下一代 prepareDirect 再重置完成位及 Owner 缓存，不能把此方法当等待在途消费者。
     */
    void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete matcher settlement");
        }
        if (treasurySettlement != null) treasurySettlement.clear();
        cancellation = false; directPublished = false; directFailure = null; dispatched = false;
        queuedLaneMask = 0;
        expandLaneRoute = false;
        matcherPublicationDeferred = false;
        cancellationUserId = 0;
        replacement = null;
        replacementAssetId = 0;
        sourceTrigger = null;
        triggeredAt = 0;
        matcherCompletionRoute = null;
        matcherCompletionShard = -1;
        admissionRequired = false;
        admissionOrder = null;
        admissionUserId = admissionOrderId = 0;
        admissionResolved = false;
        admissionAccepted = false;
        admissionResultCode = CoreResultCode.NONE.wireCode();
        directCoreSequence = 0;
        directCommandId = null;
        authorizedCancellations = java.util.List.of();
        directNativeResult = null;
        directMatcherSequence = directLastMatcherSequence = 0;
        directNativeItemCount = 0;
        directAcceptedCancellationCount = 0;
        directNativeAccepted = directNativeStateChanged = false;
        directSyntheticResultCode = null;
        routedLaneMask = 0;
        resultTarget = null;
        if (batchStorage != null) batchStorage.clear();
        batchPlanCount = 0;
        runtime = null;
        identities = null;
        treasuryTrades = false;
        collected = false;
    }

    /**
     * 下单步骤 4.4：所属 Lane 应用本代各计划、整理终态并发布完成位；仅消费票据而无业务变化的路由直接回执。准入拒绝不走正常成交计算，其他路径按撤单、改单和产品结算处理，非零提交序号才推进账户水位。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     */
    public void execute(AccountLaneState lane) {
        requireDirectResult();
        // 当前执行或检查的账户 Lane 编号。
        int laneId = lane.laneId();
        // 当前执行账户 Lane 的单个位图。
        long laneMask = 1L << laneId;
        if ((requiredLaneMask & laneMask) == 0) {
            if ((routedLaneMask & laneMask) != 0) {
                publishCompletion(lane);
                return;
            }
            throw new IllegalStateException("matcher fact was routed to an unrelated account lane");
        }
        // 普通下单事实排在同一 Lane 的准入之后；准入拒绝时只整理完成回执，
        // 不执行正常成交资金计算，让 Owner 能退休已提交的事件。
        if (admissionRequired && !admissionResolved) {
            throw new IllegalStateException("place admission receipt is missing");
        }
        if (admissionRequired && !admissionAccepted) {
            changes().prepareLaneTerminal(laneId, identities, lane, directCoreSequence);
            publishCompletion(lane);
            return;
        }
        runtime.enterMatcherSettlementScope(lane, changes());
        try {
            // 当前账户独占的财务差额，或清理及合并过程中借用的财务槽。
            RuntimeTreasuryDelta delta = !treasuryTrades
                    ? EMPTY_TREASURY_DELTA : touchedLaneTreasuryDeltas[laneSlot(laneId)];
            for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; index < batchPlanCount; index++) {
                // 本代有效前缀中当前需要由所属 Lane 应用的计划。
                MatcherSettlementPlan batchPlan = batchStorage.plans[index];
                if ((batchPlan.requiredLaneMask() & laneMask) == 0) continue;
                applyPlan(lane, laneId, batchPlan, batchStorage.instruments[index],
                        batchStorage.baseAssetIds[index], batchStorage.quoteAssetIds[index],
                        batchStorage.settleAssetIds[index], delta,
                        batchStorage.admittedOrders[index]);
                if (commitSequence != 0 || cancellation) {
                    runtime.stampMatcherOrders(
                            lane, batchPlan, commitTimestamp, commitClusterPosition);
                }
            }
            changes.prepareLaneTerminal(laneId, identities, lane, directCoreSequence);
            if (resultTarget != null
                    && laneId == runtime.topology().accountLaneId(plan().activeUserId())) {
                LaneOrderResultTarget.capture(resultTarget, changes.laneDeltas[laneId], identities, lane);
            }
            if (commitSequence != 0) {
                lane.applied(directShard, commitSequence);
                lane.committed(directShard, commitSequence);
                runtime.publishLaneCommittedSequence(lane);
            }
        } finally {
            runtime.exitMatcherSettlementScope(lane, changes);
        }
        // 每个 Lane 独立发布自己的完成位，Owner 核对事件整体完成边界，
        // 避免所有账户线程竞争写同一个原子 OR 位图。
        publishCompletion(lane);
    }

    /**
     * Lane 记录操作并以 release 发布自己独立的完成标记，拒绝重复完成；先保存通知运行时，发布后只用局部值发出就绪通知。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     */
    private void publishCompletion(AccountLaneState lane) {
        // 当前执行或检查的账户 Lane 编号。
        int laneId = lane.laneId();
        // 发布完成前提前保存的运行时通知目标，完成位发布后只使用此局部引用。
        TradingAccountRuntime completionRuntime = runtime;
        lane.recordSettlementOperation();
        // 该账户完成标记在间隔数组中的实际下标。
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("account lane completed the same matcher fact twice");
        }
        LONGS.setRelease(completedLanes, completionOffset, 1L);
        completionRuntime.publishDirectMatcherSettlementReady(laneId);
    }

    /**
     * 所属 Lane 先处理直接撤单或改单，再核对本地订单、应用已接受前置撤单、拒单或产品成交；完成待结算预留及准入值发布，最后写本 Lane 负责的触发终态。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     * @param laneId 当前操作的账户 Lane 编号
     * @param value 当前账户要应用的结算计划
     * @param valueInstrument 本计划对应的规范币对对象
     * @param valueBaseAssetId 本计划对应的基础资产编号
     * @param valueQuoteAssetId 本计划对应的计价资产编号
     * @param valueSettleAssetId 本计划对应的结算资产编号
     * @param delta 本 Lane 独占的系统财务差额槽，无成交时使用只读空值
     * @param admissionVersion 命令保存的准入订单值，可为空；最终权威后值从所属 Lane 读取
     */
    private void applyPlan(AccountLaneState lane, int laneId, MatcherSettlementPlan value,
                           CoreInstrument valueInstrument, int valueBaseAssetId,
                           int valueQuoteAssetId, int valueSettleAssetId, RuntimeTreasuryDelta delta,
                           OrderRuntime admissionVersion) {
        if (cancellation) {
            if (!value.rejectedTaker())
                runtime.cancelOrderInLane(lane, value.activeUserId(), value.takerOrderId(),
                        commitTimestamp, commitClusterPosition);
            return;
        }
        if (replacement != null) {
            applyReplacement(lane, value);
            if (!accepted()) return;
        }
        value.validateDirectLane(lane, runtime, identities, valueInstrument);
        for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; replacement == null && index < value.preCancellationCount(); index++) {
            // 当前授权、取消或发布最终值的原语订单编号。
            long orderId = value.preCancellationOrderId(index);
            // 当前前置撤单编号在本 Lane 中的权威订单，仅开放时应用取消。
            OrderRuntime order = lane.order(orderId);
            if (order != null && order.status() == CoreOrderStatus.OPEN) {
                runtime.cancelOrderInLane(lane, order.userId(), orderId);
            }
        }
        if (value.rejectedTaker()) {
            if (lane.laneId() == runtime.accountLaneId(value.activeUserId()))
                runtime.rejectOrderInLane(lane, value.activeUserId(), value.takerOrderId(), commitTimestamp, commitClusterPosition);
        } else if (valueInstrument.contractType().productLine().isDerivative()) {
            RuntimeDerivativeMatchProcessor.applyLane(value.takerOrderId(), value, lane, laneId,
                    runtime, identities, valueInstrument, valueSettleAssetId, delta, commitTimestamp, commitClusterPosition);
        } else {
            RuntimeSpotMatchProcessor.applyLane(value.takerOrderId(), value, lane, laneId,
                    runtime, valueInstrument, valueBaseAssetId, valueQuoteAssetId, delta, commitTimestamp, commitClusterPosition);
        }
        runtime.completeMatcherPendingReservations(lane, value, admissionVersion);
        publishAdmittedState(lane, value, admissionVersion);
        // 本计划准备的触发单终态，仅其用户所属 Lane 写入。
        var trigger = value.completedTrigger();
        if (trigger != null && lane.laneId() == runtime.accountLaneId(trigger.userId())) runtime.putTriggerOrder(lane, trigger);
    }

    /**
     * 把本代准入用户、订单及预留的 Lane 最终值加入同一变化缓冲，包括没有成交的挂单；只在用户所属 Lane 执行，已有变化键按缓冲规则合并。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     * @param value 当前账户要应用的结算计划
     * @param admissionVersion 命令保存的准入订单值，可为空；最终权威后值从所属 Lane 读取
     */
    private void publishAdmittedState(
            AccountLaneState lane, MatcherSettlementPlan value, OrderRuntime admissionVersion) {
        // 命令或计划保留的准入订单引用，普通不可变准入路径可以为空。
        OrderRuntime admitted = admissionVersion == null ? value.admittedTaker() : admissionVersion;
        // 本事件是否持有已经接受的普通下单不可变准入输入。
        boolean eventAdmission = admissionRequired && admissionAccepted && admissionOrder != null;
        // 当前要发布准入最终值或应用改单的用户编号。
        long userId = admitted != null ? admitted.userId()
                : eventAdmission ? admissionUserId : value.activeUserId();
        // 当前授权、取消或发布最终值的原语订单编号。
        long orderId = admitted != null ? admitted.orderId()
                : eventAdmission ? admissionOrderId : value.takerOrderId();
        if ((admitted == null && !eventAdmission)
                || lane.laneId() != runtime.accountLaneId(userId)) return;
        runtime.publishUser(lane, userId, lane.user(userId));
        runtime.publishOrder(lane, orderId, lane.order(orderId));
        runtime.publishReservation(lane, orderId, lane.reservation(orderId));
    }

    /**
     * 只在改单用户所属 Lane 核对原订单及用户版本，先执行已经接受的前置撤单；新单接受后准备客户身份、捕获余额前后值并放置新订单和预留，拒绝的新单不落账户。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     * @param value 当前账户要应用的结算计划
     */
    private void applyReplacement(AccountLaneState lane, MatcherSettlementPlan value) {
        if (lane.laneId() != runtime.accountLaneId(replacement.userId())) return;
        // 当前要发布准入最终值或应用改单的用户编号。
        long userId = replacement.userId();
        // 改单原订单的 Lane 权威值，版本必须仍等于准入时记录。
        OrderRuntime original = lane.order(replacement.originalOrderId());
        // 改单用户的 Lane 权威账户值，版本必须仍与准入输入一致。
        UserRuntime user = lane.user(userId);
        if (original == null || original.revision() != replacement.originalOrderRevision()
                || user == null || user.revision() != replacement.userRevision())
            throw new IllegalStateException("replace admission changed before Lane execution: order="
                    + (original == null ? "missing" : original.revision()) + '/'
                    + replacement.originalOrderRevision() + " user="
                    + (user == null ? "missing" : user.revision()) + '/' + replacement.userRevision());
        for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; index < value.preCancellationCount(); index++)
            runtime.cancelOrderInLane(lane, userId, value.preCancellationOrderId(index),
                    commitTimestamp, commitClusterPosition);
        if (!accepted()) return;
        // 本事件替换订单命令槽中的新单输入值。
        OrderRuntime prepared = batchStorage.admittedOrders[0];
        if (lane.order(replacement.originalOrderId()).status() != CoreOrderStatus.CANCELED)
            throw new IllegalStateException("accepted replacement did not cancel original order");
        // 在所属 Lane 准备得到的新订单客户身份原语键。
        long clientKey = RuntimeIdentityRegistry.clientKeyValue(
                identities.prepareClientKeyInLane(lane, userId, prepared.clientOrderId()));
        runtime.accountRollback().captureBalanceBefore(lane, userId, replacementAssetId);
        runtime.placeOrderInLane(lane, userId, replacement.resolved(), directCommandId,
                replacement.requiredReservationUnits(), clientKey, prepared.symbolId(), replacementAssetId,
                directCoreSequence, prepared, commitTimestamp, commitClusterPosition);
        runtime.publishUser(lane, userId, lane.user(userId));
        runtime.publishOrder(lane, prepared.orderId(), lane.order(prepared.orderId()));
        runtime.publishReservation(lane, prepared.orderId(), lane.reservation(prepared.orderId()));
        runtime.accountRollback().captureBalanceAfter(lane, userId, replacementAssetId);
    }

    /**
     * 按实际参与 Lane 的紧凑顺序保留财务差额槽，容量不足才扩展；每次真实结果确定后清空所有保留槽，无成交复用也清空旧金额。
     * @param laneMask 准备时已知的账户 Lane 路由位图
     * @param trades 本代实际计划中是否存在成交
     */
    private void prepareTreasuryDeltas(long laneMask, boolean trades) {
        if (trades) {
            // 本代实际业务 Lane 数量，决定紧凑财务槽的有效前缀。
            int touchedLaneCount = Long.bitCount(laneMask);
            // 当前事件已经保留的财务差额槽数组容量。
            int capacity = touchedLaneTreasuryDeltas == null ? 0 : touchedLaneTreasuryDeltas.length;
            if (capacity < touchedLaneCount) {
                touchedLaneTreasuryDeltas = capacity == 0
                        ? new RuntimeTreasuryDelta[touchedLaneCount]
                        : java.util.Arrays.copyOf(touchedLaneTreasuryDeltas, touchedLaneCount);
                for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = capacity; index < touchedLaneCount; index++) {
                    touchedLaneTreasuryDeltas[index] = new RuntimeTreasuryDelta();
                }
            }
        }
        // 实际事实确定后每代清空一次，包括上一代有成交而本代无成交的复用。
        if (touchedLaneTreasuryDeltas != null)
            for (RuntimeTreasuryDelta /* 当前账户独占的财务差额，或清理及合并过程中借用的财务槽。 */ delta : touchedLaneTreasuryDeltas) delta.clear();
    }

    /**
     * 只遍历本代有效计划前缀，任一项有成交则返回 true，不读取保留容量中的旧尾部。
     * @param plans 本事件保留的结算计划数组，仅遍历有效前缀
     * @param planCount 本代有效计划项数
     */
    private static boolean hasTrade(MatcherSettlementPlan[] plans, int planCount) {
        for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; index < planCount; index++) if (plans[index].tradeCount() != 0) return true;
        return false;
    }

    /**
     * Owner 仅在发布结束后以 acquire 读取尚未观察完成的路由 Lane 标记，并缓存已完成位，不回退本代观察结果。
     */
    public long completedLaneMask() {
        if (!ready()) return 0;
        // 本次尚未观察、检查或内联执行的路由 Lane 位图。
        long remaining = routedLaneMask() & ~observedCompletedLaneMask;
        while (remaining != 0) {
            // 当前执行或检查的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(remaining);
            // 当前账户 Lane 在位图中的单个位。
            long bit = 1L << laneId;
            remaining &= ~bit;
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0)
                observedCompletedLaneMask |= bit;
        }
        return observedCompletedLaneMask;
    }

    /**
     * 账本只读检查全部路由 Lane 的 release 完成标记，既不运行账户工作，也不修改 Owner 的观察缓存。
     */
    boolean accountLanesCompleteForLedger() {
        if (!ready()) return false;
        // 本次尚未观察、检查或内联执行的路由 Lane 位图。
        long remaining = routedLaneMask();
        while (remaining != 0) {
            // 当前执行或检查的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) == 0) return false;
        }
        return true;
    }

    /**
     * 事件就绪且没有独立账户线程时，按路由推进尚未完成的 Lane；不在此代替已启动的账户工作线程执行。
     */
    public void ensureLanesExecuted() {
        if (!ready() || runtime == null || runtime.accountLanesStarted() || !dispatched) return;
        // 本次尚未观察、检查或内联执行的路由 Lane 位图。
        long remaining = routedLaneMask() & ~completedLaneMask();
        while (remaining != 0) {
            // 当前执行或检查的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if ((queuedLaneMask & (1L << laneId)) == 0) {
                markLaneQueued(laneId);
            }
            execute(runtime.accountLane(laneId));
        }
    }

    /**
     * Owner 等发布结束并按需推进内联账户；全部路由回执到齐后完成本地无成交核对，再检查已登记财务工作，成功结果缓存在本代。完成仍不代替变化收集和按序提交。
     */
    public boolean complete() {
        if (ownerObservedComplete) return true;
        if (!ready()) return false;
        ensureLanesExecuted();
        if (completedLaneMask() != routedLaneMask()) return false;
        if (treasurySettlement != null && !treasurySettlement.completed) {
            if (treasurySettlement.localNoTrade) {
                treasurySettlement.completeLocalNoTrade();
            } else if (treasurySettlement.ready()) {
                treasurySettlement.apply(runtime.treasury());
            }
        }
        boolean done = treasurySettlement == null || treasurySettlement.completed;
        if (done) ownerObservedComplete = true;
        return done;
    }
    /**
     * 借用首个有效结算计划，空代次由带下标入口拒绝。
     */
    public MatcherSettlementPlan plan() { return plan(0); }
    /**
     * 返回本代有效计划数，不是批量存储容量。
     */
    public int planCount() { return batchPlanCount; }
    /**
     * 校验下标位于本代有效项前缀后借用对应计划，不暴露旧容量尾部。
     * @param index 本代有效计划或原生成交结果槽的下标
     */
    public MatcherSettlementPlan plan(int index) {
        if (index < 0 || index >= planCount()) throw new IndexOutOfBoundsException(index);
        return batchStorage.plans[index];
    }
    /**
     * 借用本代保留的结算变化缓冲，已交出或未准备时失败。
     */
    MatcherSettlementChanges changes() {
        if (changes == null) throw new IllegalStateException("matcher settlement changes are unavailable");
        return changes;
    }

    /**
     * 检查当前事件是否仍持有变化缓冲。
     */
    boolean hasChanges() { return changes != null; }
    /**
     * 把变化缓冲引用交给收集方并从事件解除，不等待消费者；调用方须先满足既有完成边界。
     */
    public MatcherSettlementChanges takeChanges() {
        // 准备交给收集方并从事件解除的变化缓冲引用。
        MatcherSettlementChanges value = changes();
        changes = null;
        return value;
    }

    /**
     * 只有事件完成才允许一次收集；已登记账本工作时禁止非空资金目标并返回空财务差额，其他路径合并 Lane 差额。未完成返回空引用，本方法不向 target 写资金。
     * @param target 既有资金接收参数；本方法不写入，已登记账本工作时必须为空
     */
    public RuntimeTreasuryDelta collectTreasuryDelta(RuntimeFundsAccumulator target) {
        if (!complete()) return null;
        if (collected) throw new IllegalStateException("matcher settlement event was already collected");
        if (hasLedgerSettlement() && target != null)
            throw new IllegalStateException("Ledger funds remain on SystemLedgerLane");
        collected = true;
        if (treasurySettlement != null && treasurySettlement.queued) return EMPTY_TREASURY_DELTA;
        return mergeTreasuryDeltas();
    }

    /**
     * 无成交返回共用空差额；有成交只合并当前业务 Lane 对应的紧凑有效槽，以首个槽为结果，不创建副本或读取容量尾部。
     */
    private RuntimeTreasuryDelta mergeTreasuryDeltas() {
        if (!treasuryTrades) return EMPTY_TREASURY_DELTA;
        // 用于原位归集本代 Lane 财务差额的首个参与槽，没有新建资金镜像。
        RuntimeTreasuryDelta aggregate = null;
        // 本代实际业务 Lane 数量，决定紧凑财务槽的有效前缀。
        int touchedLaneCount = Long.bitCount(requiredLaneMask);
        for (/* 当前准备、应用、清理或合并的有效计划、结果或财务槽下标。 */ int index = 0; index < touchedLaneCount; index++) {
            // 当前账户独占的财务差额，或清理及合并过程中借用的财务槽。
            RuntimeTreasuryDelta delta = touchedLaneTreasuryDeltas[index];
            if (aggregate == null) aggregate = delta;
            else aggregate.merge(delta);
        }
        if (aggregate == null) throw new IllegalStateException("matcher settlement event has no lane delta");
        return aggregate;
    }

    /**
     * 下单步骤 4.5：保留一个财务槽并尝试登记，无成交可在账户完成后本地核对，否则投递账本邮箱。满队列返回 false 保留同槽供重试，已成功登记不允许重复投递。
     * @param ledger 当前运行时使用的系统账本执行边界
     */
    /**
     * 下单步骤 4.5：保留一个财务槽并就地或在账户完成后应用差额与资金守恒核对。已成功登记不允许重复投递。
     * @param treasury 当前运行时由 Owner 独占持有的系统财库
     */
    public boolean submitTreasurySettlement(TreasuryRuntime treasury) {
        if (treasury == null || runtime == null || changes == null || collected)
            throw new IllegalStateException("Ledger settlement requires retained isolated account facts");
        if (treasurySettlement == null) treasurySettlement = new TreasurySettlement();
        if (treasurySettlement.queued) throw new IllegalStateException("Treasury settlement was already queued");
        ownerObservedComplete = false;
        treasurySettlement.queued = true;
        if (ready() && !treasuryTrades) {
            treasurySettlement.localNoTrade = true;
            if (completedLaneMask() == routedLaneMask()) {
                treasurySettlement.completeLocalNoTrade();
                ownerObservedComplete = true;
            }
            return true;
        }
        if (accountLanesCompleteForLedger()) {
            treasurySettlement.apply(treasury);
            if (completedLaneMask() == routedLaneMask()) {
                ownerObservedComplete = true;
            }
        }
        return true;
    }

    /**
     * 在账户已就绪时推进内联财务结算并核对资金守恒。
     */
    public void tryAdvanceLedgerSettlement(TreasuryRuntime treasury) {
        if (treasurySettlement != null && treasurySettlement.queued && !treasurySettlement.completed) {
            if (accountLanesCompleteForLedger()) {
                if (ready() && !treasuryTrades) {
                    treasurySettlement.completeLocalNoTrade();
                } else {
                    treasurySettlement.apply(treasury);
                }
            }
        }
    }

    /**
     * 返回财务工作是否已登记成功，包括本地无成交与就地完成路径。
     */
    public boolean hasLedgerSettlement() { return treasurySettlement != null && treasurySettlement.queued; }

    /**
     * 本事件保留的唯一财务任务槽，等账户完成后在账本所有权内应用差额及核对资金，完成位发布后不再访问事件。
     */
    private final class TreasurySettlement {
        /** 本财务槽独占的原语资金核对暂存，完成前校验守恒并清空，不是第二份资金账本。 */
        private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
        /** 本代财务工作已成功登记，包含真实邮箱及本地或内联完成路径。 */
        private boolean queued;
        /** 本代选择账户完成后就地核对无成交资金的标记。 */
        private boolean localNoTrade;
        /** 本代财务处理已结束的完成标记。 */
        private boolean completed;

        /**
         * 账本消费者只在所有路由账户完成且发布租约已交出后应用财务事实。
         */
        public boolean ready() { return accountLanesCompleteForLedger(); }

        /**
         * 账户已完成的无成交路径就地汇集余额资金增量并校验守恒，清空临时资金后发布财务完成，不修改 Treasury。
         */
        void completeLocalNoTrade() {
            if (completed) return;
            changes.appendFundsDelta(requiredLaneMask, funds);
            funds.requireConserved(false);
            funds.clear();
            completed = true;
        }

        /**
         * 在账本所有权内应用 Lane 财务差额，汇集账户与财库资金并校验守恒，更新持仓索引，最后发布完成位并用提前保存的运行时通知；发布后不再读本槽或事件字段。
         * @param treasury 当前账本执行方独占的原系统财库
         */
        public void apply(TreasuryRuntime treasury) {
            if (!ready() || completed || treasury.hasChangedValues())
                throw new IllegalStateException("invalid Ledger financial scope");
            mergeTreasuryDeltas().apply(treasury);
            changes.appendFundsDelta(requiredLaneMask, funds);
            treasury.appendFundsDelta(funds);
            funds.requireConserved(false);
            funds.clear();
            runtime.applyLedgerSettlementPositions(changes, requiredLaneMask, identities);
            treasury.clearChangedKeys();
            // Owner 观察完成后可以立即复用财务槽，发布后不再访问槽或事件字段。
            completed = true;
        }

        /**
         * 确认已登记账本工作完成且资金临时缓冲为空后，重置本槽标记，保留容量。
         */
        void clear() {
            if (queued && !completed) throw new IllegalStateException("Ledger still owns this settlement");
            if (!funds.isEmpty()) throw new IllegalStateException("Ledger funds scratch was not cleared");
            queued = localNoTrade = completed = false;
        }
    }

    /**
     * 把实际业务 Lane 编号转换为按参与位从低到高紧凑排列的财务槽下标，非参与 Lane 拒绝。
     * @param laneId 当前操作的账户 Lane 编号
     */
    private int laneSlot(int laneId) {
        // 当前需要映射到紧凑财务槽的账户 Lane 单个位。
        long laneBit = 1L << laneId;
        if ((requiredLaneMask & laneBit) == 0) {
            throw new IllegalStateException("matcher fact has no treasury slot for lane " + laneId);
        }
        return Long.bitCount(requiredLaneMask & (laneBit - 1));
    }

    /** Owner 已确认本代事件完成的缓存，下一代准备时重置。 */
    private boolean ownerObservedComplete;

    /**
     * 清空 Owner 观察缓存和完成缓存，按 Lane 数准备带间隔的完成数组；容量相同时只重置各 Lane 的有效完成位置。
     * @param laneCount 运行时拓扑中的账户 Lane 数量
     */
    private void resetCompletions(int laneCount) {
        observedCompletedLaneMask = 0;
        ownerObservedComplete = false;
        // 按账户 Lane 数乘完成位间隔得到的数组长度，使用精确乘法检查。
        int length = Math.multiplyExact(laneCount, CACHE_LINE_LONGS);
        if (completedLanes == null || completedLanes.length != length) {
            completedLanes = new long[length];
        } else {
            for (/* 当前执行或检查的账户 Lane 编号。 */ int laneId = 0; laneId < laneCount; laneId++) {
                completedLanes[laneId * CACHE_LINE_LONGS] = 0L;
            }
        }
    }
}
