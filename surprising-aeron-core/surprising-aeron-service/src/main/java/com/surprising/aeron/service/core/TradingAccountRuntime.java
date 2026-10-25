package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.account.LaneThread;
import com.surprising.aeron.bootstrap.account.AccountLaneLifecycle;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.account.ControlLaneDispatcher;
import com.surprising.aeron.service.account.LaneBalancePatches;
import com.surprising.aeron.service.account.RuntimeAccountRollback;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.core.change.LaneCommitEvent;
import com.surprising.aeron.service.core.change.OwnerIndexedChanges;
import com.surprising.aeron.service.core.change.RuntimeChangeBuffer;
import com.surprising.aeron.service.core.change.RuntimeIndexedChangeBuffer;
import com.surprising.aeron.service.core.change.RuntimeOrderChangeBuffer;
import com.surprising.aeron.service.core.change.RuntimePositionChangeBuffer;
import com.surprising.aeron.service.core.command.AccountLaneOperationType;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.RuntimeFactIndexes;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.admission.PendingReservationTracker;
import com.surprising.aeron.service.order.admission.PlaceAdmissionEvent;
import com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.order.admission.PlaceBatchIntentSource;
import com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.LaneCancelEvent;
import com.surprising.aeron.service.order.state.LaneOrderResultTarget;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.position.RuntimePositionIndexValue;
import com.surprising.aeron.service.realtime.state.RealtimeStateCapture;
import com.surprising.aeron.service.realtime.state.RealtimeUserSnapshot;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.risk.RiskLiquidationBatch;
import com.surprising.aeron.service.risk.RiskScanCoordinator;
import com.surprising.aeron.service.risk.RiskScanRuntime;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.settlement.FundsPosting;
import com.surprising.aeron.service.settlement.MatcherSettlementChanges;
import com.surprising.aeron.service.settlement.MatcherSettlementDispatcher;
import com.surprising.aeron.service.settlement.MatcherSettlementEvent;
import com.surprising.aeron.service.settlement.MatcherSettlementPlan;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.concurrent.LaneSequenceQueue;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2LongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 交易账户与账本的运行时：按产品线和 Lane 保存可变业务状态，Owner 负责命令交接、变化收集和完成水位。账户、Matcher 及账本各有实际执行边界；构造先准备状态和复用资源，活动阶段通过派发或明确的所有权交接访问账户。
 */
public final class TradingAccountRuntime implements AutoCloseable {
    /** Owner 冷查询或控制路径复用的杠杆查找探针，正式映射仍保存 CoreLeverageKey。 */
    private final LeverageLookup ownerLeverageLookup = new LeverageLookup();

    /**
     * 查杠杆时复用的可变探针，模拟正式杠杆键的哈希与字段比较；只在所属执行线程查找，不作为映射中的持久键。
     */
    public static final class LeverageLookup {
        /** 本次杠杆查找的用户身份，下次 set 覆盖。 */
        long userId;
        /** 本次杠杆查找的外部标的 ID，clear 时释放引用。 */
        String instrumentId;
        /** 本次杠杆查找的保证金模式，clear 时释放引用。 */
        CoreMarginMode marginMode;

        /**
         * 替换本次查杠杆所用的用户、标的和保证金模式；这个可变探针只用于查找，不放入持久化映射。
         * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
         * @param instrumentId 本产品线的外部标的 ID
         * @param marginMode 用于区分杠杆业务键的保证金模式
         */
        public void set(long userId, String instrumentId, CoreMarginMode marginMode) {
            this.userId = userId;
            this.instrumentId = instrumentId;
            this.marginMode = marginMode;
        }

        /**
         * 释放上次查找保存的标的和模式引用；用户数值可留待下次 set 覆盖，不再用已清理探针查找。
         */
        public void clear() {
            instrumentId = null;
            marginMode = null;
        }

        /**
         * 按 CoreLeverageKey 的 record 字段顺序计算相同哈希，避免为每次成交查找创建新键。
         */
        @Override
        public int hashCode() {
            // 按 CoreLeverageKey 的 record 字段顺序计算相同哈希。
            // 直接混合各字段，避免 Objects.hash 的可变参数数组。
            // 按正式杠杆 record 键字段顺序累计的哈希中间值
            int hash = 31 * Long.hashCode(userId) + instrumentId.hashCode();
            return 31 * hash + marginMode.hashCode();
        }

        /**
         * 只与正式 CoreLeverageKey 比较三个业务字段，让映射查找命中已有键；它不是可供任意对象双向比较的通用键。
         * @param other 要与当前探针比较的对象，只识别正式 CoreLeverageKey
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof CoreLeverageKey(/* 正式杠杆键解构出的用户身份，与当前探针比较 */ long id, /* 正式杠杆键解构出的外部标的 ID，与当前探针比较 */ String storedInstrumentId, /* 正式杠杆键解构出的保证金模式，与当前探针比较 */ CoreMarginMode mode)
                    && userId == id
                    && instrumentId.equals(storedInstrumentId)
                    && marginMode == mode;
        }
    }

    /**  撮合后结算的派发与完成收集入口，沿用各产品现有结算规则。 借出唯一撮合结算派发与收集入口。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final MatcherSettlementDispatcher settlements = new MatcherSettlementDispatcher(this);

    /**  Owner 维护的在途订单预留序号与用户计数，Lane 完成后再释放。 借出唯一待完成预留跟踪器，不建立第二套计数。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final PendingReservationTracker pendingReservations = new PendingReservationTracker(this);

    /** 未完成资金划转的容量上限，沿用财库定义。 */
    public static final int MAX_PENDING_TRANSFERS = TreasuryRuntime.MAX_PENDING_TRANSFERS;
    /** 显式同步等待账户提交的 30 秒纳秒预算。 */
    static final long LANE_COMMIT_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    /** 变化身份缓冲触发压缩的数量阈值，固定为 512。 */
    static final int CHANGE_KEY_COMPACTION_THRESHOLD = 512;

    /**  本运行时所属产品线，投影或恢复前默认值为 U 本位永续。 返回当前运行时元数据中的产品线。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    /** 业务运行时修订，随当前命令及回滚边界维护。 */
    long revision;
    /**  构造时固定的账户与 Matcher 路由拓扑，恢复时需保持一致。 返回本运行时固定的 Lane 拓扑。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final LaneTopology topology;
    /** 各账户 Lane 的权威可变状态，活动阶段由所属执行线程拥有。 */
    final AccountLaneState[] accountLanes;
    /** 按 Lane 复用的用户路由临时列表，每次派发前清空，不保存第二份用户业务状态。 */
    final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] laneUserScratch;
    /** PIPELINED 账户工作对象数组，未启动或 FUSED 路径可为空槽。 */
    private final AccountLaneLifecycle laneLifecycle;
    private final CoreRuntimeConfiguration configuration;
    /** 账户准入完成后按 Matcher 分片设置的唤醒提示，业务结果仍在原事件。 */
    private final PaddedReadyMask accountAdmissionReadyShards;
    /** 各账户 Lane 的准入完成序号队列，用于唤醒精确命令槽。 */
    final LaneSequenceQueue[] placeAdmissionReadyQueues;
    /** 各账户 Lane 到 Owner 的普通结算完成序号队列。 */
    final LaneSequenceQueue[] matcherSettlementReadyQueues;
    /** 同步执行按 Lane 写入的复用结果数组，下一次调用可能覆盖返回内容。 */
    final Object[] laneMutationResultsScratch;
    /** 上一次结果数组实际写过的 Lane 位，下一轮只清这些旧结果。 */
    long laneMutationResultsMask;

    /** Owner 采样到的账户队列高水位，不保证捕获每一次入队峰值。 */
    final int[] accountLaneQueueHighWaterMarks;
    /** 每条 Lane 独立发布的提交序号槽，用 volatile 和前后填充隔开写入。 */
    final PaddedCommittedSequenceSlot[] publishedLaneCommittedSlots;
    /** Owner 从既有 Lane 回执收集的已发布持仓数量，不复制持仓对象。 */
    private final int[] publishedLanePositionCounts;
    /** Owner 汇总的已发布持仓数量观测值。 */
    private int publishedPositions;
    /** 每条 Lane 已派发的提交水位，与已完成水位一起防止乱序重复派发。 */
    final long[] dispatchedLaneCommitSequences;
    /** 各事件池 ArrayDeque 的初始容纳预算，固定为 1024，不是硬容量上限。 */
    private static final int EVENT_POOL_INITIAL_CAPACITY = 1024;
    /** 构造时为各常用事件池预建的对象数量，固定为 512。 */
    private static final int EVENT_POOL_PREALLOCATE_COUNT = 512;
    /** 账户提交事件复用池，消费结束并发布清理后再归还；池空时允许新建。 */
    final java.util.ArrayDeque<LaneCommitEvent> laneCommitEventPool = new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);

    /**
     * 跨线程就绪提示，每一位使用单独间隔的原始数组槽，减少不同 Lane 写相邻位置。读写使用 acquire/release，采样与清除不是原子的整体操作；提示可以合并，队列和事件完成标记才是实际完成依据。
     */
    public static final class PaddedReadyMask {
        /** 相邻提示槽间隔十六个 long，使不同位尽量不写同一缓存行。 */
        private static final int STRIDE = 16;
        /** long 数组元素访问器，提示读取使用 acquire、写入使用 release。 */
        private static final java.lang.invoke.VarHandle CELLS =
                java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
        /** 实际存储就绪提示的原始数组，非零表示该位被发布过。 */
        private final long[] cells;
        /** 本提示数组实际支持的位数，构造时限定在 1 至 64。 */
        private final int bitCount;

        /**
         * 建立最多 64 位的就绪提示数组，每位单独留出缓存行间隔。
         */
        public PaddedReadyMask() { this(Long.SIZE); }

        /**
         * 要求位数在 1 至 64 之间，按每位固定间隔分配原始数组。
         * @param bitCount 支持的就绪位数，必须在 1 至 64 之间
         */
        public PaddedReadyMask(int bitCount) {
            if (bitCount <= 0 || bitCount > Long.SIZE)
                throw new IllegalArgumentException("ready bit count must be in [1,64]");
            this.bitCount = bitCount;
            cells = new long[Math.multiplyExact(bitCount, STRIDE)];
        }

        /**
         * 校验位下标属于本数组，再换算为实际存储位置。
         * @param bitIndex 本提示数组内的零起始位下标
         */
        private int offset(int bitIndex) {
            if (bitIndex < 0 || bitIndex >= bitCount)
                throw new IllegalArgumentException("ready bit is outside its topology");
            return bitIndex * STRIDE;
        }

        /**
         * 先以 acquire 读取提示，已非零就返回假，否则以 release 写入一并返回真；它不是原子的读改写，并发发布可能合并或重复唤醒。
         * @param bitIndex 本提示数组内的零起始位下标
         */
        public boolean setBit(int bitIndex) {
            // 本位在带间隔原始数组中的实际位置
            int offset = offset(bitIndex);
            if ((long) CELLS.getAcquire(cells, offset) != 0) return false;
            CELLS.setRelease(cells, offset, 1L);
            return true;
        }

        /**
         * 校验位下标后，以 release 清除该位提示。
         * @param bitIndex 本提示数组内的零起始位下标
         */
        public void clearBit(int bitIndex) {
            CELLS.setRelease(cells, offset(bitIndex), 0L);
        }

        /**
         * 逐个清除给定掩码对应的提示；调用方保证掩码只包含本拓扑内的位，此处不逐位做范围校验。
         * @param bitsToClear 调用方保证属于本拓扑的待清除提示位集合
         */
        public void clearBits(long bitsToClear) {
            while (bitsToClear != 0) {
                // 当前正在采样或清除的提示位下标
                int bit = Long.numberOfTrailingZeros(bitsToClear);
                bitsToClear &= bitsToClear - 1;
                CELLS.setRelease(cells, bit * STRIDE, 0L);
            }
        }

        /**
         * 逐位以 acquire 采样就绪提示并合成掩码，不清除提示，也不构成所有业务完成状态的原子快照。
         */
        public long get() {
            // 本轮逐位采样得到的就绪提示掩码，不是全部事件完成的原子快照
            long ready = 0;
            for (/* 当前正在采样或清除的提示位下标 */ int bit = 0; bit < bitCount; bit++)
                if ((long) CELLS.getAcquire(cells, bit * STRIDE) != 0) ready |= 1L << bit;
            return ready;
        }

        /**
         * 逐位采样非零提示后清零并返回所见掩码；这里只收集唤醒线索，真实结果仍以队列或事件完成标记为准。
         */
        public long getAndClear() {
            // 本轮逐位采样得到的就绪提示掩码，不是全部事件完成的原子快照
            long ready = 0;
            for (/* 当前正在采样或清除的提示位下标 */ int bit = 0; bit < bitCount; bit++) {
                // 本位在带间隔原始数组中的实际位置
                int offset = bit * STRIDE;
                // 空轮询只读提示槽；确实读到非零提示才写入清零。
                if ((long) CELLS.getAcquire(cells, offset) != 0) {
                    CELLS.setRelease(cells, offset, 0L);
                    ready |= 1L << bit;
                }
            }
            return ready;
        }
    }

    /**
     * 提交序号之前的填充基类，用额外字段把频繁写入的序号与相邻对象数据隔开；填充不保存业务。
     */
    private static class PaddedCommittedSequenceLhsPadding {
        /**
         * p00：提交序号之前的填充字段，不保存业务值。
         * p01：提交序号之前的填充字段，不保存业务值。
         * p02：提交序号之前的填充字段，不保存业务值。
         * p03：提交序号之前的填充字段，不保存业务值。
         * p04：提交序号之前的填充字段，不保存业务值。
         * p05：提交序号之前的填充字段，不保存业务值。
         * p06：提交序号之前的填充字段，不保存业务值。
         * p07：提交序号之前的填充字段，不保存业务值。
         * p08：提交序号之前的填充字段，不保存业务值。
         * p09：提交序号之前的填充字段，不保存业务值。
         * p10：提交序号之前的填充字段，不保存业务值。
         * p11：提交序号之前的填充字段，不保存业务值。
         * p12：提交序号之前的填充字段，不保存业务值。
         * p13：提交序号之前的填充字段，不保存业务值。
         * p14：提交序号之前的填充字段，不保存业务值。
         */
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14;
    }

    /**
     * 在前置填充之后保存可见的已提交序号，由所属 Lane 发布、Owner 观察。
     */
    private static class PaddedCommittedSequenceValue extends PaddedCommittedSequenceLhsPadding {
        /** 所属 Lane 已提交到的 Core 序号，以 volatile 发布给 Owner。 */
        protected volatile long sequence;
    }

    /**
     * 在提交序号之后继续填充的完整槽位，减少不同 Lane 发布时的缓存行争用。
     */
    private static final class PaddedCommittedSequenceSlot extends PaddedCommittedSequenceValue {
        /**
         * p10：提交序号之后的填充字段，不保存业务值。
         * p11：提交序号之后的填充字段，不保存业务值。
         * p12：提交序号之后的填充字段，不保存业务值。
         * p13：提交序号之后的填充字段，不保存业务值。
         * p14：提交序号之后的填充字段，不保存业务值。
         * p15：提交序号之后的填充字段，不保存业务值。
         * p16：提交序号之后的填充字段，不保存业务值。
         * p17：提交序号之后的填充字段，不保存业务值。
         * p18：提交序号之后的填充字段，不保存业务值。
         * p19：提交序号之后的填充字段，不保存业务值。
         * p20：提交序号之后的填充字段，不保存业务值。
         * p21：提交序号之后的填充字段，不保存业务值。
         * p22：提交序号之后的填充字段，不保存业务值。
         * p23：提交序号之后的填充字段，不保存业务值。
         * p24：提交序号之后的填充字段，不保存业务值。
         */
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24;
    }

    /** 单笔下单准入事件池，输入和消费者都结束使用后才能归还。 */
    final java.util.ArrayDeque<PlaceAdmissionEvent> placeAdmissionEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    /** 改单准入事件池，避免复用仍被账户或账本持有的事件。 */
    private final java.util.ArrayDeque<ReplacementAdmissionEvent> replacementAdmissionEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    /** 批量下单准入事件池，专用变化槽由独立入口归还。 */
    final java.util.ArrayDeque<PlaceBatchAdmissionEvent> placeBatchAdmissionEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    public boolean accountLanesStarted() { return laneLifecycle.started(); }
    /** 是否明确启用 FUSED 执行方式，不代表账本也在 Owner 内联执行。 */
    private boolean fusedExecutionActive;

    /**
     * 在 Owner 上标记采用 FUSED 执行方式，不在此创建或启动工作线程。
     */
    public void enableFusedExecution() {
        assertOwner();
        this.fusedExecutionActive = true;
    }

    /**
     * 返回此前是否已标记 FUSED 执行方式。
     */
    public boolean isFusedExecution() {
        return fusedExecutionActive;
    }

    /** 本产品线按标的内部身份维护的标记价格。 */
    final IntObjectHashMap<MarkPriceRuntime> markPrices = new IntObjectHashMap<>();
    /** 本产品线各标的的风险扫描续步状态。 */
    final IntObjectHashMap<RiskScanRuntime> riskScans = new IntObjectHashMap<>();
    /** 唯一系统财库，账本激活后由账本线程写入，不建立 Owner 财库副本。 */
    final TreasuryRuntime treasury = new TreasuryRuntime();
    /** 既有结算应用路径复用的财库变化累加器。 */
    final RuntimeTreasuryDelta scratchTreasuryDelta = new RuntimeTreasuryDelta();
    /** 当前唯一财务控制命令槽，普通撮合事件使用各自已有的财务交接槽。 */
    private SystemLedgerCommand ledgerControl;
    /** 按标的内部身份保存的配置对象，控制边界内更新并保持已有标的对象身份稳定。 */
    final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>();
    /** 按用户及标的范围保存的定时全撤状态。 */
    final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new HashMap<>();
    /** 当前不可变费用政策版本，活动写入归 Lane 0，读者保留所见版本。 */
    private volatile FeePolicyBook feePolicies = FeePolicyBook.EMPTY;

    /** 结算编排复用的剩余数量映射，不另建权威订单状态。 */
    final LongLongHashMap matcherSettlementRemainingScratch = new LongLongHashMap();
    /** 结算编排复用的订单身份集合，不保存第二份订单对象。 */
    final LongHashSet matcherSettlementOrderScratch = new LongHashSet();
    /** 绑定的既有事实索引；活动账本拥有持仓等业务索引的修改权。 */
    private RuntimeFactIndexes factIndexes;
    /** 应用事实索引时配套使用的身份注册表。 */
    private RuntimeIdentityRegistry indexIdentities;
    /**
     * 在账本激活前绑定现有事实索引和身份表，避免活动账本期间更换持仓索引所有权。
     * @param indexes 绑定的既有事实索引，必须在账本激活前接入
     * @param identities 与本产品线状态配套的身份注册表
     */
    public void bindFactIndexes(RuntimeFactIndexes indexes, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (laneLifecycle.ledger() != null) throw new IllegalStateException("position indexes must bind before Ledger activation");
        factIndexes = java.util.Objects.requireNonNull(indexes);
        indexIdentities = java.util.Objects.requireNonNull(identities);
    }

    /** 借用恢复时已经绑定的同一组索引，Core 不再重新创建并替换。 */
    RuntimeFactIndexes factIndexes() {
        assertOwner();
        if (factIndexes == null) throw new IllegalStateException("position indexes are not bound");
        return factIndexes;
    }
    /**
     * 在 Owner 上查强平的路由元数据；尚未绑定事实索引时返回空。
     * @param id 本次查询或发布的业务实体身份
     */
    public com.surprising.aeron.service.core.index.LiquidationIndex.LiquidationRoute liquidationRoute(long id) {
        assertOwner(); return factIndexes == null ? null : factIndexes.liquidationRoute(id);
    }
    /** Owner 跟踪风险记录仍持有的持仓身份键，只维护身份寿命，不保存风险业务值。 */
    final LongHashSet riskPositionIdentities = new LongHashSet();
    /** 普通控制命令各 Lane 的变化交接容器，完成后由 Owner 收集。 */
    final LaneCommitDelta[] laneDeltas;

    /** 下一条强平状态身份，需要随快照恢复。 */
    long nextLiquidationId = 1;
    /**  成功行情输入推进的修订，由 Owner 写入，Lane 在控制任务交接后读取。 读取当前行情修订，不在此绑定线程或推进数值。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    long marketRevision;
    /** 本产品线风险扫描的开关和预算配置。 */
    CoreRiskScanControlView riskScanControl = CoreRiskState.defaultScanControl();
    /** 当前命令变化用户的原始身份集合，随提交或回滚边界清理。 */
    final LongHashSet changedUsers = new LongHashSet();
    /** 本次 Owner 提交是否涉及余额变化，明细仍由 Lane 余额补丁保存。 */
    private boolean balancesChanged;
    /** 当前命令的订单变化及索引信息，空值可表示删除，不替代账户订单表。 */
    final OwnerIndexedChanges<OrderRuntime, Void> changedOrders =
            new OwnerIndexedChanges<>(new RuntimeOrderChangeBuffer());
    /** 当前命令变化预留的订单身份集合。 */
    final LongHashSet changedReservations = new LongHashSet();
    /** 当前命令的持仓变化及索引值，供提交和回滚使用。 */
    final OwnerIndexedChanges<PositionRuntime, RuntimePositionIndexValue> changedPositions =
            new OwnerIndexedChanges<>(new RuntimePositionChangeBuffer());
    /**  当前命令的强平变化缓冲。 借出本命令强平变化缓冲，提交清理后不能继续使用旧内容。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeChangeBuffer<LiquidationRuntime> changedLiquidations =
            new RuntimeChangeBuffer<>();
    /** 当前命令变化标记价格的标的内部身份集合。 */
    final IntHashSet changedMarkPrices = new IntHashSet();
    /**  当前命令的风险快照变化及配套索引信息。 借出风险快照变化及配套索引缓冲。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeIndexedChangeBuffer<RiskSnapshotRuntime, Boolean> changedRiskSnapshots =
            new RuntimeIndexedChangeBuffer<>();
    /** 当前命令变化风险扫描的标的身份集合。 */
    final IntHashSet changedRiskScans = new IntHashSet();
    /** 当前命令变化的杠杆业务键集合。 */
    final HashSet<CoreLeverageKey> changedLeverages = new HashSet<>();
    /**  当前命令的算法单变化缓冲。 借出本命令算法单变化缓冲。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeChangeBuffer<CoreAlgoOrderState> changedAlgoOrders = new RuntimeChangeBuffer<>();
    /** 当前命令变化的定时全撤业务键集合。 */
    final HashSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers = new HashSet<>();
    /**  当前命令的触发单变化缓冲。 借出本命令触发单变化缓冲。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeChangeBuffer<CoreTriggerOrderState> changedTriggerOrders =
            new RuntimeChangeBuffer<>();
    /** 当前命令变化的费用政策身份集合。 */
    final LongHashSet changedFeePolicies = new LongHashSet();
    /**  各 Lane 的账户修改前值和余额补丁，失败时先恢复账户再恢复 Owner 视图。 借出各 Lane 账户回滚前后补丁管理对象。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeAccountRollback accountRollback;
    /**  当前是否处于订单批量修改范围，避免提前交还仍被批次使用的账户访问权。 返回当前是否处于订单批量修改范围。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean orderBatchMutationScope;
    /** Owner 当前明确进入的账户 Lane 范围，空表示尚未进入。 */
    private AccountLaneState ownerLaneScope;
    /** Owner 当前明确进入的撮合变化容器范围。 */
    private MatcherSettlementChanges ownerMatcherSettlementChangesScope;

    /** 预建的结算变化槽固定环，每个在途结算借一槽，Owner 收集完才归还。 */
    final MatcherSettlementChanges[] matcherSettlementChangesRing;
    /** 结算变化环的空闲槽号栈，与占用槽分开管理。 */
    private final int[] freeMatcherSettlementChangeSlots;
    /** 空闲槽号栈当前条数，借出递减、归还递增。 */
    private int freeMatcherSettlementChangeCount;
    /** 账户撤单事件复用池，消费者结束后才可回收。 */
    final java.util.ArrayDeque<LaneCancelEvent> laneCancelEventPool = new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);

    /** 本运行时唯一 Owner 线程，其他线程只在所属 Lane 或账本范围内操作权威状态。 */
    volatile Thread owner;
    /** Owner 线程字段的 VarHandle，供已有所有权绑定和交接使用。 */
    private static final java.lang.invoke.VarHandle OWNER_THREAD;
    /** 初始化 Owner 字段访问器；反射绑定失败阻止类继续初始化。 */
    static {
        try { OWNER_THREAD = java.lang.invoke.MethodHandles.lookup().findVarHandle(TradingAccountRuntime.class, "owner", Thread.class); }
        catch (/* 初始化 Owner 字段访问器失败，转成类初始化错误继续传播 */ ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    /**
     * 按当前配置构造被动账户运行时；是否允许特征化拓扑由 JVM 属性决定。
     */
    public TradingAccountRuntime() {
        this(CoreConfiguration.standaloneLaneTopology(), CoreRuntimeConfiguration.standalone());
    }

    /**
     * 校验拓扑并为每条账户 Lane 建立状态、通知队列、观测槽和复用缓冲，预建结算变化环及事件池；此处不建立活动账户或账本线程。
     * @param topology 本运行时固定使用的 Lane 数量、路由及队列配置
     */
    public TradingAccountRuntime(LaneTopology topology) {
        this(topology, CoreRuntimeConfiguration.standalone());
    }

    public TradingAccountRuntime(LaneTopology topology, CoreRuntimeConfiguration configuration) {
        this.configuration = java.util.Objects.requireNonNull(configuration);
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        accountAdmissionReadyShards = new PaddedReadyMask(topology.matchingEngineCount());
        settlementReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
        directSettlementReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
        placeAdmissionReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
        this.accountLanes = new AccountLaneState[topology.accountLaneCount()];
        // 结算变化环预建槽数，至少 64，其他取当前在途撮合配置
        int deltaRingCapacity = Math.max(64,
                configuration.maxPendingMatching());
        this.matcherSettlementChangesRing = new MatcherSettlementChanges[deltaRingCapacity];
        this.freeMatcherSettlementChangeSlots = new int[deltaRingCapacity];
        this.freeMatcherSettlementChangeCount = deltaRingCapacity;
        for (/* 当前数组、变化项或已准入订单的遍历下标 */ int index = 0; index < deltaRingCapacity; index++) {
            matcherSettlementChangesRing[index] = new MatcherSettlementChanges(accountLanes.length, index);
            freeMatcherSettlementChangeSlots[index] = index;
        }
        this.accountRollback = new RuntimeAccountRollback(this, topology.accountLaneCount());
        this.laneDeltas = new LaneCommitDelta[topology.accountLaneCount()];
        // 按账户 Lane 分配的临时用户列表数组，随后交给运行时字段复用
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] routedUsers =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[topology.accountLaneCount()];
        this.laneUserScratch = routedUsers;
        this.laneLifecycle = new AccountLaneLifecycle(accountLanes, topology, configuration,
                this::assertOwner, this::signalOwnerCompletion);
        this.placeAdmissionReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.matcherSettlementReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.laneMutationResultsScratch = new Object[topology.accountLaneCount()];
        this.accountLaneQueueHighWaterMarks = new int[topology.accountLaneCount()];
        this.publishedLaneCommittedSlots = new PaddedCommittedSequenceSlot[topology.accountLaneCount()];
        for (/* 当前预建对象或工作线程数组的下标 */ int i = 0; i < topology.accountLaneCount(); i++) {
            this.publishedLaneCommittedSlots[i] = new PaddedCommittedSequenceSlot();
        }
        this.publishedLanePositionCounts = new int[topology.accountLaneCount()];
        this.dispatchedLaneCommitSequences = new long[topology.accountLaneCount()];
        for (/* 当前按拓扑选中或从掩码取出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity(), configuration.laneInitialEntities());
            placeAdmissionReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            matcherSettlementReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            publishLaneCommittedSequence(accountLanes[laneId]);
            laneUserScratch[laneId] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(4);
            laneDeltas[laneId] = new LaneCommitDelta();
        }
        for (/* 当前预建对象或工作线程数组的下标 */ int i = 0; i < EVENT_POOL_PREALLOCATE_COUNT; i++) {
            laneCommitEventPool.addFirst(new LaneCommitEvent(accountLanes.length));
            placeAdmissionEventPool.addFirst(new PlaceAdmissionEvent());
            replacementAdmissionEventPool.addFirst(new ReplacementAdmissionEvent());
            placeBatchAdmissionEventPool.addFirst(new PlaceBatchAdmissionEvent());
            laneCancelEventPool.addFirst(new LaneCancelEvent());
        }
    }

    /**
     * 返回账户 Lane 数量。
     */
    public int laneCount() {
        return accountLanes.length;
    }

    /**
     * 按固定拓扑把用户身份路由到所属账户 Lane。
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     */
    public int accountLaneId(long userId) {
        return topology.accountLaneId(userId);
    }

    /**
     * 按用户路由取得权威账户 Lane，并校验该 Lane 引用及当前调用者所有权；不通过搜索线程身份决定用户归属。
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     */
    public AccountLaneState accountLaneForUser(long userId) {
        // 本轮路由、交接或回调正在处理的账户 Lane 引用
        AccountLaneState lane = accountLanes[topology.accountLaneId(userId)];
        requireLaneAccess(lane, userId);
        return lane;
    }

    /**
     * 仅按用户路由借出 Lane 引用，不做所有权检查或取得读取栅栏；调用方必须已经处于允许的读取边界。
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     */
    public AccountLaneState accountLaneForUserReadOnly(long userId) {
        return accountLanes[topology.accountLaneId(userId)];
    }

    /**
     * 要求 Lane 属于当前运行时、与该用户路由一致，且当前线程满足 Lane 所有权检查。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     */
    public void requireLaneAccess(AccountLaneState lane, long userId) {
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)
                || accountLanes[lane.laneId()] != lane)
            throw new IllegalStateException("account access crossed its owning Lane");
        lane.assertOwner();
    }

    /**
     * 要求 Lane 引用属于当前运行时，并检查当前线程是否拥有它。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     */
    private void requireLaneAccess(AccountLaneState lane) {
        if (lane == null || lane.laneId() < 0 || lane.laneId() >= accountLanes.length
                || accountLanes[lane.laneId()] != lane)
            throw new IllegalStateException("account access crossed its owning Lane");
        lane.assertOwner();
    }

    /** 交给所属 Matcher 分片的结算入口；不借出资源数组或允许替换工作线程。 */
    public void publishMatcherSettlement(int laneId, int shard,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent event) {
        laneLifecycle.worker(laneId).publishMatcherSettlement(shard, event);
    }

    /** 测试与控制任务按原 Owner 单生产者协议投递，不暴露线程资源。 */
    public void submitAccountCommand(int laneId, SettlementLaneWorker.Command command) {
        assertOwner();
        laneLifecycle.worker(laneId).submit(command);
    }

    public Thread accountWorkerThread(int laneId) { return laneLifecycle.worker(laneId).thread(); }

    /**
     * 在 Owner 上采样指定工作队列深度；未启动返回零，瞬时负差截为零，只供观测。
     * @param laneId 账户 Lane 的零起始编号
     */
    public int laneWorkerDepth(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) throw new IllegalArgumentException("invalid Lane id");
        // 本 Lane 对应的可空账户工作对象，沿用其队列或交接协议
        SettlementLaneWorker worker = laneLifecycle.worker(laneId);
        // 非fence观测可能跨过Matcher发布/消费；只约束瞬时负差，不影响业务队列。
        return worker == null ? 0 : Math.max(0, worker.depth());
    }

    /**
     * 在 Owner 上采样队列深度、容量和高水位并写入编码器；不取得账户访问范围，也不收集业务回执。
     * @param laneId 账户 Lane 的零起始编号
     * @param encoder 接收本轮观测指标的协议编码器
     */
    public void writeAccountLaneMetrics(int laneId, com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder encoder) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length || encoder == null) {
            throw new IllegalArgumentException("invalid Lane metrics request");
        }
        // 本次容量检查或指标采样所见的队列深度，不表示预留了队列位置
        int depth = laneWorkerDepth(laneId);
        // 当前工作队列或冷阶段 Lane 声明的可容纳数量
        int capacity = laneLifecycle.worker(laneId) == null ? accountLanes[laneId].queueCapacity() : laneLifecycle.worker(laneId).metricsQueueCapacity();
        // Owner 本轮指标采样得到并合并后的队列高水位
        int highWater = Math.max(accountLaneQueueHighWaterMarks[laneId], depth);
        highWater = accountLanes[laneId].updateQueueHighWaterMark(highWater);
        accountLaneQueueHighWaterMarks[laneId] = highWater; // 指标采样所见的高水位，不声称捕获每次入队。
        accountLanes[laneId].writeMetrics(encoder, depth, capacity, highWater);
    }

    /** Owner 单调发布的订单身份退休栅栏，Lane 通过 volatile 读取。 */
    private volatile long retiredOwnerSequence;

    /**
     * 在 Owner 上单调发布已退休命令的序号边界，供 Lane 回收订单身份元数据；不传递第二份订单业务状态。
     * @param sequence 本次业务交接使用的 Core 序号
     */
    public void releaseRetiredOrderFence(long sequence) {
        assertOwner();
        if (sequence < retiredOwnerSequence) throw new IllegalStateException("order retirement fence moved backwards");
        retiredOwnerSequence = sequence;
    }

    /**
     * 使用禁用 CPU 亲和的配置创建账户工作资源。
     */
    public void startAccountLanes() {
        startAccountLanes(TradingCpuAffinity.disabled());
    }

    /**
     * 为每条账户 Lane 创建工作对象并接入统一故障通知，全部创建后标记已启动并交出 Owner 访问权，再记录是否存在普通线程实现。重复启动直接拒绝。
     * @param affinity 本次账户工作资源采用的 CPU 亲和配置
     */
    public void startAccountLanes(TradingCpuAffinity affinity) {
        laneLifecycle.start(affinity);
    }

    /** 显式集群命令范围标记，禁止范围内同步等待尚未完成的账户提交。 */
    private boolean asynchronousCommandScope;

    /**
     * 在 Owner 上进入单层异步命令范围，禁止嵌套；范围内不能退回等待未完成账户提交的同步调用。
     */
    public void enterAsynchronousCommandScope() {
        assertOwner();
        if (asynchronousCommandScope) throw new IllegalStateException("nested asynchronous command scope");
        asynchronousCommandScope = true;
    }

    /**
     * 在 Owner 上退出显式异步命令范围。
     */
    public void exitAsynchronousCommandScope() {
        assertOwner();
        asynchronousCommandScope = false;
    }

    public boolean ownerLaneAccess() { return laneLifecycle.ownerHasAccess(); }
    public long laneHandoffEpoch() { return laneLifecycle.handoffEpoch(); }

    /**
     * Owner 持有账户访问权且没有批量修改范围时归还 Lane，避免过早释放仍被批次使用的访问权。
     */
    public void releaseCompletedSequentialLaneStage() {
        if (ownerLaneAccess() && !orderBatchMutationScope) releaseOwnerLaneAccess();
    }

    /**
     * 在 Owner 上请求全部账户工作线程交还所有权：首次请求递增轮次并设置 30 秒预算，尚有工作线程未就绪时返回假，超时或中断则抛错；全就绪后绑定各 Lane 并标记可访问。冷阶段直接取得。
     */
    public boolean tryAcquireOwnerLaneAccess() {
        return laneLifecycle.tryAcquireOwnerAccess();
    }

    /**
     * 循环尝试取得全部账户 Lane 访问权，未就绪时自旋；中断和期限检查由尝试入口负责。
     */
    public void acquireOwnerLaneAccess() {
        while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
    }

    /**
     * 在 Owner 上释放各活动 Lane 的绑定并清访问标记；若正在交接，通知工作线程恢复该轮工作并清请求标记。
     */
    public void releaseOwnerLaneAccess() { laneLifecycle.releaseOwnerAccess(); }

    public void assertAccountLanesHealthy() { laneLifecycle.assertHealthy(); }

    /**
     * 要求冷阶段财库变化已经提交，首次调用时创建账本 Lane、接入故障唤醒并转交业务索引所有权；已有账本时直接返回。
     */
    public void startSystemLedgerLane() {
        laneLifecycle.startLedger(treasury,
                Math.max(4_096, Math.multiplyExact(matcherSettlementChangesRing.length, 2)),
                businessOwner -> { if (factIndexes != null) factIndexes.bindBusinessOwner(businessOwner); });
    }

    public boolean systemLedgerStarted() { return laneLifecycle.ledger() != null; }

    /**
     * 从账本拥有的事实索引读取准入所需持仓量；尚未绑定索引则报错，结果由各准入事件保存。
     * @param instrumentId 本产品线的外部标的 ID
     */
    public long ledgerAdmissionOpenInterest(String instrumentId) {
        if (factIndexes == null) throw new IllegalStateException("position indexes are not bound");
        return factIndexes.admissionOpenInterest(instrumentId);
    }

    /**
     * 索引已绑定时，把指定结算 Lane 的持仓变化交给账本业务索引应用。
     * @param changes 本次撮合结算借用的变化槽，收集完成前保持占用
     * @param mask 本次需要应用持仓索引的 Lane 位集合
     * @param identities 与本产品线状态配套的身份注册表
     */
    public void applyLedgerSettlementPositions(MatcherSettlementChanges changes, long mask, RuntimeIdentityRegistry identities) {
        if (factIndexes != null) factIndexes.applySettlementPositions(changes, mask, identities);
    }

    /**
     * 索引已绑定时，把当前控制命令持仓变化应用到账本索引，并记录可回滚内容。
     * @param undo 控制持仓索引的撤销记录，供失败后恢复
     */
    public void applyLedgerControlPositions(RuntimeFactIndexes.PositionUndo undo) {
        if (factIndexes != null) factIndexes.applyControlPositions(changedPositions, indexIdentities, undo);
    }

    /**
     * 索引已绑定时，按既有撤销记录恢复账本持仓索引。
     * @param undo 控制持仓索引的撤销记录，供失败后恢复
     */
    public void rollbackLedgerPositions(RuntimeFactIndexes.PositionUndo undo) {
        if (factIndexes != null) undo.rollback(factIndexes);
    }

    /**
     * 在 Owner 上派发财务控制命令：账本未激活则直接应用；活动阶段保证同一控制命令独占财务范围，邮箱满时撤销已提交标记并返回假，成功才记住控制槽。
     * @param command 要交给财库或账本处理的财务控制命令
     */
    public boolean dispatchLedgerCommand(SystemLedgerCommand command) {
        assertOwner();
        if (command == null) throw new IllegalArgumentException("Ledger command is required");
        if (laneLifecycle.ledger() == null) {
            command.applyBeforeActivation(treasury);
            return true;
        }
        if (ledgerControl != null && ledgerControl != command)
            throw new IllegalStateException("another control command owns the Ledger financial scope");
        command.submitted();
        if (!laneLifecycle.ledger().offer(command)) {
            command.notAdmitted();
            return false;
        }
        ledgerControl = command;
        return true;
    }

    /**
     * 冷阶段直接回滚财库变化；活动阶段向原控制槽或资金校验槽请求逆向收尾，等待先前阶段交出该财务范围。
     */
    private boolean rollbackLedgerControl() {
        if (laneLifecycle.ledger() == null) {
            treasury.rollbackChangedValues();
            return true;
        }
        if (ledgerControl == null) return controlFundsValidation.requestRollback();
        // 先让原派发阶段释放财务范围，再请求逆向收尾。
        return ledgerControl.requestRollback();
    }

    /**
     * 把财务范围关闭条目放入已激活账本邮箱，返回是否接纳；调用方保证账本已经存在。
     * @param close 要入账本邮箱的财务范围关闭条目
     */
    public boolean enqueueLedgerScopeClose(SystemLedgerLane.Entry close) {
        assertOwner();
        return laneLifecycle.ledger().offer(close);
    }

    /**
     * 在 Owner 上判断账户已启动、账本已存在或显式异步范围是否任一成立；FUSED 也可能因独立账本而走异步续步。
     */
    public boolean asynchronousCommands() { assertOwner(); return accountLanesStarted() || laneLifecycle.ledger() != null || asynchronousCommandScope; }

    /** 控制账户任务的唯一有界派发和完成收集入口。 */
    private final ControlLaneDispatcher controlLanes = new ControlLaneDispatcher(this);

    /**
     * 按目标掩码派发普通控制结算任务，交由唯一控制派发器管理回执。
     * @param laneMask 本次需要操作或派发的账户 Lane 位集合
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public void dispatchControlLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        controlLanes.dispatch(laneMask, AccountLaneOperationType.SETTLEMENT, operation);
    }

    /**
     * 按目标掩码派发风险任务，仍使用同一有界控制派发器。
     * @param laneMask 本次需要操作或派发的账户 Lane 位集合
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public void dispatchRiskLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        controlLanes.dispatch(laneMask, AccountLaneOperationType.RISK, operation);
    }

    /**
     * 校验强平批次容量在 1 至 4096 之间，从当前 Lane 或冷路径默认 Lane 0 取得可复用批次，容量不足时替换并重置；本方法不额外取得账户所有权。
     * @param capacity 本轮风险强平批次所需容量，必须在 1 至 4096 之间
     */
    public RiskLiquidationBatch riskLiquidationBatch(int capacity) {
        if (capacity <= 0 || capacity > 4_096) {
            throw new IllegalArgumentException("invalid risk liquidation batch capacity");
        }
        // 本轮路由、交接或回调正在处理的账户 Lane 引用
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null && accountLanes != null && accountLanes.length > 0) {
            lane = accountLanes[0];
        }
        if (lane == null) {
            throw new IllegalStateException("risk liquidation batch requires an owning Lane scope");
        }
        // 当前 Lane 复用的风险强平批次，容量不足时替换，本轮使用前重置
        RiskLiquidationBatch batch = lane.riskLiquidationBatch();
        if (batch == null || batch.capacity() < capacity) {
            batch = new RiskLiquidationBatch(capacity);
            lane.riskLiquidationBatch(batch);
        }
        batch.reset();
        return batch;
    }

    /**
     * 让控制派发器收集本轮完成状态，返回其是否已经完成。
     */
    public boolean pollControlLanes() { return controlLanes.poll(); }

    /**
     * 读取在途控制交接的诊断信息，供推进故障定位使用。
     */
    public String controlLaneDiagnostics() { return controlLanes.diagnostics(); }

    /**
     * 取得指定 Lane 的控制结果，完成与读取条件由控制派发器检查。
     * @param laneId 账户 Lane 的零起始编号
     */
    public Object controlLaneResult(int laneId) { return controlLanes.result(laneId); }

    /**
     * 必要时先取得全部账户访问权，再只对目标用户 Lane 建立已提交序号的读栅栏；退出时归还本方法需要取得的访问权。
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param committedCoreSequence 读取必须满足的已提交 Core 前缀
     */
    public void readFence(long userId, long committedCoreSequence) {
        assertOwner();
        // 本方法是否需要临时取得账户访问权，不表示此时已经完成获取
        boolean acquired = accountLanesStarted() && !ownerLaneAccess();
        try {
            if (acquired) while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            onLane(topology.accountLaneId(userId), /* 本轮路由、交接或回调正在处理的账户 Lane 引用 */ lane -> {
                lane.readFence(committedCoreSequence);
                return null;
            });
        } finally {
            if (acquired) releaseOwnerLaneAccess();
        }
    }

    /**
     * 必要时取得全部账户访问权，先逐 Lane 检查读栅栏，再逐 Lane 推进读水位，避免检查尚未通过时提前推进其他 Lane。
     * @param committedCoreSequence 读取必须满足的已提交 Core 前缀
     */
    public void readFenceAll(long committedCoreSequence) {
        assertOwner();
        // 本方法是否需要临时取得账户访问权，不表示此时已经完成获取
        boolean acquired = accountLanesStarted() && !ownerLaneAccess();
        try {
            if (acquired) while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            for (/* 当前按拓扑选中或从掩码取出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
                // 供本轮回调捕获的稳定 Lane 下标
                final int currentLaneId = laneId;
                onLane(currentLaneId, /* 本轮路由、交接或回调正在处理的账户 Lane 引用 */ lane -> {
                    lane.requireReadFence(committedCoreSequence);
                    return null;
                });
            }
            for (/* 当前按拓扑选中或从掩码取出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
                // 供本轮回调捕获的稳定 Lane 下标
                final int currentLaneId = laneId;
                onLane(currentLaneId, /* 本轮路由、交接或回调正在处理的账户 Lane 引用 */ lane -> {
                    lane.advanceReadFence(committedCoreSequence);
                    return null;
                });
            }
        } finally {
            if (acquired) releaseOwnerLaneAccess();
        }
    }

    /**
     * 按用户所属 Lane 执行给定操作，沿用统一的 Lane 范围检查。
     * @param <T> 该账户操作返回值的类型
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public <T> T onLane(long userId, LaneOperation<T> operation) {
        return onLane(topology.accountLaneId(userId), operation);
    }

    /**
     * 已有命令范围时只允许访问同一 Lane；否则拒绝没有账户访问权的活动 Owner，再进入受检查的 Lane 命令范围执行。
     * @param <T> 该账户操作返回值的类型
     * @param laneId 账户 Lane 的零起始编号
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public <T> T onLane(int laneId, LaneOperation<T> operation) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("account lane command crossed its owner boundary");
            }
            return operation.apply(scoped);
        }
        if (Thread.currentThread() == owner && accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
        return inLaneCommandScope(accountLanes[laneId], operation);
    }

    /**
     * 检查回调不为空后直接借出指定 Lane 执行读取；本方法不自动建立线程权限或读取栅栏。
     * @param <T> 该账户操作返回值的类型
     * @param laneId 账户 Lane 的零起始编号
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public <T> T onLaneReadOnly(int laneId, LaneOperation<T> operation) {
        if (operation == null) {
            throw new IllegalStateException("invalid account lane read operation");
        }
        return operation.apply(accountLanes[laneId]);
    }

    /**
     * 进入给定 Lane 的命令范围执行回调，无论正常返回或抛错都退出该范围。
     * @param <T> 该账户操作返回值的类型
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public <T> T inLaneCommandScope(AccountLaneState lane, LaneOperation<T> operation) {
        if (operation == null) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        enterLaneCommandScope(lane);
        try {
            return operation.apply(lane);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    /**
     * 查当前线程正在执行的 Lane 范围：Owner 使用本地引用，专用 LaneThread 直接取其 Lane，存在普通工作线程时才逐项匹配；没有活动范围返回空。
     */
    public AccountLaneState currentLaneCommandScope() {
        // 当前调用线程，用于核对 Owner 或所属账户工作线程
        Thread current = Thread.currentThread();
        if (current == owner) {
            return ownerLaneScope;
        }
        if (current instanceof LaneThread /* 携带固定账户 Lane 引用的专用工作线程 */ laneThread) {
            // 本轮路由、交接或回调正在处理的账户 Lane 引用
            AccountLaneState lane = laneThread.lane();
            return lane.commandScopeActive() ? lane : null;
        }
        return null;
    }

    /**
     * 在查询范围内读权威账户表，冷阶段允许当前拥有者执行，活动阶段要求实际所属工作线程；退出时清查询标记且不生成持仓退休证明。
     * @param <T> 该账户操作返回值的类型
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param query 要在所属账户 Lane 的查询范围中执行的读取请求
     */
    public <T> T readAccountLane(AccountLaneState lane, java.util.function.Function<AccountLaneState, T> query) {
        if (!accountLanesStarted()) {
            if (lane == null || query == null || lane.laneId() >= accountLanes.length
                    || accountLanes[lane.laneId()] != lane) {
                throw new IllegalArgumentException("invalid account lane read scope");
            }
            enterLaneCommandScope(lane);
            lane.queryScopeActive(true);
            try { return query.apply(lane); }
            finally {
                lane.queryScopeActive(false);
                exitLaneCommandScope(lane, false);
            }
        }
        if (lane == null || query == null || lane.laneId() >= accountLanes.length
                || accountLanes[lane.laneId()] != lane || laneLifecycle.worker(lane.laneId()) == null) {
            throw new IllegalArgumentException("invalid account lane read scope");
        }
        // 当前调用线程，用于核对 Owner 或所属账户工作线程
        Thread current = Thread.currentThread();
        if (current instanceof LaneThread /* 携带固定账户 Lane 引用的专用工作线程 */ laneThread) {
            if (laneThread.lane() != lane) {
                throw new IllegalStateException("account query must run on its owning Lane thread");
            }
        } else if (current != laneLifecycle.worker(lane.laneId()).thread()) {
            throw new IllegalStateException("account query must run on its owning Lane thread");
        }
        enterLaneCommandScope(lane);
        lane.queryScopeActive(true);
        try { return query.apply(lane); }
        finally {
            lane.queryScopeActive(false);
            exitLaneCommandScope(lane, false);
        }
    }

    /**
     * 由 Owner 先检查全部目标 Lane 的容量，全部可接纳才派发同一查询，避免邮箱满造成部分查询；冷阶段逐 Lane 直接执行，活动阶段先归还 Owner 访问权。
     * @param laneMask 本次需要操作或派发的账户 Lane 位集合
     * @param query 要在所属账户 Lane 的查询范围中执行的读取请求
     */
    public boolean dispatchAccountQuery(long laneMask, SettlementLaneWorker.Command query) {
        assertOwner();
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("account query producer must be Owner");
        // 当前实际账户数量允许的位集合，64 条时所有位均有效
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1;
        if (query == null || laneMask == 0 || (laneMask & ~validMask) != 0)
            throw new IllegalArgumentException("invalid account query dispatch");
        if (!accountLanesStarted()) {
            // 本轮还未派发或检查的 Lane 位集合，每次清除最低有效位
            long lanes = laneMask;
            while (lanes != 0) {
                // 当前按拓扑选中或从掩码取出的账户 Lane 编号
                int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
                query.execute(accountLanes[laneId]);
            }
            return true;
        }
        assertAccountLanesHealthy();
        // 本轮还未派发或检查的 Lane 位集合，每次清除最低有效位
        long lanes = laneMask;
        while (lanes != 0) {
            // 当前按拓扑选中或从掩码取出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            if (!laneLifecycle.worker(laneId).hasCapacity()) return false;
        }
        releaseOwnerLaneAccess();
        lanes = laneMask;
        while (lanes != 0) {
            // 当前按拓扑选中或从掩码取出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            laneLifecycle.worker(laneId).submit(query);
        }
        return true;
    }

    /**
     * 取得当前线程已接入的撮合变化容器，Owner 使用本地范围，其余线程从当前 Lane 范围读取。
     */
    public MatcherSettlementChanges currentMatcherSettlementChangesScope() {
        if (Thread.currentThread() == owner) return ownerMatcherSettlementChangesScope;
        // 本轮路由、交接或回调正在处理的账户 Lane 引用
        AccountLaneState lane = currentLaneCommandScope();
        return lane != null ? lane.matcherSettlementChanges() : null;
    }

    /**
     * 校验 Lane 引用、当前线程和既有范围，禁止交叉或嵌套账户访问；活动 Owner 还须持有交接后的访问权，再登记当前范围。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     */
    public void enterLaneCommandScope(AccountLaneState lane) {
        if (lane == null || lane.laneId() >= accountLanes.length || accountLanes[lane.laneId()] != lane) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        // 当前调用线程，用于核对 Owner 或所属账户工作线程
        Thread current = Thread.currentThread();
        if (owner == null) bindOwner();
        if (current == owner ? ownerLaneScope != null : lane.commandScopeActive())
            throw new IllegalStateException("invalid account lane command scope");
        // 本 Lane 对应的可空账户工作对象，沿用其队列或交接协议
        SettlementLaneWorker worker = laneLifecycle.worker(lane.laneId());
        if (current != owner && (worker == null || worker.thread() != current)) {
            throw new IllegalStateException("account command must run on its owning Lane thread");
        }
        if (current == owner && accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
        if (current != owner || !accountLanesStarted()) {
            lane.assertOwner();
        }
        if (current == owner) {
            ownerLaneScope = lane;
            if (!accountLanesStarted()) lane.commandScopeActive(true);
        } else {
            lane.commandScopeActive(true);
        }
    }

    /**
     * 要求撮合变化容器覆盖该 Lane 且没有其他撮合范围，先进入账户命令范围，再登记本次结算变化容器。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param changes 本次撮合结算借用的变化槽，收集完成前保持占用
     */
    public void enterMatcherSettlementScope(AccountLaneState lane, MatcherSettlementChanges changes) {
        if (changes == null || lane == null || lane.matcherSettlementChanges() != null
                || Thread.currentThread() == owner && ownerMatcherSettlementChangesScope != null
                || (changes.activeLaneMask() & (1L << lane.laneId())) == 0) {
            throw new IllegalStateException("invalid matcher settlement scope");
        }
        enterLaneCommandScope(lane);
        if (Thread.currentThread() == owner) ownerMatcherSettlementChangesScope = changes;
        lane.matcherSettlementChanges(changes);
    }

    /**
     * 按默认规则退出账户命令范围，需要时保留持仓退休证明。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     */
    public void exitLaneCommandScope(AccountLaneState lane) {
        exitLaneCommandScope(lane, true);
    }

    /**
     * 确认当前命令范围匹配，按线程及冷阶段分支选择是否准备持仓退休证明，然后清范围；Owner 退出时收集该 Lane 已发布变化。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param captureRetirementProof 退出普通命令范围时是否需要记录持仓身份退休证明
     */
    private void exitLaneCommandScope(AccountLaneState lane, boolean captureRetirementProof) {
        // 当前调用线程，用于核对 Owner 或所属账户工作线程
        Thread current = Thread.currentThread();
        if (lane == null || (current == owner
                ? ownerLaneScope != lane : !lane.commandScopeActive())) {
            throw new IllegalStateException("account lane command scope is not active");
        }
        if (current != owner || !accountLanesStarted()) {
            lane.assertOwner();
            if (captureRetirementProof) laneDeltas[lane.laneId()].preparePositionRetirement(lane);
        }
        if (current == owner) {
            ownerLaneScope = null;
            if (!accountLanesStarted()) lane.commandScopeActive(false);
            flushPublishedChanges(lane.laneId());
        } else {
            lane.commandScopeActive(false);
        }
    }

    /**
     * 确认正在退出同一撮合变化容器，清 Owner 与 Lane 的撮合引用，再退出账户范围；此处不重复生成退休证明。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param changes 本次撮合结算借用的变化槽，收集完成前保持占用
     */
    public void exitMatcherSettlementScope(AccountLaneState lane, MatcherSettlementChanges changes) {
        if (changes == null || lane == null || (Thread.currentThread() == owner
                ? ownerMatcherSettlementChangesScope != changes : lane.matcherSettlementChanges() != changes)) {
            throw new IllegalStateException("matcher settlement scope is not active");
        }
        if (Thread.currentThread() == owner) ownerMatcherSettlementChangesScope = null;
        lane.matcherSettlementChanges(null);
        exitLaneCommandScope(lane, false);
    }

    /**
     * 同步执行指定用户所属 Lane 的结算操作，并从复用结果数组取得返回值；活动阶段必须已经持有 Owner 账户访问权。
     * @param <T> 该账户操作返回值的类型
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public <T> T executeUserSettlement(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user settlement command");
        }
        // 当前按拓扑选中或从掩码取出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        executeLaneMutations(1L << laneId, 1, /* 回调传入但本操作不需要的 Lane 参数，实际使用已捕获的业务操作 */ ignored -> operation.get());
        // 从本 Lane 复用结果数组取出的本次业务返回值
        @SuppressWarnings("unchecked") T result = (T) laneMutationResultsScratch[laneId];
        return result;
    }

    /**
     * 把用户列表按用户身份映射到目标 Lane，再走同步结算执行。
     * @param userIds 本次实际涉及的用户身份集合或原始数组，跳过空或非正身份
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public Object[] executeOwnerSettlements(Iterable<Long> userIds,
                                            java.util.function.IntFunction<Object> operation) {
        return executeOwnerSettlements(userIds, Long::longValue, operation);
    }

    /**
     * 从非空业务项提取正用户身份并合并目标 Lane 位，每条选中 Lane 执行一次操作；本重载用 Lane 数作为工作数量。
     * @param <E> 待路由业务项的类型
     * @param values 本次待路由的业务项，空项由对应集合入口跳过
     * @param ownerUserId 从业务项提取所属用户身份的函数
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public <E> Object[] executeOwnerSettlements(Iterable<E> values,
                                                java.util.function.ToLongFunction<E> ownerUserId,
                                                java.util.function.IntFunction<Object> operation) {
        assertOwner();
        if (values == null || ownerUserId == null || operation == null) {
            throw new IllegalArgumentException("invalid owner settlement command");
        }
        // 由本轮有效业务用户合并得到的目标 Lane 位集合
        long selectedLaneMask = 0;
        for (E /* 本轮正在提取所属用户的业务项，空项直接跳过 */ value : values) {
            if (value == null) continue;
            // 本轮读出的用户身份，按对应入口过滤或路由
            long userId = ownerUserId.applyAsLong(value);
            if (userId > 0) selectedLaneMask |= topology.accountLaneMask(userId);
        }
        return executeLaneMutations(selectedLaneMask, Long.bitCount(selectedLaneMask), operation);
    }

    /**
     * 使用调用方已经分好的 Lane 掩码与工作量，避免再次遍历业务项，进入同步修改入口。
     * @param selectedLaneMask 调用方已经按业务项合并的目标 Lane 位集合
     * @param workItems 本次实际工作数量，非空 Lane 掩码要求大于零
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public Object[] executeOwnerSettlements(long selectedLaneMask, int workItems,
                                            java.util.function.IntFunction<Object> operation) {
        assertOwner();
        return executeLaneMutations(selectedLaneMask, workItems, operation);
    }

    /**
     * 同步修改入口：校验掩码、工作量及访问权，先清上轮结果，再按 Lane 编号逐个执行并记结算次数；返回复用数组，后续调用会改写它。这里不派发并行工作。
     * @param selectedLaneMask 调用方已经按业务项合并的目标 Lane 位集合
     * @param workItems 本次实际工作数量，非空 Lane 掩码要求大于零
     * @param operation 在选中账户 Lane 的有效执行范围内调用的业务操作
     */
    public Object[] executeLaneMutations(long selectedLaneMask, int workItems,
                                          java.util.function.IntFunction<Object> operation) {
        assertOwner();
        // 当前实际账户数量允许的位集合，64 条时所有位均有效
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if ((selectedLaneMask & ~validMask) != 0 || workItems < 0 || operation == null) {
            throw new IllegalArgumentException("invalid account lane mutation");
        }
        if (accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("active Account Lane mutations must be dispatched to their workers");
        }
        // 本运行时复用的按 Lane 结果数组，返回后下一次调用可能改写
        Object[] results = laneMutationResultsScratch;
        // 上轮结果数组实际写过且本轮需要清空的 Lane 位集合
        long staleResults = laneMutationResultsMask;
        while (staleResults != 0) {
            // 当前按拓扑选中或从掩码取出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(staleResults);
            results[laneId] = null;
            staleResults &= staleResults - 1;
        }
        laneMutationResultsMask = selectedLaneMask;
        if (selectedLaneMask == 0) {
            return results;
        }
        if (workItems == 0) throw new IllegalArgumentException("account lane mutation has no work");
        for (/* 当前按拓扑选中或从掩码取出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((selectedLaneMask & 1L << laneId) == 0) continue;
            // 供本轮回调捕获的稳定 Lane 下标
            int currentLaneId = laneId;
            results[laneId] = inLaneCommandScope(accountLanes[currentLaneId],
                    /* 回调传入但本操作不需要的 Lane 参数，实际使用已捕获的业务操作 */ ignored -> operation.apply(currentLaneId));
            accountLanes[laneId].recordSettlementOperation();
        }
        return results;
    }

    /**
     * 在修改任务状态前检查全部目标账户邮箱；未启动时无需检查，活动阶段任一邮箱满就抛出拒绝执行异常。
     * @param laneMask 本次需要操作或派发的账户 Lane 位集合
     */
    public void ensureLaneWorkerCapacity(long laneMask) {
        assertAccountLanesHealthy();
        if (!accountLanesStarted()) return;
        // 本轮还未派发或检查的 Lane 位集合，每次清除最低有效位
        long lanes = laneMask;
        while (lanes != 0) {
            // 当前按拓扑选中或从掩码取出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!laneLifecycle.worker(laneId).hasCapacity()) {
                throw new java.util.concurrent.RejectedExecutionException("Lane " + laneId + " is full");
            }
        }
    }

    /**
     * 检查各目标 Lane 是否能接纳结算工作；检查不会预留队列位置，具体发布仍遵守既有生产者边界。
     * @param laneMask 本次需要操作或派发的账户 Lane 位集合
     */
    public void ensureMatcherSettlementDispatchCapacity(long laneMask) {
        assertAccountLanesHealthy();
        if (!accountLanesStarted()) return;
        // 本轮还未派发或检查的 Lane 位集合，每次清除最低有效位
        long lanes = laneMask;
        while (lanes != 0) {
            // 当前按拓扑选中或从掩码取出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!laneLifecycle.worker(laneId).hasCapacity()) {
                throw new java.util.concurrent.RejectedExecutionException("Lane " + laneId + " is full");
            }
        }
    }

    /**
     * 已经确定账户 Lane 后执行一次业务的回调；线程与访问范围由调用入口建立。
     * @param <T> 该账户操作返回值的类型
     */
    public @FunctionalInterface
    interface LaneOperation<T> {
        /**
         * 在调用方已经建立的账户 Lane 范围内执行业务，返回本次操作结果。
         * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
         */
        T apply(AccountLaneState lane);
    }

    /**
     * 要求当前已有账户命令范围，再判断给定用户是否路由到该范围的 Lane。
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     */
    public boolean currentLaneOwns(long userId) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) throw new IllegalStateException("account lane command scope is required");
        return topology.accountLaneId(userId) == scoped.laneId();
    }

    /**
     * 在所属 Lane 注册尚未登记的用户，推进已应用与已提交序号，并发布提交序号观测槽。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param users 已经按 Lane 收集的用户原始列表
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     */
    public void applyLaneUsers(AccountLaneState lane,
                        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users,
                        long coreSequence) {
        // 用户原始列表的长整数迭代器，避免逐项装箱
        org.eclipse.collections.api.iterator.LongIterator iterator = users.longIterator();
        while (iterator.hasNext()) {
            // 本轮读出的用户身份，按对应入口过滤或路由
            long userId = iterator.next();
            if (!lane.owns(userId)) lane.registerUser(userId);
        }
        lane.applied(coreSequence);
        lane.committed(coreSequence);
        publishLaneCommittedSequence(lane);
    }

    /**
     * 把该 Lane 的已提交序号写入独立 volatile 槽，供 Owner 观察完成前缀。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     */
    public void publishLaneCommittedSequence(AccountLaneState lane) {
        // 当前按拓扑选中或从掩码取出的账户 Lane 编号
        int laneId = lane.laneId();
        // 该 Lane 当前已经完成提交的 Core 序号，随后写入 volatile 槽
        long committed = lane.committedSequence();
        publishedLaneCommittedSlots[laneId].sequence = committed;
    }

    /**
     * 复制指定资产的余额数值；原余额表或该资产不存在时返回空。
     * @param balances 要复制的原余额映射，不修改输入业务对象
     * @param assetId 本次业务使用的资产内部身份
     */
    static BalanceRuntime copyBalance(IntObjectHashMap<BalanceRuntime> balances, int assetId) {
        // 该资产现有的可空余额对象，按当前分支复制数值或释放冻结
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        return balance == null ? null : new BalanceRuntime(balance.userId(), balance.assetId(),
                balance.availableUnits(), balance.lockedUnits());
    }

    /**
     * 复制一名用户的资产映射及每项可变余额对象；空表引用仍返回空。
     * @param balances 要复制的原余额映射，不修改输入业务对象
     */
    static IntObjectHashMap<BalanceRuntime> copyBalances(IntObjectHashMap<BalanceRuntime> balances) {
        if (balances == null) return null;
        // 本次新建的余额或客户号索引容器，不与输入共享可变余额对象
        IntObjectHashMap<BalanceRuntime> copy = new IntObjectHashMap<>(balances.size());
        balances.forEachKeyValue((/* 当前复制余额项的资产内部身份 */ assetId, /* 该资产现有的可空余额对象，按当前分支复制数值或释放冻结 */ balance) -> copy.put(assetId,
                new BalanceRuntime(balance.userId(), balance.assetId(),
                        balance.availableUnits(), balance.lockedUnits())));
        return copy;
    }

    /**
     * 复制所有用户的资产映射和余额对象，供明确的冷查询或快照边界使用。
     * @param balances 要复制的原余额映射，不修改输入业务对象
     */
    public static LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> copyAllBalances(
            LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances) {
        // 本次新建的余额或客户号索引容器，不与输入共享可变余额对象
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> copy = new LongObjectHashMap<>(balances.size());
        balances.forEachKeyValue((/* 本轮读出的用户身份，按对应入口过滤或路由 */ userId, /* 当前用户的原资产余额映射，继续逐项复制其可变余额 */ userBalances) -> copy.put(userId, copyBalances(userBalances)));
        return copy;
    }

    /**
     * 复制客户订单号索引，跳过仅作为内部容量缓存的空用户映射。
     * @param index 原客户订单号索引，复制时跳过内部空用户映射
     */
    public static LongObjectHashMap<LongLongHashMap> copyClientOrderIndex(
            LongObjectHashMap<Long2LongHashMap> index) {
        // 本次新建的余额或客户号索引容器，不与输入共享可变余额对象
        LongObjectHashMap<LongLongHashMap> copy = new LongObjectHashMap<>(index.size());
        // 每用户空映射只是内部容量缓存，复制发布索引时跳过。
        index.forEachKeyValue((/* 本轮读出的用户身份，按对应入口过滤或路由 */ userId, /* 当前用户的原客户订单号索引，空映射只作为内部缓存而不复制 */ values) -> {
            if (!values.isEmpty()) {
                // 为该非空用户新建的原始客户号索引副本
                LongLongHashMap entries = new LongLongHashMap(values.size());
                values.forEachLong(entries::put);
                copy.put(userId, entries);
            }
        });
        return copy;
    }

    /**
     * 当前已有 Lane 范围时把用户变化写入该范围的变化容器；无范围时不写入。
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishUser(long userId, UserRuntime value) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putUser(userId, value);
        }
    }

    /**
     * 当前已有 Lane 范围时登记订单变化，空值保留删除含义；无范围时不写入。
     * @param orderId 本次操作关联的订单实体 ID
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishOrder(long orderId, OrderRuntime value) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putOrder(orderId, value);
        }
    }

    /**
     * 当前已有 Lane 范围时登记订单预留变化，无范围时不写入。
     * @param orderId 本次操作关联的订单实体 ID
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishReservation(long orderId, ReservationRuntime value) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putReservation(orderId, value);
        }
    }

    /**
     * 当前已有 Lane 范围时登记持仓变化，无范围时不写入。
     * @param positionKey 持仓内部身份键
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishPosition(long positionKey, PositionRuntime value) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putPosition(positionKey, value);
        }
    }

    /**
     * Lane 范围内把强平变化交给该 Lane 容器，否则写入当前 Owner 变化缓冲。
     * @param liquidationId 本次变化关联的强平实体 ID
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    void publishLiquidation(long liquidationId, LiquidationRuntime value) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) changedLiquidations.put(liquidationId, value);
        else laneDelta(scoped.laneId()).putLiquidation(liquidationId, value);
    }

    /**
     * Lane 范围内登记风险快照变化，否则写入当前 Owner 风险变化缓冲。
     * @param positionKey 持仓内部身份键
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishRiskSnapshot(long positionKey, RiskSnapshotRuntime value) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) changedRiskSnapshots.put(positionKey, value);
        else laneDelta(scoped.laneId()).putRiskSnapshot(positionKey, value);
    }

    /**
     * 按当前是否处于 Lane 范围，把触发单变化交给 Lane 容器或 Owner 缓冲。
     * @param id 本次查询或发布的业务实体身份
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishTriggerOrder(long id, CoreTriggerOrderState value) {
        // 当前线程已经进入的可空账户命令范围，不自动扩展到其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) {
            changedTriggerOrders.put(id, value);
        } else {
            laneDelta(scoped.laneId()).putTrigger(id, value);
        }
    }

    /**
     * 优先取得当前撮合范围的指定 Lane 变化容器，没有撮合范围则使用普通控制变化容器。
     * @param laneId 账户 Lane 的零起始编号
     */
    public LaneCommitDelta laneDelta(int laneId) {
        // 本次借用或当前范围持有的结算变化槽，按所属 Lane 访问其变化容器
        MatcherSettlementChanges changes = currentMatcherSettlementChangesScope();
        return changes == null ? laneDeltas[laneId] : changes.laneDelta(laneId);
    }

    /**
     * 按明确的 Lane 引用选择它的撮合变化容器或普通控制变化容器。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     */
    public LaneCommitDelta laneDelta(AccountLaneState lane) {
        // 本次借用或当前范围持有的结算变化槽，按所属 Lane 访问其变化容器
        MatcherSettlementChanges changes = lane.matcherSettlementChanges();
        return changes == null ? laneDeltas[lane.laneId()] : changes.laneDelta(lane.laneId());
    }

    /**
     * 向明确指定 Lane 的当前变化容器登记用户变化。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishUser(AccountLaneState lane, long userId, UserRuntime value) {
        laneDelta(lane).putUser(userId, value);
    }

    /**
     * 向明确指定 Lane 的当前变化容器登记订单变化。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param orderId 本次操作关联的订单实体 ID
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishOrder(AccountLaneState lane, long orderId, OrderRuntime value) {
        laneDelta(lane).putOrder(orderId, value);
    }

    /**
     * 向明确指定 Lane 的当前变化容器登记订单预留变化。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param orderId 本次操作关联的订单实体 ID
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishReservation(AccountLaneState lane, long orderId, ReservationRuntime value) {
        laneDelta(lane).putReservation(orderId, value);
    }

    /**
     * 向明确指定 Lane 的当前变化容器登记持仓变化。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param positionKey 持仓内部身份键
     * @param value 本次登记的业务值，变化发布和映射写入中空值保留删除含义
     */
    public void publishPosition(AccountLaneState lane, long positionKey, PositionRuntime value) {
        laneDelta(lane).putPosition(positionKey, value);
    }

    /**
     * 按默认的空附加身份集合，收集指定 Lane 的控制变化。
     * @param laneId 账户 Lane 的零起始编号
     */
    public void flushPublishedChanges(int laneId) {
        flushPublishedChanges(laneId, null, null);
    }

    /**
     * 收集普通控制变化、余额变化标记及触发单变化，再排入 Owner 变化缓冲和可选身份集合；不在此重做账户业务。
     * @param laneId 账户 Lane 的零起始编号
     * @param changedUsers 可空的附加用户变化身份收集器
     * @param changedOrders 可空的附加订单变化身份收集器
     */
    public void flushPublishedChanges(int laneId,
                               com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
                               com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        // 该 Lane 的普通控制变化容器，收集后排入 Owner 变化缓冲
        LaneCommitDelta changes = laneDeltas[laneId];
        changes.recordControlChanges(this, laneId);
        if (accountRollback.balancePatch(laneId).size() != 0) markBalancesChanged();
        changes.publishTriggersToOwner(this);
        changes.drainTo(laneId, this, changedUsers, changedOrders);
    }

    /**
     * 在 Owner 上从固定环借出空闲变化槽，标记占用并准备目标 Lane 容器；环满或槽已占用视为流程错误。
     * @param laneMask 本次需要操作或派发的账户 Lane 位集合
     */
    public MatcherSettlementChanges acquireMatcherSettlementChanges(long laneMask) {
        assertOwner();
        if (freeMatcherSettlementChangeCount == 0) {
            throw new IllegalStateException("LaneCommitDelta ring is full");
        }
        // 本次借用或当前范围持有的结算变化槽，按所属 Lane 访问其变化容器
        MatcherSettlementChanges changes = matcherSettlementChangesRing[
                freeMatcherSettlementChangeSlots[--freeMatcherSettlementChangeCount]];
        if (changes.inUse()) throw new IllegalStateException("LaneCommitDelta ring slot is already active");
        changes.inUse(true);
        changes.activeLaneMask(laneMask);
        changes.ensureActiveLanes(laneMask);
        return changes;
    }

    /**
     * 要求变化槽仍被占用，归还退休订单引用、清空容器并把槽号放回空闲栈；调用方保证消费者已经结束。
     * @param changes 本次撮合结算借用的变化槽，收集完成前保持占用
     */
    public void releaseMatcherSettlementChanges(MatcherSettlementChanges changes) {
        if (changes == null || !changes.inUse()) throw new IllegalStateException("LaneCommitDelta slot is not active");
        changes.returnRetiredOrders(this);
        changes.clear();
        changes.inUse(false);
        freeMatcherSettlementChangeSlots[freeMatcherSettlementChangeCount++] = changes.ringIndex();
    }

    /**
     * 先归还 Owner 账户访问权并清账户启动标记，再逐一关闭账户工作对象与账本，汇总已捕获的关闭异常；账本线程确认退出后解除索引业务所有者，最后保留 Error 或包装其他故障抛出。
     */
    @Override
    public void close() { laneLifecycle.close(); }

    public boolean workersStopped() { return laneLifecycle.workersStopped(); }

    /**
     * 订单转为终态时一并交出的订单与预留引用；不单独表示整条命令已经有序提交。
     * @param order 终态处理交出的订单引用
     * @param reservation 该订单配套的可空预留引用，沿用原处理分支含义
     */
    record CanceledOrder(OrderRuntime order, ReservationRuntime reservation) {
    }

    /**
     * 校验序号及用户集合，按 Lane 收集正用户身份后同步等待提交，返回实际提交掩码。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param userIds 本次实际涉及的用户身份集合或原始数组，跳过空或非正身份
     */
    public long stageLaneMutation(long coreSequence, Iterable<Long> userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (Long /* 本轮读出的用户身份，按对应入口过滤或路由 */ userId : userIds) {
            if (userId == null || userId <= 0) continue;
            addLaneUser(userId.longValue());
        }
        return stageLaneMutationFromScratch(coreSequence);
    }

    /**
     * 从原始用户数组收集目标 Lane，派发提交事件并交给调用方后续收集。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param userIds 本次实际涉及的用户身份集合或原始数组，跳过空或非正身份
     */
    public LaneCommitEvent dispatchLaneMutation(long coreSequence, long[] userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (long /* 本轮读出的用户身份，按对应入口过滤或路由 */ userId : userIds) {
            if (userId > 0) addLaneUser(userId);
        }
        return dispatchLaneMutationFromScratch(coreSequence);
    }

    /**
     * 从已有结果集合收集实际变化用户，仅派发这些用户所在 Lane 的提交事件。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param userIds 本次实际涉及的用户身份集合或原始数组，跳过空或非正身份
     */
    public LaneCommitEvent dispatchLaneMutation(long coreSequence, Iterable<Long> userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) throw new IllegalArgumentException("invalid lane apply");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence);
    }

    /**
     * 为订单元数据提交补上空的触发取消列表，沿用同一账户提交任务。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param userIds 本次实际涉及的用户身份集合或原始数组，跳过空或非正身份
     * @param orderIds 本次需要在同一 Lane 提交任务中盖章的订单身份
     * @param timestamp 原复制日志业务时间，不用当前机器时间替代
     * @param position 原复制日志位置，随同提交元数据交给账户任务
     */
    public LaneCommitEvent dispatchLaneMutation(long coreSequence, Iterable<Long> userIds,
            Iterable<Long> orderIds, long timestamp, long position) {
        return dispatchLaneMutation(coreSequence, userIds, orderIds, null, timestamp, position);
    }

    /**
     * 校验提交序号和原日志元数据，按变化用户派发账户提交任务，同时携带订单盖章和可选触发取消信息。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param userIds 本次实际涉及的用户身份集合或原始数组，跳过空或非正身份
     * @param orderIds 本次需要在同一 Lane 提交任务中盖章的订单身份
     * @param triggerIds 可空的待取消触发单身份集合
     * @param timestamp 原复制日志业务时间，不用当前机器时间替代
     * @param position 原复制日志位置，随同提交元数据交给账户任务
     */
    public LaneCommitEvent dispatchLaneMutation(long coreSequence, Iterable<Long> userIds,
            Iterable<Long> orderIds, Iterable<Long> triggerIds, long timestamp, long position) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null || orderIds == null || timestamp < 0 || position < 0)
            throw new IllegalArgumentException("invalid account metadata commit");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence, orderIds, triggerIds, timestamp, position);
    }

    /**
     * 最终批次响应与账户提交使用同一任务；即使批次没有账户变化，也把响应用户 Lane 加入读取范围，不把纯拒绝批次伪装成账户写入。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param userIds 本次实际涉及的用户身份集合或原始数组，跳过空或非正身份
     * @param orderIds 本次需要在同一 Lane 提交任务中盖章的订单身份
     * @param timestamp 原复制日志业务时间，不用当前机器时间替代
     * @param position 原复制日志位置，随同提交元数据交给账户任务
     * @param responseUserId 最终批次响应所属用户，决定没有账户写入时仍需读取的 Lane
     * @param target 接收最终订单结果的既有批次结果槽
     * @param identities 与本产品线状态配套的身份注册表
     */
    public LaneCommitEvent dispatchBatchCommit(long coreSequence, Iterable<Long> userIds,
            Iterable<Long> orderIds, long timestamp, long position, long responseUserId,
            LaneOrderResultTarget target, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null || orderIds == null || timestamp < 0 || position < 0
                || responseUserId <= 0 || target == null || identities == null)
            throw new IllegalArgumentException("invalid final batch account task");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence, orderIds, null, timestamp, position,
                target, identities, topology.accountLaneId(responseUserId));
    }

    /**
     * 从只读原始长整数列表直接取值，其他集合走迭代器；跳过空或非正用户后按 Lane 收集，不额外去重。
     * @param userIds 本次实际涉及的用户身份集合或原始数组，跳过空或非正身份
     */
    private void addLaneUsers(Iterable<Long> userIds) {
        if (userIds instanceof ImmutableLongArrayList /* 支持原始长整数遍历的只读用户列表，直接取值而不逐项装箱 */ primitive) {
            primitive.forEachLong(/* 本轮读出的用户身份，按对应入口过滤或路由 */ userId -> { if (userId > 0) addLaneUser(userId); });
            return;
        }
        for (Long /* 本轮读出的用户身份，按对应入口过滤或路由 */ userId : userIds) if (userId != null && userId > 0) addLaneUser(userId);
    }

    /**
     * 清空每条 Lane 的临时用户列表，准备下一次派发。
     */
    void clearLaneUserScratch() {
        for (org.eclipse.collections.impl.list.mutable.primitive.LongArrayList /* 本轮正在清空、提交或检查的 Lane 临时用户列表 */ users : laneUserScratch) {
            users.clear();
        }
    }

    /**
     * 按拓扑把用户身份追加到对应临时列表，不在这里去重。
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     */
    void addLaneUser(long userId) {
        // 当前按拓扑选中或从掩码取出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        laneUserScratch[laneId].add(userId);
    }

    /**
     * 派发临时列表对应提交，有事件则按 30 秒预算同步等待、保存掩码并归还事件；没有目标 Lane 返回零。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     */
    long stageLaneMutationFromScratch(long coreSequence) {
        // 本次借用的准入或账户提交事件，仍被消费者引用时不能回收
        LaneCommitEvent event = dispatchLaneMutationFromScratch(coreSequence);
        if (event == null) return 0;
        awaitLaneCommit(event, System.nanoTime() + LANE_COMMIT_TIMEOUT_NANOS);
        // 本轮实际涉及的账户 Lane 位集合
        long laneMask = event.requiredLaneMask();
        releaseLaneCommit(event);
        return laneMask;
    }

    /**
     * 显式异步范围内不允许等待尚未完成的事件；其他调用按分频检查健康、中断和截止时间，先自旋再让出 CPU，超时作为运行故障抛出，不提前回收未完成事件。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     * @param deadlineNanos 单调纳秒时钟上的显式等待截止时间
     */
    public void awaitLaneCommit(LaneCommitEvent event, long deadlineNanos) {
        if (asynchronousCommandScope && !event.complete())
            throw new org.agrona.concurrent.AgentTerminationException(
                    new IllegalStateException("asynchronous command cannot await Lane commit"));
        // 同步等待循环次数，用于分频健康与时钟检查，以及选择自旋或让出 CPU
        int idle = 0;
        while (!event.complete()) {
            if ((idle++ & 1_023) == 0) {
                assertAccountLanesHealthy();
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0) {
                    // 等待中断或超时属于运行故障，不能回收仍在执行的事件或发布未完成提交。
                    throw new IllegalStateException("Account Lane commit interrupted or timed out");
                }
            }
            if (idle <= configuration.laneCompletionSpins()) Thread.onSpinWait();
            else Thread.yield();
        }
        assertAccountLanesHealthy();
    }

    /**
     * 为不携带元数据的提交补上默认值，进入统一派发。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     */
    LaneCommitEvent dispatchLaneMutationFromScratch(long coreSequence) {
        return dispatchLaneMutationFromScratch(coreSequence, null, null, 0, 0);
    }

    /**
     * 为普通元数据提交补上空的批次响应目标，进入统一派发。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param orderIds 本次需要在同一 Lane 提交任务中盖章的订单身份
     * @param triggerIds 可空的待取消触发单身份集合
     * @param timestamp 原复制日志业务时间，不用当前机器时间替代
     * @param position 原复制日志位置，随同提交元数据交给账户任务
     */
    private LaneCommitEvent dispatchLaneMutationFromScratch(long coreSequence,
            Iterable<Long> orderIds, Iterable<Long> triggerIds, long timestamp, long position) {
        return dispatchLaneMutationFromScratch(coreSequence, orderIds, triggerIds, timestamp, position,
                null, null, -1);
    }

    /**
     * 按临时用户列表校验每条写入 Lane 的提交顺序，必要时加入只读响应 Lane，检查容量并借用事件；准备元数据或触发取消失败则归还事件，成功后按掩码内联执行或派发，并仅推进写入 Lane 的派发水位。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param orderIds 本次需要在同一 Lane 提交任务中盖章的订单身份
     * @param triggerIds 可空的待取消触发单身份集合
     * @param timestamp 原复制日志业务时间，不用当前机器时间替代
     * @param position 原复制日志位置，随同提交元数据交给账户任务
     * @param target 接收最终订单结果的既有批次结果槽
     * @param identities 与本产品线状态配套的身份注册表
     * @param responseLaneId 最终批次响应读取所在的 Lane，没有响应目标时为负一
     */
    private LaneCommitEvent dispatchLaneMutationFromScratch(long coreSequence,
            Iterable<Long> orderIds, Iterable<Long> triggerIds, long timestamp, long position,
            LaneOrderResultTarget target, RuntimeIdentityRegistry identities, int responseLaneId) {
        // 本轮实际涉及的账户 Lane 位集合
        long laneMask = 0;
        for (/* 当前按拓扑选中或从掩码取出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            // 本轮正在清空、提交或检查的 Lane 临时用户列表
            org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users = laneUserScratch[laneId];
            if (users.isEmpty()) continue;
            if (coreSequence <= Math.max(
                    publishedLaneCommittedSlots[laneId].sequence, dispatchedLaneCommitSequences[laneId])) {
                throw new IllegalStateException("account lane apply is out of order");
            }
            laneMask |= 1L << laneId;
        }
        // 本次真正推进账户写入水位的 Lane 集合，不含仅为读取最终响应加入的 Lane
        long commitMask = laneMask;
        if (target != null) laneMask |= 1L << responseLaneId;
        if (laneMask == 0) return null;
        if (accountLanesStarted()) releaseOwnerLaneAccess();
        ensureLaneWorkerCapacity(laneMask);
        // 本次借用的准入或账户提交事件，仍被消费者引用时不能回收
        LaneCommitEvent event = laneCommitEventPool.pollFirst();
        if (event == null) event = new LaneCommitEvent(accountLanes.length);
        event.prepare(coreSequence, laneMask, laneUserScratch, this);
        try {
            if (target != null) event.prepareResponse(target, identities, responseLaneId, commitMask);
            event.prepareMetadata(orderIds, timestamp, position);
            event.prepareTriggerCancellations(triggerIds);
        }
        catch (/* 准备提交元数据或触发取消失败，丢弃内容并归还事件后继续抛出 */ RuntimeException failure) {
            event.discard();
            laneCommitEventPool.addFirst(event);
            throw failure;
        }
        // 本轮还未派发或检查的 Lane 位集合，每次清除最低有效位
        long lanes = laneMask;
        while (lanes != 0) {
            // 当前按拓扑选中或从掩码取出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!accountLanesStarted()) {
                event.execute(accountLanes[laneId]);
            } else {
                laneLifecycle.worker(laneId).submit(event);
            }
            if ((commitMask & (1L << laneId)) != 0)
                dispatchedLaneCommitSequences[laneId] = coreSequence;
        }
        return event;
    }

    /**
     * 在 Owner 上检查健康并观察给定提交事件是否全部完成。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     */
    public boolean laneCommitComplete(LaneCommitEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        assertAccountLanesHealthy();
        return event.complete();
    }

    /**
     * 在调用方完成等待后发布并清空提交事件，再放回复用池；本方法不替调用方循环等待。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     */
    public void releaseLaneCommit(LaneCommitEvent event) {
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        event.publishAndClear();
        laneCommitEventPool.addFirst(event);
    }

    /**
     * 从下单准入池借出事件；池空时创建一个新事件，不在这里执行准入。
     */
    public PlaceAdmissionEvent acquirePlaceAdmission() {
        // 本次借用的准入或账户提交事件，仍被消费者引用时不能回收
        PlaceAdmissionEvent event = placeAdmissionEventPool.pollFirst();
        return event == null ? new PlaceAdmissionEvent() : event;
    }

    /**
     * 未派发的准入准备失败时丢弃事件内容并归还池，空事件无需处理。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     */
    public void discardPlaceAdmission(PlaceAdmissionEvent event) {
        if (event == null) return;
        event.discard();
        placeAdmissionEventPool.addFirst(event);
    }

    /**
     * 准备单笔下单事件，先检查账户准入容量；需要账本输入时先派发该读取，再内联或交给账户准入邮箱。失败时账本仍持有输入则抛出明确故障而不回收该事件，避免复用仍被引用的槽。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     * @param commandId 本次原业务命令 UUID
     * @param settlementStatus 准入需要遵守的标的生命周期结算状态
     * @param assetId 本次业务使用的资产内部身份
     * @param identities 与本产品线状态配套的身份注册表
     * @param timestamp 原复制日志业务时间，不用当前机器时间替代
     * @param position 原复制日志位置，随同提交元数据交给账户任务
     */
    public PlaceAdmissionEvent dispatchPlaceAdmission(
            long coreSequence, long userId, PlaceAdmissionEvent event, java.util.UUID commandId,
            TreasuryRuntime.SettlementStatus settlementStatus,
            int assetId, RuntimeIdentityRegistry identities,
            long timestamp, long position) {
        assertOwner();
        // 当前按拓扑选中或从掩码取出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        if (event == null) throw new IllegalArgumentException("place admission event is required");
        event.prepare(
                coreSequence, userId, commandId, settlementStatus,
                assetId, laneId, asynchronousCommands(), this, identities, timestamp, position);
        try {
            if (accountLanesStarted() && !laneLifecycle.worker(laneId).hasAdmissionCapacity())
                throw new java.util.concurrent.RejectedExecutionException("account admission mailbox is full");
            if (!event.accountReady()) {
                if (!dispatchLedgerQuery(event))
                    throw new java.util.concurrent.RejectedExecutionException("Ledger admission mailbox is full");
                event.inputQueued();
            }
            if (!accountLanesStarted()) {
                if (event.accountReady()) event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneLifecycle.worker(laneId).submitAdmission(event);
            }
        } catch (/* 准入派发失败；输入仍由账本持有时不能回收事件，否则清理后传播 */ RuntimeException | Error failure) {
            if (event.inputPending()) throw new IllegalStateException("Ledger still owns failed admission input", failure);
            if (!event.complete()) event.discard();
            placeAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    /**
     * 调用方完成准入收集后清空事件并归还池；空事件无需处理。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     */
    public void releasePlaceAdmission(PlaceAdmissionEvent event) {
        if (event == null) return;
        event.clear();
        placeAdmissionEventPool.addFirst(event);
    }

    /**
     * 准备改单准入，必要时先派发账本输入读取，再交给所属账户的普通工作邮箱；失败时仍由账本持有的输入不能回收，否则丢弃事件并归还池。
     * @param sequence 本次业务交接使用的 Core 序号
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param originalOrderId 本次改单所替换或修改的原订单 ID
     * @param amend 可空的原地改单参数，与替换单分支按既有约定选择
     * @param replace 可空的替换订单参数，与原地改单分支按既有约定选择
     * @param decision 本次订单规则解析使用的既有上下文
     * @param matcherShard 本次订单准入将交给的 Matcher 分片
     * @param identities 与本产品线状态配套的身份注册表
     */
    public ReplacementAdmissionEvent dispatchReplacementAdmission(long sequence, long userId,
            long originalOrderId, com.surprising.aeron.protocol.AmendOrderCommand amend,
            com.surprising.aeron.protocol.ReplaceOrderCommand replace,
            com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver.Context decision,
            int matcherShard, RuntimeIdentityRegistry identities) {
        assertOwner();
        // 当前按拓扑选中或从掩码取出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        ensureLaneWorkerCapacity(1L << laneId);
        // 本次借用的准入或账户提交事件，仍被消费者引用时不能回收
        ReplacementAdmissionEvent event = replacementAdmissionEventPool.pollFirst();
        if (event == null) event = new ReplacementAdmissionEvent();
        event.prepare(sequence, userId, originalOrderId, amend, replace, decision,
                matcherShard, this, identities);
        try {
            if (!event.accountReady()) {
                if (!dispatchLedgerQuery(event))
                    throw new java.util.concurrent.RejectedExecutionException("Ledger replacement mailbox is full");
                event.inputQueued();
            }
            if (!accountLanesStarted()) {
                if (event.accountReady()) event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneLifecycle.worker(laneId).submit(event);
            }
        } catch (/* 改单派发失败；先判断账本是否仍持有输入，避免提前复用事件 */ RuntimeException | Error failure) {
            if (event.inputPending()) throw new IllegalStateException("Ledger still owns failed replacement input", failure);
            event.discard();
            replacementAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    /**
     * 在 Owner 上把已完成收集的改单准入事件清空并归还池。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     */
    public void releaseReplacementAdmission(ReplacementAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        event.clear();
        replacementAdmissionEventPool.addFirst(event);
    }

    /**
     * 为该用户借出专用变化槽及批次准入事件，准备订单输入后在冷阶段执行或投递账户准入邮箱；投递阶段失败则归还其变化槽并回收事件。准备阶段与投递阶段的异常范围分别沿用现有实现。
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param commandId 本次原业务命令 UUID
     * @param orders 本批按原顺序解析的下单参数数组
     * @param clientKeys 本批各项已经准备的客户订单号内部身份，与订单下标对应
     * @param admittedOrders 本批账户准入返回或填充的订单收据数组
     * @param itemCount 本批实际使用的有效项数，不以数组容量代替
     * @param matcherShard 本次订单准入将交给的 Matcher 分片
     * @param identities 与本产品线状态配套的身份注册表
     * @param source 本批原始下单意图来源，供所属账户准入处理读取
     * @param timestamp 原复制日志业务时间，不用当前机器时间替代
     * @param position 原复制日志位置，随同提交元数据交给账户任务
     */
    public PlaceBatchAdmissionEvent dispatchPlaceBatchAdmission(
            long coreSequence, long userId, java.util.UUID commandId,
            ResolvedPlaceOrder[] orders, long[] clientKeys,
            OrderRuntime[] admittedOrders, int itemCount,
            int matcherShard, RuntimeIdentityRegistry identities, PlaceBatchIntentSource source,
            long timestamp, long position) {
        assertOwner();
        // 当前按拓扑选中或从掩码取出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        // 本次借用或当前范围持有的结算变化槽，按所属 Lane 访问其变化容器
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges(1L << laneId);
        changes.ensureAdmissionCapacity(laneId, itemCount);
        // 本次借用的准入或账户提交事件，仍被消费者引用时不能回收
        PlaceBatchAdmissionEvent event = placeBatchAdmissionEventPool.pollFirst();
        if (event == null) event = new PlaceBatchAdmissionEvent();
        event.prepare(coreSequence, userId, commandId, orders, clientKeys, admittedOrders,
                itemCount, laneId, matcherShard, this, changes, identities, source,
                timestamp, position);
        try {
            if (!accountLanesStarted()) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneLifecycle.worker(laneId).submitAdmission(event);
            }
        } catch (/* 批次投递阶段失败，归还专用变化槽并清空事件后继续抛出 */ RuntimeException | Error failure) {
            releaseMatcherSettlementChanges(event.discardChanges());
            event.clear();
            placeBatchAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    /**
     * 只登记已经完成且未拒绝的批次准入，把各订单加入待完成预留索引，并按批次项数递增修订；调用方保证只登记一次。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     */
    public void registerPlaceBatchAdmission(PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        // 本轮读出的用户身份，按对应入口过滤或路由
        long userId = event.userId();
        pendingReservations.registerBatch(event.coreSequence(), userId, event.admittedOrders(), event.itemCount());
        revision = Math.addExact(revision, event.itemCount());
    }

    /**
     * 在该批次有序提交时才接入账户回滚前值，移交准入标记、可用余额变化和可选资金变化；账户实体仍归 Lane，不重放用户、订单和预留发布缓冲。取得变化槽后无论正常或抛错都归还它。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     * @param fundsAccumulator 可空的资金变化接收器，只追加本批实际产生的变化
     */
    public void collectPlaceBatchAdmission(
            PlaceBatchAdmissionEvent event, RuntimeFundsAccumulator fundsAccumulator) {
        assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        // 当前按拓扑选中或从掩码取出的账户 Lane 编号
        int laneId = topology.accountLaneId(event.userId());
        // 只在本批进入有序提交时接入共享回滚前值。
        // 前面的命令不能提前发布或清空未来批次的修改前状态。
        accountRollback.captureBatchAdmissionBefore(event.userId(), event.userBefore(), event.admittedOrders(), event.itemCount(), laneId);
        event.copyBalanceBeforeTo(accountRollback.balancePatch(laneId));
        // 本次借用或当前范围持有的结算变化槽，按所属 Lane 访问其变化容器
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            // 准入业务对象一直归所属 Lane，直到终态结算完成。
            // 这里仅交接原始准入标记、资金变化和可用余额，
            // 不重放用户、订单或预留的发布缓冲。
            changes.laneDelta(laneId).commitBatchAdmissionToOwner(this, laneId);
            // 本批专用变化槽保存的余额补丁，只发布本批可用余额变化
            LaneBalancePatches balances = changes.balancePatch(laneId);
            for (/* 当前数组、变化项或已准入订单的遍历下标 */ int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(this, index);
                if (changedUsers != null) changedUsers.add(balances.userId(index));
                markBalancesChanged();
            }
            if (fundsAccumulator != null) changes.appendFundsDelta(1L << laneId, fundsAccumulator);
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    /**
     * 在 Owner 上取走批次专用变化槽并归还；事件本身由独立释放入口回收。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     */
    public void discardPlaceBatchAdmission(PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        releaseMatcherSettlementChanges(event.takeChanges());
    }

    /**
     * 在 Owner 上清空已完成使用的批次准入事件并归还池，调用方先处理其变化槽。
     * @param event 本次借用的准入或提交事件，归还前由调用方满足其完成与收集条件
     */
    public void releasePlaceBatchAdmission(PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        event.clear();
        placeBatchAdmissionEventPool.addFirst(event);
    }

    /**
     * 在所属 Lane 按相反顺序撤销本批已准入订单的待预留、客户号、订单与冻结，最后恢复原用户修订及持仓模式；缺少必要预留或余额时直接报错。
     * @param lane 明确所属的账户 Lane 引用，沿用当前执行入口的权限检查
     * @param userId 本次业务对应的用户身份，按固定拓扑路由到账户 Lane
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     * @param orders 本批按原顺序解析的下单参数数组
     * @param clientKeys 本批各项已经准备的客户订单号内部身份，与订单下标对应
     * @param admittedCount 回滚前已经成功准入的项数，只逆序撤销这些项
     * @param userBefore 本批修改前的用户状态，用于恢复修订和持仓模式
     */
    public void rollbackPlaceBatchAdmissionInLane(
            AccountLaneState lane, long userId, long coreSequence, ResolvedPlaceOrder[] orders,
            long[] clientKeys,
            int admittedCount, UserRuntime userBefore) {
        lane.assertOwner();
        for (/* 当前数组、变化项或已准入订单的遍历下标 */ int index = admittedCount - 1; index >= 0; index--) {
            // 当前逆序撤销的已准入订单实体 ID
            long orderId = orders[index].orderId();
            // 该已准入订单必须存在的预留，用于恢复冻结资金
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation == null) {
                throw new IllegalStateException("batch admission rollback reservation is missing");
            }
            lane.completePendingReservation(orderId, coreSequence);
            if (clientKeys[index] != 0) {
                removeClientOrderIndex(lane, userId, clientKeys[index]);
            }
            lane.removeReservationValue(orderId);
            lane.removeReservationId(userId, orderId);
            lane.removeOrder(orderId);
            // 当前用户所属 Lane 的资产余额映射
            IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
            // 该资产现有的可空余额对象，按当前分支复制数值或释放冻结
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("batch admission rollback balance is missing");
            balance.release(reservation.reservedUnits());
        }
        if (userBefore != null) {
            // 当前用户值，保留其所属 Lane 结构后恢复批次前的修订和持仓模式
            UserRuntime current = lane.user(userId);
            lane.storeUser(userId, (accountLanesStarted() ? current.laneValue() : current)
                    .withState(userBefore.revision(), userBefore.positionMode()));
        }
    }

    /** Owner 当前仍预期收到普通结算序号的 Lane 掩码。 */
    private long expectedSettlementLanes;
    /** 普通结算序号队列对应的就绪提示，完成事实仍在队列。 */
    private final PaddedReadyMask settlementReadyLanes;
    /** 直接 Matcher 到账户结算的唤醒提示，原事件完成数组保存实际结果。 */
    private final PaddedReadyMask directSettlementReadyLanes;
    /** 单笔下单准入序号队列对应的就绪提示。 */
    private final PaddedReadyMask placeAdmissionReadyLanes;
    /** 每条账户 Lane 尚待消费的普通结算数量，固定预留 64 个位置。 */
    private final int[] expectedSettlements = new int[64];
    /**
     * 在 Owner 上消费一条预期结算计数；减到零时清该 Lane 的预期位和就绪提示，禁止消费不存在的预期。
     * @param laneId 账户 Lane 的零起始编号
     */
    public void releaseSettlementExpectation(int laneId) {
        assertOwner();
        if (expectedSettlements[laneId] <= 0) throw new IllegalStateException("unexpected settlement completion");
        if (--expectedSettlements[laneId] == 0) {
            // 该 Lane 预期计数刚降到零时需要清除的位
            long bit = 1L << laneId;
            expectedSettlementLanes &= ~bit;
            settlementReadyLanes.clearBit(laneId);
        }
    }
    /**
     * 在 Owner 上为给定 Lane 掩码累计预期结算数量，调用方保证掩码属于本拓扑。
     * @param lanes 已经判定属于本拓扑的预期结算 Lane 位集合
     */
    public void expectMatcherSettlement(long lanes) {
        assertOwner();
        expectedSettlementLanes |= lanes;
        while (lanes != 0) {
            // 当前从预期掩码取出的账户 Lane 下标，用于累计待收集数量
            int lane = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            expectedSettlements[lane]++;
        }
    }

    /**
     * 先发布准入完成的 Core 序号，再设置 Lane 唤醒提示；新观察到置位时通知 Owner。
     * @param laneId 账户 Lane 的零起始编号
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     */
    public void publishPlaceAdmissionReady(int laneId, long coreSequence) {
        placeAdmissionReadyQueues[laneId].publish(coreSequence);
        if (placeAdmissionReadyLanes.setBit(laneId)) signalOwnerCompletion();
    }

    /**
     * 在 Owner 上采样并清除账户准入提示，实际完成序号仍需从队列取出。
     */
    public long takePlaceAdmissionReadyLaneMask() {
        assertOwner();
        return placeAdmissionReadyLanes.getAndClear();
    }

    /**
     * 在 Owner 上从指定 Lane 的准入完成队列取一个 Core 序号，空队列返回零。
     * @param laneId 账户 Lane 的零起始编号
     */
    public long pollPlaceAdmissionReady(int laneId) {
        assertOwner();
        return placeAdmissionReadyQueues[laneId].poll();
    }

    /**
     * 校验 Matcher 分片后发布账户准入就绪提示，唤醒对应撮合提交头；业务内容继续保存在原事件中。
     * @param matcherShard 本次订单准入将交给的 Matcher 分片
     */
    public void publishAccountAdmissionReadyShard(int matcherShard) {
        if (matcherShard < 0 || matcherShard >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("invalid matcher shard");
        }
        if (accountAdmissionReadyShards.setBit(matcherShard)) signalOwnerCompletion();
    }

    /**
     * 在 Owner 上采样并清除 Matcher 分片的账户准入提示。
     */
    public long takeAccountAdmissionReadyShardMask() {
        assertOwner();
        return accountAdmissionReadyShards.getAndClear();
    }

    /**
     * 在 Owner 上检查工作健康并只探测准入或结算就绪提示，不消费完成序号，也不代替事件完成判断。
     */
    public boolean hasMatchingNotifications() {
        assertOwner();
        assertAccountLanesHealthy();
        return placeAdmissionReadyLanes.get() != 0
                || hasPlaceAdmissionNotifications() || hasSettlementNotifications();
    }

    /** 可空的轻量唤醒回调，发布队列或标记之后调用，不替代完成事实。 */
    private volatile Runnable ownerCompletionSignal;
    /**
     * 在 Owner 上接入可空的轻量完成通知回调。
     * @param signal 可空轻量通知，Owner 休眠协议决定实际唤醒行为
     */
    public void ownerCompletionSignal(Runnable signal) { assertOwner(); ownerCompletionSignal = signal; }
    /**
     * 读取一次当前通知回调并在非空时执行，由发布方在队列或完成标记发布后调用。
     */
    public void signalOwnerCompletion() {
        // 本次读取到的可空通知回调，保存到局部后再调用
        Runnable signal = ownerCompletionSignal;
        if (signal != null) signal.run();
    }

    /**
     * 只探测是否有账户准入对应的 Matcher 分片就绪提示，不消费业务结果。
     */
    public boolean hasPlaceAdmissionNotifications() {
        return accountAdmissionReadyShards.get() != 0;
    }

    /**
     * 探测预期 Lane 中的普通结算提示或直接结算提示，真实完成事实仍从队列或事件读取。
     */
    public boolean hasSettlementNotifications() {
        return (settlementReadyLanes.get() & expectedSettlementLanes) != 0
                || directSettlementReadyLanes.get() != 0;
    }

    /**
     * 先向 Lane 完成队列发布序号，再置就绪提示并按需唤醒 Owner。
     * @param laneId 账户 Lane 的零起始编号
     * @param coreSequence 本次准入、提交或完成通知关联的 Core 序号
     */
    public void publishMatcherSettlementReady(int laneId, long coreSequence) {
        matcherSettlementReadyQueues[laneId].publish(coreSequence);
        if (settlementReadyLanes.setBit(laneId)) signalOwnerCompletion();
    }

    /**
     * 直接结算只发布 Lane 唤醒提示；原结算事件持有权威完成标记，不额外建立 Owner 序号队列或预期计数。
     * @param laneId 账户 Lane 的零起始编号
     */
    public void publishDirectMatcherSettlementReady(int laneId) {
        if (directSettlementReadyLanes.setBit(laneId)) signalOwnerCompletion();
    }

    /**
     * 采样并清除直接结算提示，实际完成状态仍查原结算事件。
     */
    public long takeDirectMatcherSettlementReadyLaneMask() {
        return directSettlementReadyLanes.getAndClear();
    }

    /**
     * 在 Owner 上只收集当前预期 Lane 的提示，先清提示再核对其队列是否真的有待消费序号。
     */
    public long takeMatcherSettlementReadyLaneMask() {
        assertOwner();
        return takeReadyLaneMask(settlementReadyLanes, matcherSettlementReadyQueues, expectedSettlementLanes);
    }

    /**
     * 在 Owner 上从指定 Lane 完成队列取序号，取到非零序号后同时减少该 Lane 预期结算数量。
     * @param laneId 账户 Lane 的零起始编号
     */
    public long pollMatcherSettlementReady(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= matcherSettlementReadyQueues.length) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        // 本次从完成队列取出的 Core 序号，零表示没有结果
        long sequence = matcherSettlementReadyQueues[laneId].poll();
        if (sequence != 0) releaseSettlementExpectation(laneId);
        return sequence;
    }

    /**
     * 从提示中选出预期 Lane，先清这些提示再探测完成队列；只返回实际有待消费序号的 Lane。
     * @param ready 需要采样并清除的就绪提示数组
     * @param queues 按账户 Lane 编号排列的完成序号队列
     * @param expected 本轮允许探测的 Lane 位集合
     */
    private static long takeReadyLaneMask(PaddedReadyMask ready,
                                         LaneSequenceQueue[] queues, long expected) {
        // 就绪提示与本轮预期 Lane 相交得到的候选集合，清位后仍需核对队列
        long signalled = ready.get() & expected;
        if (signalled == 0) return 0;
        // 先清位再消费。清位之后的发布留下新位；之前的发布已经可从队列读取。
        ready.clearBits(signalled);
        return readyLaneMask(queues, signalled);
    }

    /**
     * 逐个探测给定掩码内的完成队列，合成非空队列掩码，不消费序号。
     * @param queues 按账户 Lane 编号排列的完成序号队列
     * @param expected 本轮允许探测的 Lane 位集合
     */
    static long readyLaneMask(LaneSequenceQueue[] queues, long expected) {
        // 当前已确认队列非空的 Lane 位集合，只探测不消费序号
        long mask = 0;
        while (expected != 0) {
            // 当前按拓扑选中或从掩码取出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(expected); expected &= expected - 1;
            if (queues[laneId].hasPending()) mask |= 1L << laneId;
        }
        return mask;
    }

    /**
     * 在有效执行范围内收集已经完成且未拒绝的单笔准入，禁止重复登记，建立 Owner 的待预留索引并递增修订，返回原解析结果；空、未完成或已拒绝事件返回空。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     */
    public ResolvedPlaceOrder collectPlaceAdmission(PlaceAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        if (event.rejection() != null) return null;
        // 本轮正在登记、完成或撤销的订单实体 ID
        long orderId = event.orderId();
        // 本轮业务项所属用户，用于恢复索引或核对账户路由
        long userId = event.userId();
        if (pendingReservations.hasPendingUser(orderId)) {
            throw new IllegalStateException("place admission was collected twice");
        }
        // 按用户计算出的 Lane 编号；当前实现没有继续使用这个局部值
        int laneId = topology.accountLaneId(userId);
        pendingReservations.indexPendingReservation(userId, orderId, event.coreSequence(),
                Math.incrementExact(pendingReservations.totalPendingReservations()));
        revision = Math.incrementExact(revision);
        return event.resolvedOrder();
    }

    /**
     * 仅在账本未激活时，把非空的已收集财库变化应用到唯一财库；空变化直接返回，活动资金交给账本处理。
     * @param delta 冷阶段需要应用到财库的变化，空或无内容时直接跳过
     */
    public void applyCollectedTreasuryBeforeActivation(RuntimeTreasuryDelta delta) {
        assertOwner();
        if (delta == null || delta.size() == 0) return;
        if (laneLifecycle.ledger() != null) throw new IllegalStateException("live money must be applied by SystemLedgerLane");
        delta.apply(treasury);
    }

    /**
     * 活动账本存在时，要求直接财务控制范围已经结束，再复用原撮合事件的财务提交或尝试入账本队列；没有事件或账本时无需派发，返回真不单独代表整条结算已经完成。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     */
    public boolean dispatchMatcherFinancialSettlement(MatcherSettlementEvent event) {
        assertOwner();
        if (event == null) return true;
        if (laneLifecycle.ledger() == null) return true;
        if (ledgerControl != null) throw new IllegalStateException("Matcher money cannot enter an unfinished control scope");
        return event.hasLedgerSettlement() || event.submitTreasurySettlement(laneLifecycle.ledger());
    }

    /**
     * 先尝试派发财务部分，再检查原事件整体完成状态；队列暂满或事件未完成均返回假。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     */
    public boolean pollMatcherFinancialSettlement(MatcherSettlementEvent event) {
        return dispatchMatcherFinancialSettlement(event) && (event == null || event.complete());
    }

    /**
     * 不附加资金及终态接收器，进入统一撮合结算收集入口。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     */
    public RuntimeTreasuryDelta collectMatcherSettlement(MatcherSettlementEvent event) {
        return collectMatcherSettlement(event, null, null);
    }

    /**
     * 为撮合收集补上空的附加变化身份集合，沿用同一次 Lane 遍历。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     * @param fundsAccumulator 可空的资金变化接收器，按实际账本或 Lane 交接来源累计
     * @param terminalOrderSink 可空终态身份接收器，接收完本序号后调用结束钩子
     */
    public RuntimeTreasuryDelta collectMatcherSettlement(
            MatcherSettlementEvent event,
            RuntimeFundsAccumulator fundsAccumulator,
            TerminalOrderSink terminalOrderSink) {
        return collectMatcherSettlement(event, fundsAccumulator, terminalOrderSink, null, null);
    }

    /**
     * 等待事件完成并确认活动财务已派发，收集财库变化、客户身份分配及待预留完成事实；一次遍历 Lane 变化交接终态、余额和响应身份，避免重复累计账本资金。取走变化槽后在 finally 归还，事件本身仍由外层管理。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     * @param fundsAccumulator 可空的资金变化接收器，按实际账本或 Lane 交接来源累计
     * @param terminalOrderSink 可空终态身份接收器，接收完本序号后调用结束钩子
     * @param changedUsers 可空的响应用户变化身份集合，随同 Lane 遍历填充
     * @param changedOrders 可空的响应订单变化身份集合，随同 Lane 遍历填充
     */
    public RuntimeTreasuryDelta collectMatcherSettlement(
            MatcherSettlementEvent event,
            RuntimeFundsAccumulator fundsAccumulator,
            TerminalOrderSink terminalOrderSink,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        if (laneLifecycle.ledger() != null && !event.hasLedgerSettlement())
            throw new IllegalStateException("Matcher money must be dispatched from the ordered commit head");
        // 事件汇总交出的财库变化，后续账户变化收集不会再造一份财库状态
        RuntimeTreasuryDelta aggregate = event.collectTreasuryDelta(fundsAccumulator);
        // 该事件是否已有账本财务交接，避免再从各 Lane 重复累计同一资金变化
        boolean ledgerFunds = event.hasLedgerSettlement();
        // 该计划 Core 序号是否仍有 Owner 侧待预留索引，需要按原计划移除
        boolean ownerPending = pendingReservations.containsSequence(event.plan().coreSequence());
        for (/* 本轮正在处理的计划、订单或变化缓冲项下标 */ int index = 0; index < event.planCount(); index++) {
            if (ownerPending) unindexMatcherPendingReservations(event.plan(index));
            // Lane 不修改全局版本；全部结算成功后由 owner 合并一次触发终态变更。
            if (event.plan(index).completedTrigger() != null) revision = Math.incrementExact(revision);
        }
        // 本次结算或回滚需要处理的账户 Lane 位集合
        long laneMask = event.requiredLaneMask();
        // 本次撮合或撤单的变化容器，按当前分支借出、取走或读取
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            // 尚待收集的 Lane 位集合，每轮清除最低有效位
            long remainingLanes = laneMask;
            // 本轮已经统计完成的待预留项数，随后与原索引数量核对或扣减
            int completed = 0;
            while (remainingLanes != 0) {
                // 本轮按用户路由或从掩码选出的账户 Lane 编号
                int laneId = Long.numberOfTrailingZeros(remainingLanes);
                remainingLanes &= remainingLanes - 1;
                completed += changes.laneDelta(laneId).completedPending();
                if (fundsAccumulator != null && !ledgerFunds) fundsAccumulator.add(changes.laneFundsDelta(laneId));
                if (changes.directPositionIdentities()) {
                    // 本 Lane 已交出的持仓变化及身份键，用于释放发布后的身份引用
                    var positions = changes.laneDelta(laneId).positions();
                    for (/* 本轮正在处理的计划、订单或变化缓冲项下标 */ int index = 0; index < positions.size(); index++)
                        event.identities().releasePublishedPosition(positions.keyAt(index));
                }
                changes.laneDelta(laneId).commitTerminalToOwner(this, laneId, terminalOrderSink, changedUsers,
                        changedOrders);
                // 该 Lane 交出的余额补丁，逐项发布可用余额并标记变化用户
                LaneBalancePatches balances = changes.balancePatch(laneId);
                for (/* 本轮正在处理的计划、订单或变化缓冲项下标 */ int index = 0; index < balances.size(); index++) {
                    balances.publishAvailableAt(this, index);
                    if (changedUsers != null) changedUsers.add(balances.userId(index));
                    markBalancesChanged();
                }
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            pendingReservations.completedBatchItems(event.plan().coreSequence(), completed);
            return aggregate;
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    /**
     * 为用户所属 Lane 借出撤单事件和专用变化槽，准备原提交元数据后在冷阶段执行或投递工作邮箱；投递阶段失败时丢弃事件并归还资源，不把该清理保证扩大到此前的准备步骤。
     * @param coreSequence 原命令或当前收集结果关联的 Core 序号
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param orderId 本次需要读取、登记或撤销的订单实体 ID
     * @param commitTimestamp 原提交业务时间，随账户变化保留
     * @param commitClusterPosition 原提交复制日志位置，随账户变化保留
     * @param identities 与本产品线配套的身份注册表
     */
    public LaneCancelEvent dispatchCancel(
            long coreSequence, long userId, long orderId, long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities) {
        assertOwner();
        // 本轮按用户路由或从掩码选出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        // 本次撮合或撤单的变化容器，按当前分支借出、取走或读取
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges(1L << laneId);
        // 为本次账户撤单借出的事件，消费者结束前保留它及专用变化槽
        LaneCancelEvent event = laneCancelEventPool.pollFirst();
        if (event == null) event = new LaneCancelEvent();
        event.prepare(coreSequence, userId, orderId, commitTimestamp, commitClusterPosition,
                laneId, this, identities, changes);
        try {
            if (!accountLanesStarted()) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneLifecycle.worker(laneId).submit(event);
            }
        } catch (/* 撤单投递或内联执行失败，丢弃事件并归还本次变化槽后继续抛出 */ RuntimeException | Error failure) {
            event.discard();
            releaseMatcherSettlementChanges(changes);
            laneCancelEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    /**
     * 使用默认的不推进 Lane 提交选项派发批量账户撤单。
     * @param coreSequence 原命令或当前收集结果关联的 Core 序号
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param orderIds 同一用户本次批量撤销的非空订单号数组
     * @param commitTimestamp 原提交业务时间，随账户变化保留
     * @param commitClusterPosition 原提交复制日志位置，随账户变化保留
     * @param identities 与本产品线配套的身份注册表
     */
    public LaneCancelEvent dispatchCancelBatch(
            long coreSequence, long userId, long[] orderIds,
            long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities) {
        return dispatchCancelBatch(coreSequence, userId, orderIds, commitTimestamp,
                commitClusterPosition, identities, false);
    }

    /**
     * 为批量撤单补上空的最终订单结果槽，进入统一派发。
     * @param coreSequence 原命令或当前收集结果关联的 Core 序号
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param orderIds 同一用户本次批量撤销的非空订单号数组
     * @param commitTimestamp 原提交业务时间，随账户变化保留
     * @param commitClusterPosition 原提交复制日志位置，随账户变化保留
     * @param identities 与本产品线配套的身份注册表
     * @param commitLane 是否在该批撤单任务中推进账户 Lane 提交水位
     */
    public LaneCancelEvent dispatchCancelBatch(
            long coreSequence, long userId, long[] orderIds,
            long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities, boolean commitLane) {
        return dispatchCancelBatch(coreSequence, userId, orderIds, commitTimestamp, commitClusterPosition,
                identities, commitLane, null);
    }

    /**
     * 要求批量订单非空，先检查目标邮箱容量，再准备同用户撤单事件、变化槽、提交选项和可选响应目标；冷阶段执行或交给账户线程，投递阶段失败归还事件及变化槽。
     * @param coreSequence 原命令或当前收集结果关联的 Core 序号
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param orderIds 同一用户本次批量撤销的非空订单号数组
     * @param commitTimestamp 原提交业务时间，随账户变化保留
     * @param commitClusterPosition 原提交复制日志位置，随账户变化保留
     * @param identities 与本产品线配套的身份注册表
     * @param commitLane 是否在该批撤单任务中推进账户 Lane 提交水位
     * @param resultTarget 可空的最终订单结果接收槽，沿用原批次响应边界
     */
    public LaneCancelEvent dispatchCancelBatch(
            long coreSequence, long userId, long[] orderIds,
            long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities, boolean commitLane, LaneOrderResultTarget resultTarget) {
        assertOwner();
        if (orderIds == null || orderIds.length == 0) {
            throw new IllegalStateException("cancel batch requires orders");
        }
        // 本轮按用户路由或从掩码选出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        ensureMatcherSettlementDispatchCapacity(1L << laneId);
        // 本次撮合或撤单的变化容器，按当前分支借出、取走或读取
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges(1L << laneId);
        // 为本次账户撤单借出的事件，消费者结束前保留它及专用变化槽
        LaneCancelEvent event = laneCancelEventPool.pollFirst();
        if (event == null) event = new LaneCancelEvent();
        event.prepare(coreSequence, userId, orderIds, orderIds.length,
                commitTimestamp, commitClusterPosition, laneId, commitLane, this, identities, changes);
        event.resultTarget(resultTarget);
        try {
            if (!accountLanesStarted()) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneLifecycle.worker(laneId).submit(event);
            }
        } catch (/* 撤单投递或内联执行失败，丢弃事件并归还本次变化槽后继续抛出 */ RuntimeException | Error failure) {
            event.discard();
            releaseMatcherSettlementChanges(changes);
            laneCancelEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    /**
     * 不附加额外响应身份集合，进入统一账户撤单收集入口。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     * @param fundsAccumulator 可空的资金变化接收器，按实际账本或 Lane 交接来源累计
     * @param terminalOrderSink 可空终态身份接收器，接收完本序号后调用结束钩子
     */
    public void collectCancel(LaneCancelEvent event, RuntimeFundsAccumulator fundsAccumulator,
                              TerminalOrderSink terminalOrderSink) {
        collectCancel(event, fundsAccumulator, terminalOrderSink, null, null);
    }

    /**
     * 要求撤单事件已经完成，一次遍历交接终态身份、可用余额及可选资金变化，结束本序号的终态收集；变化槽在 finally 归还，事件另行释放。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     * @param fundsAccumulator 可空的资金变化接收器，按实际账本或 Lane 交接来源累计
     * @param terminalOrderSink 可空终态身份接收器，接收完本序号后调用结束钩子
     * @param changedUsers 可空的响应用户变化身份集合，随同 Lane 遍历填充
     * @param changedOrders 可空的响应订单变化身份集合，随同 Lane 遍历填充
     */
    public void collectCancel(LaneCancelEvent event, RuntimeFundsAccumulator fundsAccumulator,
                              TerminalOrderSink terminalOrderSink,
                              com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
                              com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        assertOwner();
        if (event == null || !event.complete()) throw new IllegalStateException("cancel event is incomplete");
        // 本轮按用户路由或从掩码选出的账户 Lane 编号
        int laneId = event.laneId();
        // 本次撮合或撤单的变化容器，按当前分支借出、取走或读取
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            changes.laneDelta(laneId).commitTerminalToOwner(this, laneId, terminalOrderSink, changedUsers, changedOrders);
            // 撤单后该 Lane 的余额补丁，用于发布解冻后的可用余额变化
            LaneBalancePatches balances = changes.balancePatch(laneId);
            for (/* 本轮正在处理的计划、订单或变化缓冲项下标 */ int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(this, index);
                if (changedUsers != null) changedUsers.add(balances.userId(index));
                markBalancesChanged();
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            if (fundsAccumulator != null) changes.appendFundsDelta(event.requiredLaneMask(), fundsAccumulator);
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    /**
     * 在调用方已经完成撤单收集后清空事件并放回池，空事件视为调用错误。
     * @param event 本次准入、撤单或撮合结算的原事件，沿用其完成和缓冲持有周期
     */
    public void releaseCancel(LaneCancelEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("cancel event is required");
        event.clear();
        laneCancelEventPool.addFirst(event);
    }

    /**
     * 判断当前命令是否已经收集该订单键，删除所用的空值也算有效变化。
     * @param orderId 本次需要读取、登记或撤销的订单实体 ID
     */
    public boolean hasChangedOrder(long orderId) {
        assertOwner();
        return changedOrders.containsKey(orderId);
    }

    /**
     * 取得当前命令已收集的订单变化；空值可能表示删除，也可能未登记，需配合 hasChangedOrder 区分。
     * @param orderId 本次需要读取、登记或撤销的订单实体 ID
     */
    public OrderRuntime changedOrderValue(long orderId) {
        assertOwner();
        return changedOrders.get(orderId);
    }

    /**
     * 取得当前命令已收集的持仓变化，返回借用值而不是再次查询账户。
     * @param positionKey 持仓内部身份键
     */
    public PositionRuntime changedPositionValue(long positionKey) {
        assertOwner();
        return changedPositions.get(positionKey);
    }

    /**
     * Owner 收集终态订单身份的接收器，只传订单、用户和客户号；默认批次入口遍历已有原始缓冲，结束钩子供需要统一收尾的实现使用。
     */
    @FunctionalInterface
    public interface TerminalOrderSink {
        /**
         * 接收一个终态订单的原始身份，避免 Owner 再遍历订单对象取得这些字段。
         * @param orderId 本次需要读取、登记或撤销的订单实体 ID
         * @param userId 业务所属用户身份，按本产品线固定拓扑路由
         * @param clientOrderId 该终态订单所属用户下的客户订单号
         */
        void accept(long orderId, long userId, String clientOrderId);

        /**
         * 按已有 Lane 终态缓冲的顺序逐项调用接收器，不另建订单列表。
         * @param delta 已完成 Lane 交出的终态订单原始缓冲，不在接收器内复制
         */
        default void acceptBatch(LaneCommitDelta delta) {
            for (/* 本轮正在处理的计划、订单或变化缓冲项下标 */ int index = 0; index < delta.terminalOrderCount(); index++) {
                accept(delta.terminalOrderId(index), delta.terminalOrderUser(index),
                        delta.terminalOrderClient(index));
            }
        }

        /**
         * 本序号终态身份已经交接后的结束钩子，默认不做额外处理，由需要批量收尾的实现覆盖。
         */
        default void completeSequence() {
        }
    }

    /**
     * 取得 Owner 复用的剩余数量映射；本入口不清空，调用方沿用既有准备和清理顺序。
     */
    public LongLongHashMap matcherSettlementRemainingScratch() {
        assertOwner();
        return matcherSettlementRemainingScratch;
    }

    /**
     * 先清空再借出本轮复用的订单身份集合，下一次调用会覆盖此前内容。
     */
    public LongHashSet matcherSettlementOrderScratch() {
        assertOwner();
        matcherSettlementOrderScratch.clear();
        return matcherSettlementOrderScratch;
    }

    /**
     * 在所属账户 Lane 完成与本计划序号匹配的待预留。按当前是否有撮合变化槽保存回滚前值及完成计数，补记订单变化和余额前后值；全字段未变化时复用准入版本，避免仅凭修订号判断提交元数据是否相同。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param plan 本次撮合结算计划，订单身份与完成序号沿用原计划
     * @param admissionVersion 可空的既有准入订单版本，全字段相等时可直接复用
     */
    public void completeMatcherPendingReservations(AccountLaneState lane, MatcherSettlementPlan plan,
                                            OrderRuntime admissionVersion) {
        for (/* 本轮正在处理的计划、订单或变化缓冲项下标 */ int index = 0; index < plan.orderCount(); index++) {
            // 本轮正在登记、完成或撤销的订单实体 ID
            long orderId = plan.orderId(index);
            if (lane.pendingReservationSequence(orderId) != plan.coreSequence()) continue;
            // 计划订单必须存在的预留，还需核对该用户确实路由到当前 Lane
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation == null || topology.accountLaneId(reservation.userId()) != lane.laneId()) {
                throw new IllegalStateException("matcher pending reservation is missing from its owner lane");
            }
            // 本次撮合或撤单的变化容器，按当前分支借出、取走或读取
            MatcherSettlementChanges changes = lane.matcherSettlementChanges();
            if (changes == null) {
                accountRollback.captureUserBefore(reservation.userId());
                accountRollback.captureOrderBefore(orderId);
                accountRollback.captureReservationBefore(orderId);
                lane.clientKeysByOrderId().forEach(orderId,
                        /* 该订单已有的客户号内部键，记录回滚前值时直接遍历原索引 */ clientKey -> accountRollback.captureClientOrderBefore(reservation.userId(), clientKey));
            }
            accountRollback.captureBalanceBefore(lane, reservation.userId(), reservation.assetId());
            lane.completePendingReservation(orderId, plan.coreSequence());
            if (changes != null) changes.laneDelta(lane.laneId()).completedPending(changes.laneDelta(lane.laneId()).completedPending() + 1);
            // 完成待预留仍要登记订单变化身份；已有准入版本未改变时直接复用。
            // 比较全部字段，不能只看修订，因为提交时间和位置也可能变化。
            if (changes == null || !changes.laneDelta(lane.laneId()).orders().containsKey(orderId)) {
                // Lane 当前订单值，用完整字段相等判断能否复用原准入版本
                OrderRuntime admitted = lane.order(orderId);
                publishOrder(lane, orderId, admissionVersion != null && admissionVersion.equals(admitted)
                        ? admissionVersion : admitted);
                if (changes == null) {
                    changedOrder(orderId, admitted);
                    markUserChanged(reservation.userId());
                }
            }
            accountRollback.captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
        }
    }

    /**
     * 按计划逐个检查订单，只有原提交时间或日志位置变化时更新元数据；沿用当前普通或撮合变化范围，保留订单状态与业务修订。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param plan 本次撮合结算计划，订单身份与完成序号沿用原计划
     * @param timestamp 原复制日志业务时间，用于更新订单提交元数据
     * @param clusterPosition 原复制日志位置，用于更新订单提交元数据
     */
    public void stampMatcherOrders(AccountLaneState lane, MatcherSettlementPlan plan,
                            long timestamp, long clusterPosition) {
        for (/* 本轮正在处理的计划、订单或变化缓冲项下标 */ int index = 0; index < plan.orderCount(); index++) {
            // 计划中对应的账户当前订单，更新提交元数据时保留其状态和修订
            OrderRuntime order = lane.order(plan.orderId(index));
            if (order == null || order.updatedAtEpochMillis() == timestamp
                    && order.clusterPosition() == clusterPosition) {
                continue;
            }
            if (lane.matcherSettlementChanges() == null) replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
            else updateOrderStatusInLane(lane, order.orderId(), order.status(), order.revision(), timestamp, clusterPosition);
        }
    }

    /**
     * 让待预留跟踪器移除本计划对应的 Owner 侧索引，账户内预留完成由所属 Lane 负责。
     * @param plan 本次撮合结算计划，订单身份与完成序号沿用原计划
     */
    void unindexMatcherPendingReservations(MatcherSettlementPlan plan) {
        pendingReservations.unindexMatcherPendingReservations(plan);
    }

    /**
     * 校验用户、资产及持仓身份后，标记用户、余额和持仓发生变化；这里登记变化范围，不重新计算资产余额。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param assetId 需要校验为非负的资产内部身份，具体余额明细由已有变化缓冲保存
     * @param positionKey 持仓内部身份键
     */
    public void recordUserSettlementChanges(long userId, int assetId, long positionKey) {
        assertOwner();
        if (userId <= 0 || assetId < 0 || positionKey <= 0) {
            throw new IllegalArgumentException("invalid user settlement changes");
        }
        markUserChanged(userId);
        markBalancesChanged();
        changedPosition(positionKey);
    }

    /**
     * 在调用方已经拥有各账户状态的边界内，逐 Lane 重建本地哈希并重新发布提交序号。
     */
    public void rebuildAccountLaneHashes() {
        assertOwner();
        for (AccountLaneState /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane : accountLanes) {
            lane.rebuildLocalHashes();
            publishLaneCommittedSequence(lane);
        }
    }

    /**
     * 校验全局状态产品线及待预留计数，必要时取得账户访问权，先核对全部快照栅栏再捕获各 Lane；最后重读修订、序号和两类哈希确认期间未变，返回固定列表并归还临时访问权。
     * @param fenceSequence 本次账户快照需要满足的完成栅栏序号
     * @param globalState 与本次账户快照配套的全局业务状态
     */
    public java.util.List<AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        assertOwner();
        if (globalState == null || globalState.productLine() != productLine) {
            throw new IllegalArgumentException("global snapshot state is required");
        }
        pendingReservations.assertPendingReservationCounts();
        // 本方法是否需要临时取得账户访问权，不表示此刻已经取得
        boolean acquired = (accountLanesStarted() || systemLedgerStarted()) && !ownerLaneAccess();
        try {
            if (acquired) {
                while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            }
            for (/* 本轮按用户路由或从掩码选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
                onLane(laneId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> {
                    lane.requireSnapshot(fenceSequence);
                    return null;
                });
            }
            // 按 Lane 捕获的账户快照结果，最后核对后转换为固定列表
            java.util.List<AccountLaneSnapshot> snapshots = new java.util.ArrayList<>(accountLanes.length);
            for (/* 本轮按用户路由或从掩码选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
                // 供本轮账户回调捕获的稳定 Lane 下标
                int currentLaneId = laneId;
                snapshots.add(onLane(currentLaneId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> lane.snapshot(fenceSequence)));
            }
            for (AccountLaneSnapshot /* 本轮正在核对或恢复的账户 Lane 快照 */ snapshot : snapshots) {
                // 快照后重读修订、应用与提交序号、状态及资金哈希是否全部一致
                boolean unchanged = onLane(snapshot.laneId(), /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane ->
                        lane.revision() == snapshot.revision()
                                && lane.appliedSequence() == snapshot.appliedSequence()
                                && lane.committedSequence() == snapshot.committedSequence()
                                && lane.localStateHash() == snapshot.localStateHash()
                                && lane.localFundsHash() == snapshot.localFundsHash());
                if (!unchanged) {
                    throw new IllegalStateException("account lane changed during snapshot capture");
                }
            }
            return java.util.List.copyOf(snapshots);
        } finally {
            if (acquired) {
                releaseOwnerLaneAccess();
            }
        }
    }

    /**
     * 要求控制任务、账户工作队列、待预留索引和本命令变化缓冲均已收尾；这是本方法列明的业务栅栏检查，不单独证明所有后台查询或账本邮箱都为空。
     */
    public void requireSnapshotFenceReady() {
        assertOwner();
        if (controlLanes.pending()) throw new IllegalStateException("control Lane work is unfinished");
        if (accountLanesStarted()) {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                SettlementLaneWorker worker = laneLifecycle.worker(laneId);
                if (worker != null && worker.depth() != 0)
                    throw new IllegalStateException("Matcher settlement ring is unfinished");
            }
        }
        pendingReservations.assertPendingReservationCounts();
        if (pendingReservations.totalPendingReservations() != 0 || !pendingReservations.sequenceIndexEmpty()
                || !pendingReservations.pendingUserIndexEmpty() || snapshotProjectionStateDirty()) {
            throw new IllegalStateException("runtime contains unfinished reservation or patch work");
        }
    }

    /**
     * 判断变化缓冲或回滚前值是否仍未提交，并检查冷财库变化或活动财务控制槽；尚未提升全局修订的账户修改也会计入。
     */
    public boolean hasUncommittedCommandChanges() {
        assertOwner();
        return snapshotProjectionStateDirty() || (laneLifecycle.ledger() == null ? treasury.hasChangedValues() : ledgerControl != null);
    }

    /**
     * 只检查当前命令各类变化缓冲、余额标记及回滚前值是否非空，不代替全部异步任务完成判断。
     */
    boolean snapshotProjectionStateDirty() {
        return !changedUsers.isEmpty() || balancesChanged || !changedOrders.isEmpty()
                || !changedReservations.isEmpty() || !changedPositions.isEmpty()
                || !changedLiquidations.isEmpty() || !changedMarkPrices.isEmpty()
                || !changedRiskSnapshots.isEmpty() || !changedRiskScans.isEmpty()
                || !changedLeverages.isEmpty()
                || !changedAlgoOrders.isEmpty() || !changedCancelAllAfterTimers.isEmpty()
                || !changedTriggerOrders.isEmpty() || !changedFeePolicies.isEmpty()
                || accountRollback.hasCaptured();
    }

    /**
     * 在调用方已建立的恢复访问边界内，先核对清单及用户路由，再逐 Lane 恢复并发布提交序号，最后清恢复用待预留信息；激活前限制由外层恢复入口保证。
     * @param snapshots 按实际 Lane 身份携带的账户快照集合
     * @param fenceSequence 本次账户快照需要满足的完成栅栏序号
     * @param globalState 与本次账户快照配套的全局业务状态
     */
    public void restoreAccountLaneSnapshots(java.util.List<AccountLaneSnapshot> snapshots, long fenceSequence,
                                            TradingCoreState globalState) {
        assertOwner();
        validateAccountLaneSnapshotManifest(snapshots, fenceSequence, globalState, topology);
        for (AccountLaneSnapshot /* 本轮正在核对或恢复的账户 Lane 快照 */ snapshot : snapshots) {
            // 本轮按用户路由或从掩码选出的账户 Lane 编号
            int laneId = snapshot.laneId();
            for (Long /* 本轮业务项所属用户，用于恢复索引或核对账户路由 */ userId : snapshot.userIds()) {
                if (!onLane(laneId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> lane.user(userId) != null)) {
                    throw new IllegalArgumentException("account lane contains an incorrectly routed user");
                }
            }
        }
        for (/* 本轮按用户路由或从掩码选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            // 供本轮账户回调捕获的稳定 Lane 下标
            int currentLaneId = laneId;
            onLane(currentLaneId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> {
                lane.forEachUserId(/* 本轮业务项所属用户，用于恢复索引或核对账户路由 */ userId -> {
                    if (!lane.owns(userId)) {
                        throw new IllegalArgumentException("runtime user is absent from its account lane");
                    }
                });
                return null;
            });
        }
        for (AccountLaneSnapshot /* 本轮正在核对或恢复的账户 Lane 快照 */ snapshot : snapshots) {
            onLane(snapshot.laneId(), /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> {
                lane.restore(snapshot);
                publishLaneCommittedSequence(lane);
                return null;
            });
        }
        pendingReservations.clearRestoredPendingReservations();
    }

    /**
     * 只校验快照清单：数量与 Lane 唯一性、应用及提交序号、用户无重复且路由正确，并与全局用户集合完全对应；不在这里逐项重算业务值或本地哈希。
     * @param snapshots 按实际 Lane 身份携带的账户快照集合
     * @param fenceSequence 本次账户快照需要满足的完成栅栏序号
     * @param globalState 与本次账户快照配套的全局业务状态
     * @param topology 用于校验快照数量及用户归属的固定拓扑
     */
    public static void validateAccountLaneSnapshotManifest(
            java.util.List<AccountLaneSnapshot> snapshots,
            long fenceSequence,
            TradingCoreState globalState,
            LaneTopology topology) {
        if (snapshots == null || topology == null || snapshots.size() != topology.accountLaneCount()
                || globalState == null || fenceSequence < 0) {
            throw new IllegalArgumentException("incomplete account lane snapshot set");
        }
        // 从全局用户集合按拓扑分好的每 Lane 预期用户集合
        @SuppressWarnings("unchecked")
        java.util.TreeSet<Long>[] expectedUsers = new java.util.TreeSet[topology.accountLaneCount()];
        for (/* 本轮按用户路由或从掩码选出的账户 Lane 编号 */ int laneId = 0; laneId < expectedUsers.length; laneId++) {
            expectedUsers[laneId] = new java.util.TreeSet<>();
        }
        globalState.users().keySet().forEach(/* 本轮业务项所属用户，用于恢复索引或核对账户路由 */ userId ->
                expectedUsers[topology.accountLaneId(userId)].add(userId));
        // 清单中各 Lane 是否已经出现，防止同一 Lane 重复占位
        boolean[] present = new boolean[topology.accountLaneCount()];
        for (AccountLaneSnapshot /* 本轮正在核对或恢复的账户 Lane 快照 */ snapshot : snapshots) {
            // 本轮按用户路由或从掩码选出的账户 Lane 编号
            int laneId = snapshot.laneId();
            if (laneId < 0 || laneId >= present.length || present[laneId]
                    || snapshot.appliedSequence() != fenceSequence
                    || snapshot.committedSequence() != fenceSequence) {
                throw new IllegalArgumentException("invalid account lane snapshot manifest");
            }
            // 快照用户列表转换成的有序集合，用于同时检查重复和全局集合一致性
            java.util.TreeSet<Long> actualUsers = new java.util.TreeSet<>(snapshot.userIds());
            if (actualUsers.size() != snapshot.userIds().size()
                    || actualUsers.stream().anyMatch(/* 本轮业务项所属用户，用于恢复索引或核对账户路由 */ userId -> topology.accountLaneId(userId) != laneId)) {
                throw new IllegalArgumentException("account lane contains an incorrectly routed user");
            }
            if (!expectedUsers[laneId].equals(actualUsers)) {
                throw new IllegalArgumentException("account lane user manifest differs from global state");
            }
            present[laneId] = true;
        }
    }

    /**
     * 用 CAS 为尚未绑定的运行时登记当前线程；已经绑定时严格要求同一线程，不接纳其他 Lane 线程代替 Owner。
     */
    public void bindOwner() {
        // 当前调用线程，用于核对严格 Owner 或有效账户执行范围
        Thread current = Thread.currentThread();
        if (owner == null && OWNER_THREAD.compareAndSet(this, null, current)) return;
        if (owner != current) {
            throw new IllegalStateException("trading runtime state is bound to another thread");
        }
    }

    /**
     * 检查当前执行权限：Owner 直接通过，尚未绑定则尝试绑定，已经处于合法账户 Lane 命令范围的工作线程也允许通过；其他线程交给严格绑定检查拒绝。
     */
    public void assertOwner() {
        // 当前调用线程，用于核对严格 Owner 或有效账户执行范围
        Thread current = Thread.currentThread();
        if (owner == current) return;
        if (owner == null) { bindOwner(); return; }
        if (currentLaneCommandScope() != null) return;
        bindOwner();
    }

    /**
     * 判断当前线程是否为 Owner，或运行时是否尚未绑定；只返回判断结果，不执行绑定。
     */
    public boolean inOwnerThread() {
        // 当前调用线程，用于核对严格 Owner 或有效账户执行范围
        Thread current = Thread.currentThread();
        return owner == current || owner == null;
    }

    /**
     * 要求账户与账本资源均已关闭，逐个释放 Lane 和财库的所有者，再清运行时 Owner 引用，供被动状态交给另一线程。
     */
    public void releaseOwnerForHandoff() {
        if (accountLanesStarted() || laneLifecycle.ledger() != null) throw new IllegalStateException("all lanes must be closed before handoff");
        for (AccountLaneState /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane : accountLanes) lane.releaseOwnerForHandoff();
        treasury.releaseOwnerForHandoff();
        owner = null;
    }

    /**
     * 在有效执行范围内读取当前业务修订。
     */
    public long revision() {
        assertOwner();
        return revision;
    }

    /**
     * 保存当前业务修订值供本命令失败回滚使用，本方法只读取数值。
     */
    public long commandRevisionCheckpoint() {
        assertOwner();
        return revision;
    }

    /**
     * 要求当前没有批量范围且变化与财务范围都干净，登记批量修改标记；冷阶段同时进入财库批量范围。
     */
    public void beginOrderBatchMutationScope() {
        assertOwner();
        if (orderBatchMutationScope) throw new IllegalStateException("order batch mutation scope is already active");
        if (snapshotProjectionStateDirty() || (laneLifecycle.ledger() == null ? treasury.hasChangedValues() : ledgerControl != null)) {
            throw new IllegalStateException("order batch requires a clean command mutation set");
        }
        orderBatchMutationScope = true;
        if (laneLifecycle.ledger() == null) treasury.beginOrderBatchMutationScope();
    }

    /**
     * 存在批量范围时先结束冷财库对应范围，再清本地批量标记；未开始时直接返回。
     */
    public void endOrderBatchMutationScope() {
        assertOwner();
        if (!orderBatchMutationScope) return;
        if (laneLifecycle.ledger() == null) treasury.endOrderBatchMutationScope();
        orderBatchMutationScope = false;
    }

    /**
     * 当前处于订单批量范围时拒绝指定业务域的交叉修改，范围外不额外拦截。
     * @param domain 批量订单范围内不允许交叉修改的业务域名称
     */
    void rejectUnsupportedOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) {
            throw new IllegalStateException("order batch cannot mutate " + domain);
        }
    }

    /**
     * 为异步命令建立失败续步：中止私有实时批次，向各账户 Lane 派发待预留完成与前值恢复，返回可轮询回调；账户恢复收集完成后才恢复 Owner，再等待财务范围逆向收尾。
     * @param revisionCheckpoint 失败命令开始前的非负全局修订检查点
     * @param coreSequence 原命令或当前收集结果关联的 Core 序号
     */
    public java.util.function.BooleanSupplier beginCommandRollback(long revisionCheckpoint, long coreSequence) {
        assertOwner();
        if (!asynchronousCommands() || revisionCheckpoint < 0 || coreSequence <= 0)
            throw new IllegalArgumentException("invalid asynchronous rollback");
        if (realtimeCapture != null && realtimeCapture.privateStateEnabled()) realtimeCapture.abort();
        // 本 Core 序号登记的单笔待预留订单快照，账户恢复完成前保持不变
        long[] pendingOrders = pendingReservations.pendingOrderIds(coreSequence);
        // 本 Core 序号对应的可空批量待预留描述，恢复时按原已准入项核对
        PendingReservationTracker.PendingBatch batch = pendingReservations.batch(coreSequence);
        // 本次应恢复的单笔订单数与批次剩余项数之和
        int expectedPending = pendingOrders.length + (batch == null ? 0 : batch.remaining());
        // 本次结算或回滚需要处理的账户 Lane 位集合
        long laneMask = accountLanes.length == 64 ? -1L : (1L << accountLanes.length) - 1;
        dispatchControlLanes(laneMask, /* 当前恢复任务实际执行的账户 Lane 下标 */ id -> {
            // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
            AccountLaneState lane = currentLaneCommandScope();
            // 本轮已经统计完成的待预留项数，随后与原索引数量核对或扣减
            int completed = 0;
            for (long /* 本轮正在登记、完成或撤销的订单实体 ID */ orderId : pendingOrders) {
                // 本轮业务项所属用户，用于恢复索引或核对账户路由
                long userId = pendingReservations.pendingUser(orderId);
                if (topology.accountLaneId(userId) != id) continue;
                lane.completePendingReservation(orderId, coreSequence);
                completed++;
            }
            if (batch != null && topology.accountLaneId(batch.userId()) == id) {
                for (/* 本轮正在处理的计划、订单或变化缓冲项下标 */ int index = 0; index < batch.count(); index++) {
                    // 本批此前准入的订单收据，用来核对并完成这个 Core 序号的待预留
                    OrderRuntime order = batch.order(index);
                    if (order == null || lane.pendingReservationSequence(order.orderId()) != coreSequence)
                        continue;
                    lane.completePendingReservation(order.orderId(), coreSequence);
                    completed++;
                }
            }
            accountRollback.restoreLane(lane);
            return completed;
        });
        return /* 由 Owner 轮询的失败恢复续步，先完成账户和 Owner 恢复，再等待账本回滚；返回真后停止轮询 */ new java.util.function.BooleanSupplier() {
            /** 账户与 Owner 前值是否已经恢复并收集，避免等待账本期间重复恢复；不表示财务回滚已经完成。 */
            private boolean ownerRestored;
            /**
             * 首次收集账户恢复时核对预留完成总数、移除 Owner 索引并恢复 Owner 前值；随后可多轮等待财务回滚，完成后恢复修订并清变化。返回真后调用方停止继续推进该回调。
             */
            @Override public boolean getAsBoolean() {
                if (!ownerRestored) {
                    if (!pollControlLanes()) return false;
                    // 本轮已经统计完成的待预留项数，随后与原索引数量核对或扣减
                    int completed = 0;
                    for (/* 正在收集恢复结果的账户 Lane 下标 */ int id = 0; id < accountLanes.length; id++)
                        completed = Math.addExact(completed, (Integer) controlLaneResult(id));
                    if (completed != expectedPending) throw new IllegalStateException("rollback reservation count mismatch");
                    for (long /* 本轮正在登记、完成或撤销的订单实体 ID */ orderId : pendingOrders) {
                        // 本轮业务项所属用户，用于恢复索引或核对账户路由
                        long userId = pendingReservations.pendingUser(orderId);
                        pendingReservations.unindexPendingReservation(orderId, coreSequence, userId,
                                Math.subtractExact(pendingReservations.totalPendingReservations(), 1));
                    }
                    if (batch != null) pendingReservations.completedBatchItems(coreSequence, batch.remaining());
                    accountRollback.restoreOwner();
                    ownerRestored = true;
                }
                if (!rollbackLedgerControl()) return false;
                ledgerControl = null;
                revision = revisionCheckpoint;
                clearChangedKeys();
                return true;
            }
        };
    }

    /**
     * 同步恢复失败命令的预留、各 Lane 和 Owner 前值；活动财务控制尚未收尾时要求改走异步续步。该检查发生在账户恢复之后，因此不能把一次抛错理解为此前没有状态变化。
     * @param revisionCheckpoint 失败命令开始前的非负全局修订检查点
     * @param coreSequence 原命令或当前收集结果关联的 Core 序号
     */
    public void rollbackActiveCommand(long revisionCheckpoint, long coreSequence) {
        assertOwner();
        // 失败补丁可能已经暂存私有实时值，回滚时先丢弃当前实时批次。
        // 后续由权威快照恢复视图；可靠回放只捕获已提交成交。
        if (realtimeCapture != null && realtimeCapture.privateStateEnabled()) realtimeCapture.abort();
        if (revisionCheckpoint < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid runtime command rollback checkpoint");
        }
        if (pendingReservations.containsSequence(coreSequence)
                || pendingReservations.hasBatch(coreSequence)) {
            pendingReservations.completePendingReservations(coreSequence);
        }
        for (/* 本轮按用户路由或从掩码选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> {
                accountRollback.restoreLane(lane);
                return null;
            });
        }
        accountRollback.restoreOwner();
        if (laneLifecycle.ledger() != null && ledgerControl != null)
            throw new IllegalStateException("active Ledger rollback requires its asynchronous continuation");
        if (laneLifecycle.ledger() == null) treasury.rollbackChangedValues();
        revision = revisionCheckpoint;
        clearChangedKeys();
    }

    /**
     * 当前全局修订已经达到 long 上限时抛出算术溢出异常，供修改前检查。
     */
    public void requireRevisionCapacity() {
        assertOwner();
        if (revision == Long.MAX_VALUE) {
            throw new ArithmeticException("runtime command revision overflow");
        }
    }

    /**
     * 账户控制任务活动时只累计本任务修订增量，其他情况通过元数据入口精确递增全局修订，溢出直接报错。
     */
    public void incrementCommandRevision() {
        assertOwner();
        if (controlLanes.active()) {
            controlLanes.recordRevisionDelta();
            return;
        }
        setMetadata(productLine, Math.incrementExact(revision));
    }

    /**
     * 校验产品线非空及修订非负后保存元数据，不在这里重新划分既有账户或执行产品线迁移。
     * @param productLine 要保存到运行时元数据中的产品线，必须非空
     * @param revision 要保存的非负业务修订值
     */
    public void setMetadata(ProductLine productLine, long revision) {
        assertOwner();
        if (productLine == null || revision < 0) throw new IllegalArgumentException("invalid runtime metadata");
        this.productLine = productLine;
        this.revision = revision;
    }

    /**
     * 取得本产品线当前风险扫描开关和预算配置。
     */
    public CoreRiskScanControlView riskScanControl() {
        assertOwner();
        return riskScanControl;
    }

    /**
     * 要求不在不支持的订单批量修改范围，且配置非空，再替换当前风险扫描配置。
     * @param riskScanControl 非空的风险扫描开关与预算配置
     */
    public void setRiskScanControl(CoreRiskScanControlView riskScanControl) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan control");
        if (riskScanControl == null) throw new IllegalArgumentException("risk scan control is required");
        this.riskScanControl = riskScanControl;
    }

    /**
     * 在用户所属 Lane 的有效范围中取得指定标的准入订单索引引用；返回引用不延长账户访问权限。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param symbolId 本产品线标的内部身份
     */
    public com.surprising.aeron.service.order.admission.AdmissionOrderIndex admissionOrders(long userId, int symbolId) {
        return onLane(userId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> lane.admissionOrderIndex(symbolId));
    }

    /**
     * 账户已启动时，要求当前已有账户命令范围或 Owner 已取得交接访问权；不在此发起交接或等待。
     */
    private void requireAccountReadOwnership() {
        if (accountLanesStarted() && currentLaneCommandScope() == null && !ownerLaneAccess())
            throw new IllegalStateException("account read requires its owning Lane thread");
    }

    /**
     * 在当前 Lane 范围内读取该用户，跨 Lane 返回空；没有范围时检查访问权后临时进入所属 Lane 读取。返回运行时引用，不复制用户。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public UserRuntime user(long userId) {
        assertOwner();
        // 本轮按用户路由或从掩码选出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            return scoped.laneId() == laneId ? scoped.user(userId) : null;
        }
        checkLaneAccess();
        // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
        AccountLaneState lane = accountLanes[laneId];
        enterLaneCommandScope(lane);
        try {
            return lane.user(userId);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    /**
     * 读取用户并在未注册时抛出带用户身份的参数异常。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public UserRuntime requireUser(long userId) {
        // 已经按合法账户范围读取的可空用户值，缺失时由 requireUser 拒绝
        UserRuntime user = user(userId);
        if (user == null) {
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        }
        return user;
    }

    /**
     * 校验明确 Lane 与用户路由及所有权后，借出该用户的运行时值。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public UserRuntime user(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        return lane.user(userId);
    }

    /**
     * 当前账户范围内返回原余额，跨 Lane 直接拒绝；无范围时检查访问权并在所属 Lane 内复制该资产余额，再退出范围。不存在时返回空。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param assetId 本次读取或登记变化的资产内部身份
     */
    public BalanceRuntime balance(long userId, int assetId) {
        assertOwner();
        // 本轮按用户路由或从掩码选出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("balance query crossed its owner lane");
            }
            // 该用户在所属 Lane 的原资产余额映射，资产不存在时返回空
            IntObjectHashMap<BalanceRuntime> balances = scoped.userBalances(userId);
            return balances == null ? null : balances.get(assetId);
        }
        checkLaneAccess();
        // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
        AccountLaneState lane = accountLanes[laneId];
        enterLaneCommandScope(lane);
        try {
            return copyBalance(lane.userBalances(userId), assetId);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    /**
     * 活动 Owner 未持有账户访问权时拒绝直接读取，不在此自动等待账户交接。
     */
    private void checkLaneAccess() {
        if (Thread.currentThread() == owner && accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
    }

    /**
     * 核对明确 Lane 与用户的归属后，借出该资产原余额对象，缺少资产时返回空。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param assetId 本次读取或登记变化的资产内部身份
     */
    public BalanceRuntime balance(AccountLaneState lane, long userId, int assetId) {
        requireLaneAccess(lane, userId);
        // 该用户在所属 Lane 的原资产余额映射，资产不存在时返回空
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        return balances == null ? null : balances.get(assetId);
    }

    /**
     * 按正常账户读取规则取可用余额，不存在时返回 Long.MIN_VALUE 作为缺失标记，不能当作零余额。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param assetId 本次读取或登记变化的资产内部身份
     */
    public long publishedAvailableBalance(long userId, int assetId) {
        assertOwner();
        // 本次读取的可空资产余额，缺失与零余额需要区分
        BalanceRuntime balance = balance(userId, assetId);
        return balance == null ? Long.MIN_VALUE : balance.availableUnits();
    }

    /**
     * 核对账户所有权后复制该用户的资产映射和可变余额对象；原表为空引用时返回空。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public IntObjectHashMap<BalanceRuntime> balancesForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        return copyBalances(lane.userBalances(userId));
    }

    /**
     * 在该用户所属 Lane 扫描预留身份，取严格大于游标的最小订单号，没有后续项返回零。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param cursor 上次已消费的订单号，结果必须严格大于它
     */
    public long nextRiskReservationId(long userId, long cursor) {
        // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
        AccountLaneState lane = riskCursorLane(userId);
        // 该用户当前预留或持仓身份索引，只在对应账户读取边界内遍历
        var ids = lane.reservationIds(userId);
        // 当前找到的严格大于数字游标的最小预留订单号，零表示尚未找到
        long selected = 0;
        if (ids != null) {
            // 当前原始身份集合的迭代器，避免先复制完整集合
            var iterator = ids.iterator();
            while (iterator.hasNext()) {
                // 当前遍历到的预留订单号，用数字大小与游标比较
                long id = iterator.nextValue();
                if (id > cursor && (selected == 0 || id < selected)) selected = id;
            }
        }
        return selected;
    }

    /**
     * 按持仓对外身份文本的字典序取下一项，返回它的内部长整数键；游标为减号时从头开始，没有后续项返回零。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param cursor 上次持仓对外身份文本，减号表示从第一项开始
     * @param identities 与本产品线配套的身份注册表
     */
    public long nextRiskPositionKey(long userId, String cursor, RuntimeIdentityRegistry identities) {
        // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
        AccountLaneState lane = riskCursorLane(userId);
        // 该用户当前预留或持仓身份索引，只在对应账户读取边界内遍历
        LongHashSet ids = lane.positionKeys(userId);
        // 当前字典序最小候选持仓对应的内部长整数键
        long selected = 0;
        // 当前候选持仓的对外身份文本，用字典序决定下一项
        String selectedIdentity = null;
        if (ids != null) {
            // 当前原始身份集合的迭代器，避免先复制完整集合
            var iterator = ids.longIterator();
            while (iterator.hasNext()) {
                // 当前遍历到的持仓内部键，先转换为对外身份文本再比较
                long id = iterator.next();
                // 该内部持仓键对应的对外身份文本，下一页按此文本字典序选择
                String identity = identities.positionKey(userId, id);
                if (!"-".equals(cursor) && identity.compareTo(cursor) <= 0) continue;
                if (selectedIdentity == null || identity.compareTo(selectedIdentity) < 0) {
                    selected = id;
                    selectedIdentity = identity;
                }
            }
        }
        return selected;
    }

    /**
     * 核对读取权限并定位当前用户 Lane；已有范围必须路由一致，最后检查实际 Lane 所有权，不自行建立新的命令范围。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    AccountLaneState riskCursorLane(long userId) {
        assertOwner();
        requireAccountReadOwnership();
        // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) {
            lane = accountLanes[topology.accountLaneId(userId)];
        }
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("risk cursor must run in the user's Lane");
        }
        lane.assertOwner();
        return lane;
    }

    /**
     * 只查该用户已有索引，判断是否存在活动订单、非零持仓或仍有冻结数量的预留，供持仓模式修改前校验。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public boolean hasOpenAccountExposure(long userId) {
        assertOwner();
        return onLane(userId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> {
            // 该用户已有的活动订单身份索引，非空即存在订单暴露
            var orders = lane.activeOrderIds(userId);
            if (orders != null && !orders.isEmpty()) return true;
            // 该用户已有的持仓身份索引，继续检查每个持仓的实际数量
            LongHashSet positions = lane.positionKeys(userId);
            if (positions != null) {
                // 当前订单、持仓或预留身份索引的原始迭代器
                var it = positions.longIterator();
                while (it.hasNext()) {
                    // 该用户当前持仓，非零数量表示仍有持仓暴露
                    PositionRuntime position = lane.position(it.next());
                    if (position != null && position.signedQuantitySteps() != 0) return true;
                }
            }
            // 该用户已有的预留身份索引，还需检查实际冻结数量
            var reservations = lane.reservationIds(userId);
            if (reservations != null) {
                // 当前订单、持仓或预留身份索引的原始迭代器
                var it = reservations.iterator();
                while (it.hasNext()) {
                    // 该用户当前预留，仍有非零冻结数量就算账户暴露
                    ReservationRuntime reservation = lane.reservation(it.nextValue());
                    if (reservation != null && reservation.reservedUnits() != 0) return true;
                }
            }
            return false;
        });
    }

    /**
     * 只查用户所属 Lane，判断同标的、同保证金模式是否有 OPEN 订单或非零持仓，供杠杆变更前校验。
     * @param key 需要检查的用户、标的及保证金模式杠杆键
     * @param symbolId 本产品线标的内部身份
     */
    public boolean hasOpenLeverageExposure(CoreLeverageKey key, int symbolId) {
        assertOwner();
        return onLane(key.userId(), /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> {
            // 该用户活动订单身份索引，还需逐单核对标的与保证金模式
            var orders = lane.activeOrderIds(key.userId());
            if (orders != null) {
                // 当前订单、持仓或预留身份索引的原始迭代器
                var it = orders.iterator();
                while (it.hasNext()) {
                    // 活动索引中的当前订单，还需核对 OPEN 状态、标的和保证金模式
                    OrderRuntime order = lane.order(it.nextValue());
                    if (order != null && order.status() == CoreOrderStatus.OPEN
                            && order.symbolId() == symbolId && order.marginMode() == key.marginMode()) return true;
                }
            }
            // 该用户已有的持仓身份索引，继续检查每个持仓的实际数量
            LongHashSet positions = lane.positionKeys(key.userId());
            if (positions != null) {
                // 当前订单、持仓或预留身份索引的原始迭代器
                var it = positions.longIterator();
                while (it.hasNext()) {
                    // 该用户当前持仓，需同时满足非零数量、同标的及同保证金模式
                    PositionRuntime position = lane.position(it.next());
                    if (position != null && position.signedQuantitySteps() != 0
                            && position.symbolId() == symbolId && position.marginMode() == key.marginMode()) return true;
                }
            }
            return false;
        });
    }

    /**
     * 已有 Lane 范围时登记该 Lane 当前用户值，否则把用户身份加入 Owner 的本命令变化集合。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public void markUserChanged(long userId) {
        assertOwner();
        // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) changedUsers.add(userId);
        else laneDelta(lane.laneId()).putUser(userId, lane.user(userId));
    }

    /**
     * 在用户所属 Lane 的有效读取范围内复制预留身份集合。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public LongHashSet reservationIdsForUser(long userId) {
        assertOwner();
        return onLane(userId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> reservationIdsForUser(lane, userId));
    }

    /**
     * 在用户所属 Lane 的有效范围内读取预留数量，不为计数复制集合。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public int reservationCountForUser(long userId) {
        assertOwner();
        return onLane(userId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> reservationCountForUser(lane, userId));
    }

    /**
     * 校验账户归属后复制当前预留身份，缺少索引时返回独立空集合。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public LongHashSet reservationIdsForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        // 该用户当前预留或持仓身份索引，只在对应账户读取边界内遍历
        var ids = lane.reservationIds(userId);
        // 本次独立复制的预留身份集合，缺少原索引时仍返回空集合
        LongHashSet result = new LongHashSet(ids == null ? 0 : ids.size());
        if (ids != null) ids.forEachLong(result::add);
        return result;
    }

    /**
     * 校验账户归属后读取预留索引大小，缺少索引返回零。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public int reservationCountForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        // 该用户当前预留或持仓身份索引，只在对应账户读取边界内遍历
        var ids = lane.reservationIds(userId);
        return ids == null ? 0 : ids.size();
    }

    /**
     * 在所属账户范围内复制用户持仓内部键集合。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public LongHashSet positionKeysForUser(long userId) {
        assertOwner();
        return onLane(userId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> positionKeysForUser(lane, userId));
    }

    /**
     * 在所属账户范围内读取用户持仓键数量。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public int positionCountForUser(long userId) {
        assertOwner();
        return onLane(userId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> positionCountForUser(lane, userId));
    }

    /**
     * 校验账户归属后复制持仓键，缺少索引时返回独立空集合。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public LongHashSet positionKeysForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        // 该用户原持仓键集合，随后创建独立副本
        LongHashSet keys = lane.positionKeys(userId);
        return keys == null ? new LongHashSet() : new LongHashSet(keys);
    }

    /**
     * 校验账户归属后直接读取持仓键数量，缺少索引返回零。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public int positionCountForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        // 该用户原持仓键集合，只读取数量而不复制
        LongHashSet keys = lane.positionKeys(userId);
        return keys == null ? 0 : keys.size();
    }

    /**
     * 校验账户归属后复制并排序杠杆业务键，返回只读集合；没有索引时复用空集合。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     */
    public NavigableSet<CoreLeverageKey> leverageKeysForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        // 该用户原杠杆业务键，随后复制、排序并包装成只读集合
        Set<CoreLeverageKey> keys = lane.leverageKeys(userId);
        return keys == null ? Collections.emptyNavigableSet()
                : Collections.unmodifiableNavigableSet(new TreeSet<>(keys));
    }

    /**
     * 核对明确 Lane 与杠杆键用户的路由及所有权后读取配置值，缺失可返回空。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param key 需要读取的正式杠杆业务键，其中用户必须归属给定 Lane
     */
    public Long leverage(AccountLaneState lane, CoreLeverageKey key) {
        requireLaneAccess(lane, key.userId());
        return lane.leverage(key);
    }

    /**
     * 校验明确 Lane 的所有权后直接借出该订单引用，沿用调用方已经建立的可见性边界。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param orderId 本次需要读取、登记或撤销的订单实体 ID
     */
    public OrderRuntime order(AccountLaneState lane, long orderId) {
        requireLaneAccess(lane);
        return lane.order(orderId);
    }

    /**
     * 校验明确 Lane 的所有权后直接借出该订单预留引用。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param orderId 本次需要读取、登记或撤销的订单实体 ID
     */
    public ReservationRuntime reservation(AccountLaneState lane, long orderId) {
        requireLaneAccess(lane);
        return lane.reservation(orderId);
    }

    /**
     * 校验明确 Lane 的所有权后直接借出该持仓引用。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param positionKey 持仓内部身份键
     */
    public PositionRuntime position(AccountLaneState lane, long positionKey) {
        requireLaneAccess(lane);
        return lane.position(positionKey);
    }

    /**
     * 按当前 Lane、活动路由和必要的全 Lane 检索读取订单。查询范围隐藏未完成预留；Owner 准备范围仅按已变化、批量或触发子单规则暴露在途订单，返回引用仍遵守读取权限。
     * @param orderId 本次需要读取、登记或撤销的订单实体 ID
     */
    public OrderRuntime order(long orderId) {
        assertOwner();
        requireAccountReadOwnership();
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            // 从当前账户或路由定位到的订单，继续按本入口的在途可见性条件处理
            OrderRuntime order = scoped.order(orderId);
            if (order != null) {
                if (scoped.queryScopeActive() && scoped.pendingReservation(orderId)) {
                    return null;
                }
                if (Thread.currentThread() == owner && scoped.pendingReservation(orderId)) {
                    if (changedOrders.get(orderId) != null || orderBatchMutationScope) {
                        return order;
                    }
                    if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                        return order;
                    }
                    return null;
                }
                return order;
            }
            return null;
        }
        if (factIndexes != null) {
            // 活动订单索引提供的用户路由，随后仍需在所属 Lane 读取真实订单或预留
            var route = factIndexes.activeOrderRoute(orderId);
            if (route != null) {
                // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
                AccountLaneState lane = accountLanes[topology.accountLaneId(route.userId())];
                lane.assertOwner();
                // 从当前账户或路由定位到的订单，继续按本入口的在途可见性条件处理
                OrderRuntime order = lane.order(orderId);
                if (order != null) {
                    if (lane.pendingReservation(orderId)) {
                        if (changedOrders.get(orderId) != null || orderBatchMutationScope) {
                            return order;
                        }
                        if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                            return order;
                        }
                        return null;
                    }
                    return order;
                }
            }
        }
        for (/* 当前在可访问账户数组中检索业务值的 Lane 下标 */ int i = 0; i < accountLanes.length; i++) {
            accountLanes[i].assertOwner();
            // 从当前账户或路由定位到的订单，继续按本入口的在途可见性条件处理
            OrderRuntime order = accountLanes[i].order(orderId);
            if (order != null) {
                if (accountLanes[i].pendingReservation(orderId)) {
                    if (changedOrders.get(orderId) != null || orderBatchMutationScope) {
                        return order;
                    }
                    if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                        return order;
                    }
                    continue;
                }
                return order;
            }
        }
        return null;
    }

    /**
     * 按当前 Lane、活动路由和必要的全 Lane 检索读取预留。查询范围隐藏待完成项；Owner 仅在该预留已变化或批量范围内取得在途值，没有订单读取的触发子单特例。
     * @param orderId 本次需要读取、登记或撤销的订单实体 ID
     */
    public ReservationRuntime reservation(long orderId) {
        assertOwner();
        requireAccountReadOwnership();
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            // 本次从账户查到的预留，继续按查询或 Owner 准备范围判断是否可见
            ReservationRuntime reservation = scoped.reservation(orderId);
            if (reservation != null) {
                if (scoped.queryScopeActive() && scoped.pendingReservation(orderId)) {
                    return null;
                }
                if (Thread.currentThread() == owner && scoped.pendingReservation(orderId)) {
                    if (changedReservations.contains(orderId) || orderBatchMutationScope) {
                        return reservation;
                    }
                    return null;
                }
                return reservation;
            }
            return null;
        }
        if (factIndexes != null) {
            // 活动订单索引提供的用户路由，随后仍需在所属 Lane 读取真实订单或预留
            var route = factIndexes.activeOrderRoute(orderId);
            if (route != null) {
                // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
                AccountLaneState lane = accountLanes[topology.accountLaneId(route.userId())];
                lane.assertOwner();
                // 本次从账户查到的预留，继续按查询或 Owner 准备范围判断是否可见
                ReservationRuntime reservation = lane.reservation(orderId);
                if (reservation != null) {
                    if (lane.pendingReservation(orderId)) {
                        if (changedReservations.contains(orderId) || orderBatchMutationScope) {
                            return reservation;
                        }
                        return null;
                    }
                    return reservation;
                }
            }
        }
        for (/* 当前在可访问账户数组中检索业务值的 Lane 下标 */ int i = 0; i < accountLanes.length; i++) {
            accountLanes[i].assertOwner();
            // 本次从账户查到的预留，继续按查询或 Owner 准备范围判断是否可见
            ReservationRuntime reservation = accountLanes[i].reservation(orderId);
            if (reservation != null) {
                if (accountLanes[i].pendingReservation(orderId)) {
                    if (changedReservations.contains(orderId) || orderBatchMutationScope) {
                        return reservation;
                    }
                    continue;
                }
                return reservation;
            }
        }
        return null;
    }

    /**
     * 在有效账户权限内读取持仓，优先使用当前范围或持仓身份路由，必要时检查各 Lane；借出原引用，不复制持仓。
     * @param positionKey 持仓内部身份键
     */
    public PositionRuntime position(long positionKey) {
        assertOwner();
        requireAccountReadOwnership();
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            return scoped.position(positionKey);
        }
        if (indexIdentities != null) {
            // 该持仓键已登记的身份元数据，用其中用户身份定位所属 Lane
            var identity = indexIdentities.positionIdentity(positionKey);
            if (identity != null) {
                // 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限
                AccountLaneState lane = accountLanes[topology.accountLaneId(identity.userId())];
                lane.assertOwner();
                // 按身份路由或账户检索得到的持仓，存在时直接借出，不按数量过滤
                PositionRuntime position = lane.position(positionKey);
                if (position != null) return position;
            }
        }
        for (/* 当前在可访问账户数组中检索业务值的 Lane 下标 */ int i = 0; i < accountLanes.length; i++) {
            accountLanes[i].assertOwner();
            // 按身份路由或账户检索得到的持仓，存在时直接借出，不按数量过滤
            PositionRuntime position = accountLanes[i].position(positionKey);
            if (position != null) return position;
        }
        return null;
    }

    /**
     * 要求当前已经进入该用户 Lane 范围，借出原始持仓键列表；只能在范围退出前使用，缺失时返回共享空列表。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param symbolId 本产品线标的内部身份
     */
    public org.eclipse.collections.impl.list.mutable.primitive.LongArrayList positionKeysForUserAndSymbolPrimitive(
            long userId, int symbolId) {
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null || scoped.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("primitive position index access requires owning Lane scope");
        }
        // 该标的按用户分组的原始持仓键列表索引
        LongObjectHashMap<org.eclipse.collections.impl.list.mutable.primitive.LongArrayList> byUser =
                scoped.symbolPositionKeys(symbolId);
        // 该用户该标的的原始持仓键列表，借用期间必须保持当前 Lane 范围有效
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList keys = byUser == null ? null : byUser.get(userId);
        return keys == null ? EMPTY_POSITION_KEYS : keys;
    }

    /**
     * 当前有账户范围时只查该 Lane，否则要求有效账户读取权限后逐 Lane 查强平状态，返回借用引用。
     * @param liquidationId 需要查询的强平实体 ID
     */
    public LiquidationRuntime liquidation(long liquidationId) {
        assertOwner();
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.liquidation(liquidationId);
        requireAccountReadOwnership();
        for (AccountLaneState /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane : accountLanes) {
            lane.assertOwner();
            // 当前 Lane 查到的可空强平运行时引用
            LiquidationRuntime value = lane.liquidation(liquidationId);
            if (value != null) return value;
        }
        return null;
    }

    /**
     * 在用户所属 Lane 按标的及持仓方向索引定位活动强平，再取得其运行时引用。
     * @param userId 业务所属用户身份，按本产品线固定拓扑路由
     * @param symbolId 本产品线标的内部身份
     * @param positionSide 用于定位活动强平的持仓方向
     */
    public LiquidationRuntime activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        assertOwner();
        return onLane(userId, /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane -> {
            // 该用户按标的分组的活动强平索引
            IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(userId);
            // 该标的按持仓方向保存的活动强平身份索引
            LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(symbolId);
            // 从用户、标的和方向索引找到的可空强平身份
            Long liquidationId = bySide == null ? null : bySide.get(positionSide.ordinal());
            return liquidationId == null ? null : lane.liquidation(liquidationId);
        });
    }

    /**
     * 供 Core 配置边界检查尚未结束的强平：禁止在账户命令范围内调用，优先查询已绑定事实索引；缺少索引时要求账户读取权限并检查所有 Lane，标的内部身份为负表示全部标的。
     * @param instrumentId 与配置检查配套的对外标的 ID，全部标的查询会传空给事实索引
     * @param symbolId 要筛选的内部标的身份，负值表示全部标的
     */
    public boolean hasUnresolvedLiquidation(String instrumentId, int symbolId) {
        assertOwner();
        if (currentLaneCommandScope() != null) throw new IllegalStateException("instrument check belongs to Core owner");
        if (factIndexes != null) return factIndexes.hasUnresolvedLiquidation(
                symbolId < 0 ? null : instrumentId);
        requireAccountReadOwnership();
        for (AccountLaneState /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane : accountLanes) {
            lane.assertOwner();
            if (lane.anyLiquidation(/* 当前检查的强平状态，完成或取消之外的状态均算未结束 */ value ->
                    (symbolId < 0 || value.symbolId() == symbolId)
                    && value.status() != CoreLiquidationState.Status.COMPLETED
                    && value.status() != CoreLiquidationState.Status.CANCELED)) return true;
        }
        return false;
    }

    /**
     * 在有效执行范围内按内部标的身份读取本产品线标记价。
     * @param symbolId 本产品线标的内部身份
     */
    public MarkPriceRuntime markPrice(int symbolId) {
        assertOwner();
        return markPrices.get(symbolId);
    }

    /**
     * 要求当前线程拥有传入 Lane，再读取本运行时的共享标记价元数据。
     * @param lane 已经定位的账户 Lane 引用，访问与借用范围由对应入口核对
     * @param symbolId 本产品线标的内部身份
     */
    public MarkPriceRuntime markPrice(AccountLaneState lane, int symbolId) {
        lane.assertOwner();
        return markPrices.get(symbolId);
    }

    /**
     * 当前有账户范围时只查该 Lane，否则在有效账户权限下逐 Lane 查风险快照，返回借用引用。
     * @param positionKey 持仓内部身份键
     */
    public RiskSnapshotRuntime riskSnapshot(long positionKey) {
        assertOwner();
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.riskSnapshot(positionKey);
        requireAccountReadOwnership();
        for (AccountLaneState /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane : accountLanes) {
            lane.assertOwner();
            // 当前 Lane 查到的可空风险快照引用
            RiskSnapshotRuntime value = lane.riskSnapshot(positionKey);
            if (value != null) return value;
        }
        return null;
    }

    /**
     * 按内部标的身份取得当前风险扫描续步状态。
     * @param symbolId 本产品线标的内部身份
     */
    public RiskScanRuntime riskScan(int symbolId) {
        assertOwner();
        return riskScans.get(symbolId);
    }

    /**
     * 扫描当前可访问 Lane，统计其全部持仓记录并收集非零持仓用户，扫描后才检查数量上限；超限抛出查询过大异常，不能把该参数理解为提前停止遍历的预算。
     * @param maximumPositions 扫描完成后校验的持仓记录数量上限，超出则拒绝该查询
     */
    public LongHashSet usersWithPublishedPositions(int maximumPositions) {
        assertOwner();
        requireAccountReadOwnership();
        // 本次扫描收集到的非零持仓用户集合，按用户身份去重
        LongHashSet users = new LongHashSet();
        // 本次已经扫描的持仓记录总数，全部扫描后才与上限比较
        int totalPositions = 0;
        // 当前线程已经进入的可空账户 Lane 范围，不允许借此读取其他 Lane
        AccountLaneState scoped = currentLaneCommandScope();
        for (AccountLaneState /* 当前执行、检查或恢复的账户 Lane，借出引用不扩大访问权限 */ lane : accountLanes) {
            if (scoped != null && lane != scoped) continue;
            lane.assertOwner();
            totalPositions = Math.addExact(totalPositions, lane.positionCount());
            for (PositionRuntime /* 当前读取的可空持仓，风险暴露检查只计非零数量 */ position : lane.positionValuesForRead())
                if (position.signedQuantitySteps() != 0) users.add(position.userId());
        }
        if (totalPositions > maximumPositions)
            throw new com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException();
        return users;
    }

    /**
     * 严格绑定或核对 Owner 后，读取从 Lane 回执汇总的持仓数量。
     */
    public int publishedPositionCount() {
        bindOwner();
        return publishedPositions;
    }

    /**
     * 严格核对 Owner，校验 Lane 与非负数量，再按新旧差额精确更新全局持仓计数；不复制持仓明细。
     * @param laneId 需要更新数量回执的账户 Lane 编号
     * @param count 该 Lane 最新发布的非负持仓记录数量
     */
    public void publishLanePositionCount(int laneId, int count) {
        bindOwner();
        if (laneId < 0 || laneId >= publishedLanePositionCounts.length || count < 0)
            throw new IllegalArgumentException("invalid Lane position count receipt");
        // 按当前 Lane 新旧数量差精确计算的全局持仓计数
        int total = Math.addExact(publishedPositions,
                Math.subtractExact(count, publishedLanePositionCounts[laneId]));
        publishedLanePositionCounts[laneId] = count;
        publishedPositions = total;
    }

    /**
     * 在整体 complete 标记尚未结束的扫描中，选择标的内部身份最小的一条。
     */
    public RiskScanRuntime firstIncompleteRiskScan() {
        assertOwner();
        // 当前整体未完成且内部标的身份最小的候选风险扫描
        RiskScanRuntime selected = null;
        for (RiskScanRuntime /* 当前遍历的风险扫描，整体完成与风险阶段完成按各入口分别判断 */ scan : riskScans.values()) {
            if (!scan.complete()
                    && (selected == null || scan.symbolId() < selected.symbolId())) {
                selected = scan;
            }
        }
        return selected;
    }

    /**
     * 只在 riskComplete 尚未结束的扫描中，按最近调度修订、再按标的身份选择最先需要推进的一条。
     */
    public RiskScanRuntime firstRiskIncompleteScan() {
        assertOwner();
        // 当前风险阶段未完成且按调度顺序最先的候选扫描
        RiskScanRuntime selected = null;
        for (RiskScanRuntime /* 当前遍历的风险扫描，整体完成与风险阶段完成按各入口分别判断 */ scan : riskScans.values()) {
            if (!scan.riskComplete() && (selected == null
                    || compareRiskProgress(scan, selected) < 0)) {
                selected = scan;
            }
        }
        return selected;
    }

    /**
     * 先比较最近调度修订，相同再比较内部标的身份，形成确定的风险推进顺序。
     * @param left 需要比较调度顺序的左侧风险扫描
     * @param right 需要比较调度顺序的右侧风险扫描
     */
    static int compareRiskProgress(RiskScanRuntime left, RiskScanRuntime right) {
        // 两条扫描最近调度修订的比较结果，非零时直接决定顺序
        int scheduled = Long.compare(left.lastScheduledRevision(), right.lastScheduledRevision());
        return scheduled != 0 ? scheduled : Integer.compare(left.symbolId(), right.symbolId());
    }

    /**
     * 统计整体 complete 标记尚未完成的扫描数量。
     */
    public int incompleteRiskScanCount() {
        assertOwner();
        // 当前累计的整体未完成风险扫描条数
        int count = 0;
        for (RiskScanRuntime /* 当前遍历的风险扫描，整体完成与风险阶段完成按各入口分别判断 */ scan : riskScans.values()) {
            if (!scan.complete()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 读取下一个强平身份数值；本入口只读，不分配或递增编号。
     */
    public long nextLiquidationId() {
        assertOwner();
        return nextLiquidationId;
    }

    /**
     * 在实际财库拥有者线程上物化不可变财库导出值，不把财库所有权交给 Owner。
     * @param identities 与本产品线配套的身份注册表
     */
    public CoreTreasuryState treasuryForQuery(RuntimeIdentityRegistry identities) {
        return queryLedgerAtRest(/* 读取回调所获得的唯一财库，由实际拥有者执行物化 */ ledger -> RuntimeStateMaterializer.materializeTreasury(ledger, identities));
    }

    /**
     * 显式导出用的同步读取：要求没有未结束财务控制，冷阶段直接读取，活动账本则投递临时读取条目并共用五秒入队与完成预算。超时不撤销已入队回调，回调引用仍须保持有效；生产查询使用非阻塞派发。
     * @param <T> 财库读取回调返回的结果类型
     * @param read 在财库实际拥有者线程执行的读取函数，已入队后超时不会自动取消
     */
    public <T> T queryLedgerAtRest(java.util.function.Function<TreasuryRuntime, T> read) {
        assertOwner();
        java.util.Objects.requireNonNull(read, "Ledger read is required");
        if (ledgerControl != null) throw new IllegalStateException("Treasury query requires committed control work");
        if (laneLifecycle.ledger() == null) return read.apply(treasury);
        /**
         * 同步财库导出临时放入账本的读取条目，保存返回值或运行时异常，并通过 volatile 标记交接完成。
         */
        final class Query implements SystemLedgerLane.Entry {
            /** 账本读取返回的结果，完成标记发布后由等待方取得。 */
            private T value;
            /** 读取回调捕获的 RuntimeException，完成后由调用线程继续抛出。 */
            private RuntimeException failure;
            /** 读取回调是否已经返回或抛错，以 volatile 发布结果可见性；成功与失败另查对应字段。 */
            private volatile boolean complete;
            /**
             * 在账本线程执行读取，保存返回值或 RuntimeException，再用 volatile 完成标记交接；Error 由工作线程故障边界处理。
             * @param ledger 账本线程交给当前读取条目的唯一财库引用
             */
            @Override public void apply(TreasuryRuntime ledger) {
                try { value = read.apply(ledger); }
                catch (/* 财库读取回调抛出的运行时异常，保存后由等待线程继续抛出 */ RuntimeException invalidQuery) { failure = invalidQuery; }
                finally { complete = true; }
            }
        }
        // 本次临时账本读取条目，同时保存返回值、异常和完成标记
        Query query = new Query();
        // 入账本队列与等待完成共用的五秒单调纳秒截止时间
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!laneLifecycle.ledger().offer(query)) {
            laneLifecycle.ledger().assertHealthy();
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Treasury query mailbox timeout");
            java.util.concurrent.locks.LockSupport.parkNanos(this, 100_000L);
        }
        while (!query.complete) {
            laneLifecycle.ledger().assertHealthy();
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Treasury query completion timeout");
            java.util.concurrent.locks.LockSupport.parkNanos(this, 100_000L);
        }
        if (query.failure != null) throw query.failure;
        return query.value;
    }

    /**
     * 校验非空读取且没有财务控制占用，冷阶段直接应用，活动账本尝试入队并返回是否接纳，不在此等待结果。
     * @param query 需要在财库拥有者线程执行的读取条目，活动路径只尝试入队
     */
    public boolean dispatchLedgerQuery(SystemLedgerLane.Entry query) {
        assertOwner();
        if (query == null) throw new IllegalArgumentException("Ledger query is required");
        if (ledgerControl != null) throw new IllegalStateException("Ledger query requires committed control work");
        if (laneLifecycle.ledger() == null) { query.apply(treasury); return true; }
        return laneLifecycle.ledger().offer(query);
    }

    /**
     * 确认已有财务控制允许风险输入，先把风险读取绑定到同一账本范围，再内联或尝试投递；未入队时保留原请求供上层重试。
     * @param risk 本命令风险输入请求，接入同一财务范围后由账本执行
     */
    public boolean dispatchLedgerRiskInput(RiskScanCoordinator risk) {
        assertOwner();
        if (ledgerControl != null && !ledgerControl.allowsRiskInput())
            throw new IllegalStateException("risk input requires a completed financial phase before scope close");
        risk.bindLedgerScope(ledgerControl);
        if (laneLifecycle.ledger() == null) { risk.apply(treasury); return true; }
        return laneLifecycle.ledger().offer(risk);
    }

    /**
     * 借出唯一财库引用，账户命令范围内禁止取得；账本激活后仍须遵守财库自己的线程检查，这个引用不转移所有权。
     */
    public TreasuryRuntime treasury() {
        assertOwner();
        if (currentLaneCommandScope() != null) {
            throw new IllegalStateException("Account Lane cannot access SystemLedger-owned Treasury");
        }
        return treasury;
    }

    /**
     * 先清空再借出复用财库变化对象，下一次调用会覆盖此前内容。
     */
    public RuntimeTreasuryDelta scratchTreasuryDelta() {
        assertOwner();
        scratchTreasuryDelta.clear();
        return scratchTreasuryDelta;
    }

    /**
     * 借出当前配置名称的迭代入口，供 Owner 在明确的查询输入边界内消费；沿用原配置迭代顺序，不创建独立快照。
     */
    public Iterable<String> instrumentIdsForQuery() {
        assertOwner();
        return () -> /* 把原配置对象转换成标的名称的借用迭代器，消费期间保持查询输入边界有效 */ new java.util.Iterator<>() {
            /** 当前配置集合的借用迭代器，消费期间沿用 Owner 查询输入边界，不复制配置。 */
            private final java.util.Iterator<CoreInstrument> values = instruments.values().iterator();
            /**
             * 沿用原配置迭代器判断是否还有标的。
             */
            public boolean hasNext() { return values.hasNext(); }
            /**
             * 从原配置迭代器取得下一条标的配置并返回其外部身份。
             */
            public String next() { return values.next().instrumentId(); }
        };
    }

    /**
     * 把外部标的 ID 解析为内部身份，再读取已有标的配置。
     * @param instrumentId 本产品线标的身份，文本重载先解析为内部数值
     */
    public CoreInstrument instrument(String instrumentId) {
        return instrument(com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
    }

    /**
     * 在有效执行范围内按内部身份读取标的配置，缺失返回空。
     * @param instrumentId 本产品线标的身份，文本重载先解析为内部数值
     */
    public CoreInstrument instrument(int instrumentId) {
        assertOwner();
        return instruments.get(instrumentId);
    }

    /**
     * 要求当前线程拥有传入 Lane，再解析外部标的 ID 并读取共享配置。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param instrumentId 本产品线标的身份，文本重载先解析为内部数值
     */
    public CoreInstrument instrument(AccountLaneState lane, String instrumentId) {
        lane.assertOwner();
        return instruments.get(com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
    }

    /**
     * 禁止订单批量范围内注册，校验配置非空且身份未注册后保存原配置对象。
     * @param instrument 本产品线标的配置对象，已有对象更新要求保持注册身份
     */
    public void registerInstrument(CoreInstrument instrument) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument registry");
        if (instrument == null || instruments.containsKey(instrument.instrumentIdValue())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument is already registered");
        }
        instruments.put(instrument.instrumentIdValue(), instrument);
    }

    /**
     * 要求原对象正是注册表中的对象，且新旧标的身份一致，再原地更新配置，保持已有引用身份。
     * @param instrument 本产品线标的配置对象，已有对象更新要求保持注册身份
     * @param updated 与原标的身份相同的新配置内容
     */
    public void updateInstrumentConfiguration(CoreInstrument instrument, CoreInstrument updated) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument configuration");
        if (instrument == null || updated == null || instruments.get(instrument.instrumentIdValue()) != instrument
                || instrument.instrumentIdValue() != updated.instrumentIdValue()) {
            throw new IllegalArgumentException("invalid canonical instrument configuration update");
        }
        instrument.updateConfiguration(updated);
    }

    /**
     * 校验已注册对象身份和维护参数，在允许的控制边界内原地更新维护状态。
     * @param instrument 本产品线标的配置对象，已有对象更新要求保持注册身份
     * @param maintenance 非空的标的维护状态配置
     */
    public void updateInstrumentMaintenance(
            CoreInstrument instrument,
            com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument operation");
        if (instrument == null || maintenance == null || instruments.get(instrument.instrumentIdValue()) != instrument) {
            throw new IllegalArgumentException("invalid canonical instrument operation");
        }
        instrument.updateMaintenance(maintenance);
    }

    /**
     * 在用户所属账户范围内用正式业务键读取杠杆值。
     * @param key 本次查询或修改使用的业务键
     */
    public Long leverage(CoreLeverageKey key) {
        assertOwner();
        return onLane(key.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.leverage(key));
    }

    /**
     * 检查用户、规范标的文本和保证金模式，复用当前 Lane 或 Owner 的查找探针读取杠杆，最后释放探针文本引用。标的规范化由命令入口完成。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param canonicalSymbol 已经由入口规范化的标的名称，直接用于杠杆键比较
     * @param marginMode 订单、持仓或杠杆配置的保证金模式
     */
    public Long leverage(long userId, String canonicalSymbol, CoreMarginMode marginMode) {
        assertOwner();
        if (userId <= 0 || canonicalSymbol == null || marginMode == null) {
            throw new IllegalArgumentException("invalid runtime leverage lookup");
        }
        requireAccountReadOwnership();
        // 当前已经进入的账户范围，用于核对用户归属及选择 Lane 自己的杠杆探针
        AccountLaneState scopedLane = currentLaneCommandScope();
        if (scopedLane != null && scopedLane.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("leverage read crossed its owning Lane");
        // 本线程复用的杠杆查找探针，只查原映射，finally 清理临时引用
        LeverageLookup lookup = scopedLane == null ? ownerLeverageLookup : scopedLane.leverageLookup();
        lookup.set(userId, canonicalSymbol, marginMode);
        try {
            return accountLanes[topology.accountLaneId(userId)].leverage(lookup);
        } finally {
            lookup.clear();
        }
    }

    /**
     * 核对明确 Lane 与用户归属后，复用该 Lane 探针读取杠杆，finally 清理探针引用。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param canonicalSymbol 已经由入口规范化的标的名称，直接用于杠杆键比较
     * @param marginMode 订单、持仓或杠杆配置的保证金模式
     */
    public Long leverage(AccountLaneState lane, long userId, String canonicalSymbol, CoreMarginMode marginMode) {
        requireLaneAccess(lane, userId);
        // 本线程复用的杠杆查找探针，只查原映射，finally 清理临时引用
        LeverageLookup lookup = lane.leverageLookup();
        lookup.set(userId, canonicalSymbol, marginMode);
        try { return lane.leverage(lookup); }
        finally { lookup.clear(); }
    }

    /**
     * 校验杠杆至少为一倍且不在订单批量范围内，写入用户所属 Lane 并登记用户杠杆键；范围外调用再记录 Owner 变化。
     * @param key 本次查询或修改使用的业务键
     * @param leveragePpm 百万分比表示的杠杆，一倍为 1000000
     */
    public void putLeverage(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("leverage state");
        if (key == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid runtime leverage");
        }
        onLane(key.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            lane.storeLeverage(key, leveragePpm);
            // 该用户已有的杠杆业务键集合，缺失时新建后登记
            HashSet<CoreLeverageKey> userKeys = lane.leverageKeys(key.userId());
            if (userKeys == null) {
                userKeys = new HashSet<>();
                lane.storeLeverageKeys(key.userId(), userKeys);
            }
            userKeys.add(key);
            return null;
        });
        if (currentLaneCommandScope() == null) recordLeverageChange(key, leveragePpm);
    }

    /**
     * 收集已经完成的杠杆变化并按需捕获实时值，账户任务不直接操作共享捕获器。
     * @param key 本次查询或修改使用的业务键
     * @param leveragePpm 百万分比表示的杠杆，一倍为 1000000
     */
    public void recordLeverageChange(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        changedLeverages.add(key);
        if (realtimeCapture != null) realtimeCapture.leverage(key, leveragePpm);
    }

    /**
     * 取得事实索引中的算法单路由，尚未绑定索引时返回空。
     * @param id 本次路由、登记或删除使用的业务实体 ID
     */
    public com.surprising.aeron.service.core.index.AlgoOrderIndex.AlgoRoute algoRoute(long id) {
        assertOwner(); return factIndexes == null ? null : factIndexes.algoRoute(id);
    }

    /**
     * 优先取已有触发单路由；没有事实索引时要求账户读取权限，并从原触发单构造路由。
     * @param id 本次路由、登记或删除使用的业务实体 ID
     */
    public com.surprising.aeron.service.core.index.TriggerOrderIndex.TriggerRoute triggerRoute(long id) {
        assertOwner();
        if (factIndexes != null) return factIndexes.triggerRoute(id);
        requireAccountReadOwnership();
        // 找出的可空触发单值，用它构造冷阶段路由
        var value = triggerOrder(id);
        return value == null ? null : com.surprising.aeron.service.core.index.TriggerOrderIndex.TriggerRoute.from(value);
    }

    /**
     * 当前有账户范围时只查该 Lane，否则在有效读取权限下逐 Lane 查询算法单。
     * @param algoOrderId 算法单实体 ID
     */
    public CoreAlgoOrderState algoOrder(long algoOrderId) {
        assertOwner();
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.algoOrder(algoOrderId);
        requireAccountReadOwnership();
        for (AccountLaneState /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane : accountLanes) {
            lane.assertOwner();
            // 当前 Lane 查到的可空算法单，存在时直接返回
            CoreAlgoOrderState value = lane.algoOrder(algoOrderId);
            if (value != null) return value;
        }
        return null;
    }

    /**
     * 在用户所属 Lane 检查同用户客户算法单号是否已经存在。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientId 同一用户下需要检查的客户算法单号或客户触发单号
     */
    public boolean hasAlgoClient(long userId, String clientId) {
        assertOwner();
        return onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.anyAlgoOrder(
                /* 当前检查的算法单，需同时匹配用户及客户号 */ value -> value.userId() == userId && value.clientAlgoOrderId().equals(clientId)));
    }

    /**
     * 把算法单变化登记到当前 Lane 容器或 Owner 本命令变化缓冲。
     * @param order 要交接的算法单变化值
     */
    public void publishAlgoOrder(CoreAlgoOrderState order) {
        assertOwner();
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) changedAlgoOrders.put(order.algoOrderId(), order);
        else laneDelta(scoped.laneId()).putAlgo(order.algoOrderId(), order);
    }

    /**
     * 校验算法单非空且允许修改后，在所属 Lane 保存并登记变化。
     * @param algoOrder 需要保存的非空算法单状态
     */
    public void putAlgoOrder(CoreAlgoOrderState algoOrder) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("algo-order state");
        if (algoOrder == null) throw new IllegalArgumentException("invalid runtime algo order");
        onLane(algoOrder.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            lane.storeAlgoOrder(algoOrder.algoOrderId(), algoOrder);
            publishAlgoOrder(algoOrder);
            return null;
        });
    }

    /**
     * 读取用户及标的范围对应的定时全撤状态。
     * @param key 本次查询或修改使用的业务键
     */
    public CoreCancelAllAfterState cancelAllAfterTimer(CoreCancelAllAfterKey key) {
        assertOwner();
        return cancelAllAfterTimers.get(key);
    }

    /**
     * 校验业务键与定时器非空，写入定时全撤表并记录变化键。
     * @param key 本次查询或修改使用的业务键
     * @param timer 非空的定时全撤状态
     */
    public void putCancelAllAfterTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("timer state");
        if (key == null || timer == null) throw new IllegalArgumentException("invalid runtime cancel-all-after timer");
        cancelAllAfterTimers.put(key, timer);
        changedCancelAllAfterTimers.add(key);
    }

    /**
     * 读取本命令已经收集的触发单变化，保留到有序发布清理结束。
     * @param id 本次路由、登记或删除使用的业务实体 ID
     */
    public CoreTriggerOrderState changedTriggerOrder(long id) { assertOwner(); return changedTriggerOrders.get(id); }

    /**
     * 当前有账户范围时只查该 Lane，否则在有效账户权限下逐 Lane 读取触发单。
     * @param triggerOrderId 触发单实体 ID
     */
    public CoreTriggerOrderState triggerOrder(long triggerOrderId) {
        assertOwner();
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.triggerOrder(triggerOrderId);
        requireAccountReadOwnership();
        for (AccountLaneState /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane : accountLanes) {
            lane.assertOwner();
            // 当前 Lane 查到的可空触发单，存在时直接返回
            CoreTriggerOrderState value = lane.triggerOrder(triggerOrderId);
            if (value != null) return value;
        }
        return null;
    }

    /**
     * 保存触发单修改前值，在用户所属 Lane 写入新值并登记变化；禁止不支持的订单批量交叉修改。
     * @param triggerOrder 需要保存或交接的触发单状态
     */
    public void putTriggerOrder(CoreTriggerOrderState triggerOrder) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        if (triggerOrder == null) throw new IllegalArgumentException("invalid runtime trigger order");
        accountRollback.captureTriggerOrderBefore(triggerOrder.triggerOrderId(), triggerOrder.userId());
        onLane(triggerOrder.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            lane.putTrigger(triggerOrder);
            publishTriggerOrder(triggerOrder.triggerOrderId(), triggerOrder);
            return null;
        });
    }

    /**
     * 要求明确 Lane 归属且已经接入撮合变化容器，直接写入触发单并交接本次变化。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param triggerOrder 需要保存或交接的触发单状态
     */
    public void putTriggerOrder(AccountLaneState lane, CoreTriggerOrderState triggerOrder) {
        requireLaneAccess(lane, triggerOrder.userId());
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        if (lane.matcherSettlementChanges() == null)
            throw new IllegalStateException("trigger completion requires its settlement Lane");
        lane.putTrigger(triggerOrder);
        laneDelta(lane).putTrigger(triggerOrder.triggerOrderId(), triggerOrder);
    }

    /**
     * 尽可能先保存原触发单前值，再在所属范围删除并登记空值；原实体缺失且没有范围时只保留 Owner 删除标记。
     * @param triggerOrderId 触发单实体 ID
     */
    public void removeTriggerOrder(long triggerOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        // 待删除的原触发单，存在时先按原用户保存前值
        CoreTriggerOrderState current = triggerOrder(triggerOrderId);
        if (current != null) {
            accountRollback.captureTriggerOrderBefore(triggerOrderId, current.userId());
        }
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            scoped.removeTrigger(triggerOrderId);
            publishTriggerOrder(triggerOrderId, null);
        } else if (current != null) {
            onLane(current.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                lane.removeTrigger(triggerOrderId);
                publishTriggerOrder(triggerOrderId, null);
                return null;
            });
        } else {
            changedTriggerOrders.put(triggerOrderId, null);
        }
    }

    /**
     * 按外部标的名称建立有序映射副本，配置对象本身仍与运行时共享。
     */
    public Map<String, CoreInstrument> instrumentsForRuntime() {
        assertOwner();
        // 按标的名称新建的有序配置映射，里面仍引用原配置对象
        Map<String, CoreInstrument> exported = new TreeMap<>();
        instruments.forEachValue(/* 当前收集的标的配置，以其外部身份作为导出键 */ instrument -> exported.put(instrument.instrumentId(), instrument));
        return exported;
    }

    /**
     * 在调用方建立的读取边界内，收集各 Lane 杠杆快照到新有序映射；此入口不自行取得账户交接权。
     */
    public Map<CoreLeverageKey, Long> leveragesForRuntime() {
        assertOwner();
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        TreeMap<CoreLeverageKey, Long> values = new TreeMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.leveragesSnapshot()));
        }
        return values;
    }

    /**
     * 在已有读取边界内，把各 Lane 算法单引用收集到新有序映射，不深复制业务值。
     */
    public Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        assertOwner();
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        TreeMap<Long, CoreAlgoOrderState> values = new TreeMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++)
            onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> { lane.forEachAlgoOrder(values::put); return null; });
        return values;
    }

    /**
     * 借出定时全撤原映射，沿用 Owner 与恢复边界，不返回独立副本。
     */
    public Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return cancelAllAfterTimers;
    }

    /**
     * 在所属账户触发单索引中检查客户触发单号是否存在。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientId 同一用户下需要检查的客户算法单号或客户触发单号
     */
    public boolean hasTriggerClient(long userId, String clientId) {
        return onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            // 该用户已有的触发单身份集合，只在所属账户范围内遍历
            LongHashSet ids = lane.triggerIds(userId);
            if (ids == null) return false;
            // 当前用户触发单身份的原始迭代器
            var iterator = ids.longIterator();
            while (iterator.hasNext()) {
                if (lane.triggerOrder(iterator.next()).clientTriggerOrderId().equals(clientId)) return true;
            }
            return false;
        });
    }

    /**
     * 汇总同标的、模式、方向及买卖侧的活动触发数量，再用当前同 OCO 组最大值与新数量的较大者替换该最大项；其余旧组成员仍按现有公式计入。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param view 需要加入容量计算的新触发单视图
     */
    public long projectedTriggerCapacity(long userId, com.surprising.aeron.protocol.CoreTriggerOrderStateView view) {
        return onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            // 符合新单标的、模式、方向及买卖侧的旧触发数量总和
            long capacity = 0;
            // 符合条件且属于新单同一非空 OCO 组的旧单最大数量
            long sameOcoMax = 0;
            // 该用户已有的触发单身份集合，只在所属账户范围内遍历
            LongHashSet ids = lane.triggerIds(userId);
            if (ids != null) {
                // 当前用户触发单身份的原始迭代器
                var iterator = ids.longIterator();
                while (iterator.hasNext()) {
                    // 当前参与投影容量筛选的触发单，只有活动且分类一致才累计
                    CoreTriggerOrderState trigger = lane.triggerOrder(iterator.next());
                    if (!trigger.status().open() || !trigger.instrumentId().equals(view.instrumentId())
                            || trigger.marginMode() != view.marginMode()
                            || trigger.positionSide() != view.positionSide() || trigger.side() != view.side()) continue;
                    capacity = Math.addExact(capacity, trigger.quantitySteps());
                    if (!view.ocoGroupId().isEmpty() && view.ocoGroupId().equals(trigger.ocoGroupId())) {
                        sameOcoMax = Math.max(sameOcoMax, trigger.quantitySteps());
                    }
                }
            }
            return Math.addExact(Math.subtractExact(capacity, sameOcoMax),
                    Math.max(sameOcoMax, view.quantitySteps()));
        });
    }

    /**
     * 有当前账户范围时只复制该 Lane 的触发单映射，否则在已有读取边界内收集各 Lane；业务值仍为原引用。
     */
    public Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        assertOwner();
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            // 本次新建的汇总映射，按既有读取边界收集各账户内容
            TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
            scoped.forEachTriggerOrder(values::put);
            return values;
        }
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                lane.forEachTriggerOrder(values::put);
                return null;
            });
        }
        return values;
    }

    /**
     * 在 Owner 路由边界读取财库保存的划转用户身份，账户命令范围禁止调用。
     * @param transferId 待完成划转的业务身份
     */
    public long pendingTransferUser(long transferId) {
        assertOwner();
        if (currentLaneCommandScope() != null) throw new IllegalStateException("transfer routing belongs to Owner");
        return treasury.pendingTransferUser(transferId);
    }

    /**
     * 当前账户范围内读取本 Lane 划转；没有范围时只允许账户未启动的冷路径，通过财库路由定位用户，路由缺失返回空。
     * @param transferId 待完成划转的业务身份
     */
    public TransferFundsCommand pendingTransfer(long transferId) {
        assertOwner();
        // 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用
        AccountLaneState lane = currentLaneCommandScope();
        if (lane != null) return lane.pendingTransfer(transferId);
        if (accountLanesStarted()) throw new IllegalStateException("active transfer identity must be read on its Account Lane");
        // 本次余额、预留或划转实际所属的用户身份
        long userId = pendingTransferUser(transferId);
        return userId == 0 ? null : accountLanes[topology.accountLaneId(userId)].pendingTransfer(transferId);
    }

    /**
     * 要求活动 Owner 已取得账户访问权，复制各 Lane 待完成划转引用并返回只读有序映射。
     */
    public Map<Long, TransferFundsCommand> pendingTransfersSnapshot() {
        assertOwner();
        if (accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("pending transfers snapshot requires owner lane access");
        }
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        TreeMap<Long, TransferFundsCommand> values = new TreeMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> { lane.forEachPendingTransfer(values::put); return null; });
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * 单页划转路由，仅携带划转 ID、所属用户及账户掩码，不包含账户业务正文。record 直接保存传入数组，不自行复制、校验或限制页大小。
     * @param ids 本页划转 ID 原始数组，record 直接借用
     * @param users 与划转 ID 按相同下标对应的所属用户原始数组
     * @param laneMask 本页涉及的账户 Lane 位集合，构造不额外校验
     */
    public record PendingTransferRoutes(long[] ids, long[] users, long laneMask) { }

    /**
     * 向财库取得有限页的划转 ID、用户 ID 和 Lane 掩码，仅交接路由，不复制账户划转正文。
     * @param limit 本次向财库请求的最大分页条数，具体范围由财库入口检查
     */
    public PendingTransferRoutes pendingTransferRoutes(int limit) {
        assertOwner();
        // 财库给出的有限页路由，数组内只含身份和所属用户
        TreasuryRuntime.PendingTransferRoutes routes = treasury.pendingTransferRoutes(limit, topology::accountLaneMask);
        return new PendingTransferRoutes(routes.ids(), routes.users(), routes.laneMask());
    }

    /**
     * 用该页最后一个划转 ID 推进财库分页边界，空页使用零。
     * @param page 已经读取的划转路由页，完成时使用最后一个 ID 推进页边界
     */
    public void completePendingTransferPage(PendingTransferRoutes page) {
        assertOwner();
        treasury.completePendingTransferPage(page.ids().length == 0 ? 0 : page.ids()[page.ids().length - 1]);
    }

    /**
     * 只在账户未启动时按路由页读取划转，完成页边界后返回固定列表；活动查询交给账户 Lane。
     * @param limit 本次向财库请求的最大分页条数，具体范围由财库入口检查
     */
    public List<TransferFundsCommand> pendingTransfers(int limit) {
        assertOwner();
        if (accountLanesStarted()) throw new IllegalStateException("active transfer queries must run on Account Lanes");
        // 本次冷读取使用的路由页，读取结束后推进其最后 ID 边界
        PendingTransferRoutes page = pendingTransferRoutes(limit);
        // 本页逐项读取出的原划转引用列表，随后生成固定列表
        var values = new java.util.ArrayList<TransferFundsCommand>(page.ids().length);
        for (long /* 本轮正在恢复、读取或捕获的业务实体身份 */ id : page.ids()) values.add(pendingTransfer(id));
        completePendingTransferPage(page);
        return List.copyOf(values);
    }

    /**
     * 要求账户已停止，校验映射容量、键值非空及划转身份一致，再清旧路由与账户值并逐项恢复。
     * @param restored 需要恢复的政策或待完成划转映射，按对应入口校验
     */
    public void restorePendingTransfers(Map<Long, TransferFundsCommand> restored) {
        assertOwner();
        if (accountLanesStarted()) throw new IllegalStateException("transfer restore requires stopped Account Lanes");
        if (restored == null || restored.size() > MAX_PENDING_TRANSFERS
                || restored.entrySet().stream().anyMatch(/* 恢复映射当前项，用于核对键非空且与划转值身份一致 */ entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey() != entry.getValue().transferId()))
            throw new IllegalArgumentException("invalid pending transfer snapshot");
        treasury.clearPendingTransfers();
        for (AccountLaneState /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane : accountLanes) lane.clearPendingTransferValues();
        restored.forEach((/* 本轮正在恢复、读取或捕获的业务实体身份 */ id, /* 恢复输入中当前划转值，按其用户分别登记财库路由和账户正文 */ transfer) -> {
            treasury.restorePendingTransferRoute(id, transfer.sourceUserId());
            accountLanes[topology.accountLaneId(transfer.sourceUserId())].storePendingTransfer(id, transfer);
        });
    }

    /**
     * 查询财库路由索引是否还有待完成划转容量。
     */
    public boolean hasPendingTransferCapacity() {
        assertOwner();
        return treasury.hasPendingTransferCapacity();
    }

    /**
     * 把划转 ID 与所属用户登记到唯一财库路由索引。
     * @param id 本次路由、登记或删除使用的业务实体 ID
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void registerPendingTransferRoute(long id, long userId) {
        assertOwner();
        treasury.registerPendingTransferRoute(id, userId);
    }

    /**
     * 让财库按划转 ID 和用户身份完成对应路由，不在此重复删除账户正文。
     * @param id 本次路由、登记或删除使用的业务实体 ID
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void completePendingTransferRoute(long id, long userId) {
        assertOwner();
        treasury.completePendingTransferRoute(id, userId);
    }

    /**
     * 禁止订单批量交叉修改，在所属账户范围保存新划转；冷路径可按路由取得 Lane 并检查容量，活动路径要求账户范围，冷执行同时登记财库路由。
     * @param transfer 非空待完成划转业务值
     */
    public void putPendingTransfer(TransferFundsCommand transfer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        if (transfer == null) throw new IllegalArgumentException("pending transfer is required");
        // 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) {
            if (accountLanesStarted()) throw new IllegalStateException("active transfer writes require their Account Lane");
            if (!hasPendingTransferCapacity())
                throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
            lane = accountLanes[topology.accountLaneId(transfer.sourceUserId())];
        }
        if (lane.laneId() != topology.accountLaneId(transfer.sourceUserId()) || lane.hasPendingTransfer(transfer.transferId()))
            throw new IllegalArgumentException("pending transfer already exists or reached the wrong Lane");
        lane.storePendingTransfer(transfer.transferId(), transfer);
        if (!accountLanesStarted()) registerPendingTransferRoute(transfer.transferId(), transfer.sourceUserId());
    }

    /**
     * 核对划转所属用户与 Lane，存在时检查修订容量并删除账户值；冷阶段同时完成财库路由。未找到返回假，用户冲突按幂等冲突拒绝。
     * @param transferId 待完成划转的业务身份
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public boolean removePendingTransfer(long transferId, long userId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        // 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) {
            if (accountLanesStarted()) throw new IllegalStateException("active transfer completion requires its Account Lane");
            // 财库路由查到的划转所属用户，零表示未找到
            long routed = pendingTransferUser(transferId);
            if (routed == 0) return false;
            if (routed != userId) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
            lane = accountLanes[topology.accountLaneId(userId)];
        }
        if (lane.laneId() != topology.accountLaneId(userId)) throw new IllegalStateException("transfer completion crossed Account Lane");
        // 当前 Lane 的原划转值，存在时核对所属用户再删除
        TransferFundsCommand current = lane.pendingTransfer(transferId);
        if (current == null) return false;
        if (current.sourceUserId() != userId) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        requireRevisionCapacity();
        lane.removePendingTransferValue(transferId);
        if (!accountLanesStarted()) completePendingTransferRoute(transferId, userId);
        return true;
    }

    /**
     * 取得当前不可变费用政策版本，沿用其已有读写边界。
     */
    public FeePolicyBook feePolicyBook() {
        assertOwner();
        return feePolicies;
    }

    /**
     * 取得当前费用政策书提供的快照映射。
     */
    public Map<Long, CoreFeePolicyState> feePoliciesSnapshot() {
        assertOwner();
        return feePolicies.snapshot();
    }

    /**
     * 要求账户已停止，从恢复映射重建费用政策版本，并清空政策变化身份。
     * @param restored 需要恢复的政策或待完成划转映射，按对应入口校验
     */
    public void restoreFeePolicies(Map<Long, CoreFeePolicyState> restored) {
        assertOwner();
        if (accountLanesStarted()) throw new IllegalStateException("fee policy restore requires stopped Lanes");
        feePolicies = FeePolicyBook.from(restored);
        changedFeePolicies.clear();
    }

    /**
     * 活动阶段只允许配置 Lane 0 修改，构造并合并不可变政策版本；内容未变直接返回，否则登记变化并推进命令修订。
     * @param command 本次已经解码的费用政策或下单参数
     */
    public void upsertFeePolicy(com.surprising.aeron.protocol.UpsertFeePolicyCommand command) {
        assertOwner();
        if (accountLanesStarted() && (currentLaneCommandScope() == null || currentLaneCommandScope().laneId() != 0))
            throw new IllegalStateException("active fee policy updates must run on configuration Lane 0");
        rejectUnsupportedOrderBatchMutation("fee-policy state");
        // 从政策命令构造的新政策状态
        CoreFeePolicyState next = CoreFeePolicyState.from(command);
        // 合并后的不可变政策书，与旧对象相同表示内容未变
        FeePolicyBook updated = feePolicies.upsert(next);
        if (updated == feePolicies) return;
        requireRevisionCapacity();
        feePolicies = updated;
        changedFeePolicies.add(next.policyId());
        incrementCommandRevision();
    }

    /**
     * 按给定状态替换产品元数据、风险配置、标的、杠杆、算法单、定时器和触发单；依次调用现有 Lane 入口，不替换资金、订单或持仓，也不自行建立回滚事务。
     * @param source 要用于替换辅助状态的完整业务镜像
     */
    public void replaceAuxiliaryState(TradingCoreState source) {
        assertOwner();
        setMetadata(source.productLine(), source.revision());
        setRiskScanControl(source.riskState().scanControl());
        instruments.clear();
        source.instruments().values().forEach(/* 当前收集的标的配置，以其外部身份作为导出键 */ instrument -> instruments.put(instrument.instrumentIdValue(), instrument));
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                lane.clearLeverageValues();
                lane.clearLeverageKeys();
                lane.clearAlgoOrderValues();
                lane.clearTriggerOrderValues();
                lane.clearTriggerIds();
                return null;
            });
        }
        source.leverages().forEach(this::putLeverage);
        source.algoOrders().values().forEach(this::putAlgoOrder);
        cancelAllAfterTimers.clear();
        cancelAllAfterTimers.putAll(source.cancelAllAfterTimers());
        source.triggerOrders().values().forEach(this::putTriggerOrder);
    }

    /**
     * 查客户号对应订单，把原始索引的缺失值零转换为空引用。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientKey 客户订单号内部键，零表示没有客户号
     */
    public Long orderIdByClient(long userId, long clientKey) {
        assertOwner();
        // 当前客户号、订单或预留对应的订单实体 ID
        long orderId = orderIdByClientValue(userId, clientKey);
        return orderId == 0 ? null : orderId;
    }

    /**
     * 在当前所属账户范围或临时用户 Lane 范围中查客户号，跨 Lane 拒绝，缺失保留零。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientKey 客户订单号内部键，零表示没有客户号
     */
    public long orderIdByClientValue(long userId, long clientKey) {
        assertOwner();
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != topology.accountLaneId(userId)) {
                throw new IllegalStateException("client-order lookup crossed its owner Lane");
            }
            return orderIdByClientValue(scoped, userId, clientKey);
        }
        return onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            return orderIdByClientValue(lane, userId, clientKey);
        });
    }

    /**
     * 核对明确 Lane 与用户归属后读取客户号原始索引，缺少用户映射返回零。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientKey 客户订单号内部键，零表示没有客户号
     */
    public long orderIdByClientValue(AccountLaneState lane, long userId, long clientKey) {
        requireLaneAccess(lane, userId);
        // 该用户客户号到订单的原始映射，零表示没有对应订单
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        return userClientOrders == null ? 0L : userClientOrders.get(clientKey);
    }

    /**
     * 保存用户前值，在所属 Lane 写入用户并登记用户身份；活动阶段保持 Lane 可变值形态，随后交接用户变化。
     * @param user 需要写入账户的用户状态
     */
    public void putUser(UserRuntime user) {
        assertOwner();
        accountRollback.captureUserBefore(user.userId());
        onLane(user.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            // 该 Lane 已有用户值，活动写入尽量保留其 Lane 形态
            UserRuntime current = lane.user(user.userId());
            // 本次实际交给 Lane 保存的用户值，活动阶段使用 Lane 可变形态
            UserRuntime value = user;
            if (accountLanesStarted()) {
                value = current == null || current.productLine() != user.productLine() ? user.laneValue()
                        : current.laneValue().withState(user.revision(), user.positionMode());
            }
            lane.storeUser(user.userId(), value);
            lane.registerUser(user.userId());
            return null;
        });
        publishUser(user.userId(), user);
        if (currentLaneCommandScope() == null) markUserChanged(user.userId());
    }

    /**
     * 把用户修订按默认一步推进。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void advanceUserRevision(long userId) {
        advanceUserRevision(userId, 1);
    }

    /**
     * 要求正增量；撮合范围内只累计该用户修订差额，等 Lane 完成时冻结最终值，其他路径精确相加并保存用户状态。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param count 必须为正的用户修订增量
     */
    public void advanceUserRevision(long userId, long count) {
        if (count <= 0) throw new IllegalArgumentException("revision advance must be positive");
        assertOwner();
        // 当前可空撮合变化容器，存在时只累计用户修订差额
        MatcherSettlementChanges settlementChanges = currentMatcherSettlementChangesScope();
        if (settlementChanges != null) {
            // 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用
            AccountLaneState lane = currentLaneCommandScope();
            if (lane == null || topology.accountLaneId(userId) != lane.laneId()) {
                throw new IllegalStateException("user revision crossed its owner lane");
            }
            if (lane.user(userId) == null) {
                throw new IllegalArgumentException("runtime user is not registered: " + userId);
            }
            // 同一结算可能多次推进用户修订，先合并增量。
            // 等所属 Lane 完成该结算时再冻结最终用户值。
            settlementChanges.addUserRevision(lane.laneId(), userId, count);
            return;
        }
        // 已注册用户的当前值，用来精确增加修订并保持持仓模式
        UserRuntime current = requireUser(userId);
        setUserState(userId, Math.addExact(current.revision(), count), current.positionMode());
    }

    /**
     * 使用明确 Lane，把用户修订按默认一步推进。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void advanceUserRevision(AccountLaneState lane, long userId) {
        advanceUserRevision(lane, userId, 1);
    }

    /**
     * 核对 Lane 后忽略非正增量；有撮合变化槽时累计用户差额，否则交给普通修订入口。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param count 用户修订增量，非正值在本重载直接忽略
     */
    public void advanceUserRevision(AccountLaneState lane, long userId, long count) {
        requireLaneAccess(lane, userId);
        if (count <= 0) return;
        if (lane.matcherSettlementChanges() == null) {
            advanceUserRevision(userId, count);
            return;
        }
        if (lane.user(userId) == null)
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        lane.matcherSettlementChanges().addUserRevision(lane.laneId(), userId, count);
    }

    /**
     * 校验用户、修订及持仓模式，保存前值，在所属 Lane 创建或更新用户元数据，再交接独立变化收据。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param revision 要写入的用户或订单业务修订值
     * @param mode 用户需要保存的非空持仓模式
     */
    public void setUserState(long userId, long revision, com.surprising.aeron.protocol.CorePositionMode mode) {
        assertOwner();
        if (userId <= 0 || revision < 0 || mode == null) throw new IllegalArgumentException("invalid runtime user");
        accountRollback.captureUserBefore(userId);
        // 用户所属 Lane 写入后交回的用户值，随后登记本命令收据
        UserRuntime updated = onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            // 当前 Lane 已有的可空用户，缺失则创建新用户值
            UserRuntime current = lane.user(userId);
            // 本次创建或由原用户更新得到的值，活动阶段转成 Lane 形态
            UserRuntime value = current == null ? new UserRuntime(productLine, userId, revision, mode)
                    : (accountLanesStarted() ? current.laneValue() : current).withState(revision, mode);
            if (accountLanesStarted()) value = value.laneValue();
            lane.storeUser(userId, value);
            lane.registerUser(userId);
            return value;
        });
        publishUser(userId, updated);
        if (currentLaneCommandScope() == null) markUserChanged(userId);
    }

    /**
     * 保存用户前值，删除用户值、余额及各用户索引并清客户号反向关系，再登记用户删除；已有订单、预留及持仓实体不在此递归删除。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void removeUser(long userId) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            // 该用户客户号到订单的原始映射，零表示没有对应订单
            Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
            if (userClientOrders != null) userClientOrders.forEachLong((/* 当前客户号内部键，与订单反向索引一起维护 */ clientKey, /* 当前客户号、订单或预留对应的订单实体 ID */ orderId) -> {
                removeClientOrderReverse(lane, orderId, clientKey);
            });
            lane.removeUserValue(userId);
            lane.removeUser(userId);
            lane.removeUserBalances(userId);
            lane.removeUserClientOrders(userId);
            lane.removeReservationIds(userId);
            lane.removeActiveOrderIds(userId);
            lane.removePositionKeys(userId);
            lane.removeLeverageKeys(userId);
            return null;
        });
        publishUser(userId, null);
        markUserChanged(userId);
    }

    /**
     * 读取输入余额数值，保存前值后在所属 Lane 建立独立余额对象并捕获后值；没有外层账户范围时再标记 Owner 变化。
     * @param balance 本次需要保存或更新的资产余额值
     */
    public void putBalance(BalanceRuntime balance) {
        assertOwner();
        // 本次余额、预留或划转实际所属的用户身份
        long userId = balance.userId();
        // 本次余额或预留对应的资产内部身份
        int assetId = balance.assetId();
        // 输入余额当前可用数量，先取数值再在 Lane 建立独立对象
        long availableUnits = balance.availableUnits();
        // 输入余额当前冻结数量，先取数值再在 Lane 建立独立对象
        long lockedUnits = balance.lockedUnits();
        onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureBalanceBefore(userId, assetId);
            // 该用户原资产余额表，缺失时按当前写入分支建立或拒绝
            IntObjectHashMap<BalanceRuntime> userBalances = lane.userBalances(userId);
            if (userBalances == null) {
                userBalances = new IntObjectHashMap<>();
                lane.storeUserBalances(userId, userBalances);
            }
            userBalances.put(assetId, new BalanceRuntime(userId, assetId, availableUnits, lockedUnits));
            accountRollback.captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        if (currentLaneCommandScope() == null) {
            markBalancesChanged();
            markUserChanged(userId);
        }
    }

    /**
     * 明确撮合 Lane 内保存给定余额对象及前后补丁；没有撮合变化槽则沿用普通余额写入入口。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param balance 本次需要保存或更新的资产余额值
     */
    public void putBalance(AccountLaneState lane, BalanceRuntime balance) {
        requireLaneAccess(lane, balance.userId());
        if (lane.matcherSettlementChanges() == null) {
            putBalance(balance);
            return;
        }
        accountRollback.captureBalanceBefore(lane, balance.userId(), balance.assetId());
        // 本次所属用户的原资产余额映射，用于按预留资产取得余额
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(balance.userId());
        if (balances == null) {
            balances = new IntObjectHashMap<>();
            lane.storeUserBalances(balance.userId(), balances);
        }
        balances.put(balance.assetId(), balance);
        accountRollback.captureBalanceAfter(lane, balance.userId(), balance.assetId());
    }

    /**
     * 保存用户及订单前值，在所属 Lane 写入订单，再登记订单与用户变化。
     * @param order 本次安装、更新或交接的订单值
     */
    public void putOrder(OrderRuntime order) {
        assertOwner();
        accountRollback.captureUserBefore(order.userId());
        accountRollback.captureOrderBefore(order.orderId());
        onLane(order.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        changedOrder(order.orderId(), order);
        markUserChanged(order.userId());
    }

    /**
     * 保存前值，旧预留若仍待撮合则拒绝；移除旧用户索引后写入新预留及所属用户索引，并登记新旧用户变化。
     * @param reservation 与订单对应的资金预留值
     */
    public void putReservation(ReservationRuntime reservation) {
        assertOwner();
        accountRollback.captureUserBefore(reservation.userId());
        accountRollback.captureReservationBefore(reservation.orderId());
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null && onLane(previous.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.pendingReservation(previous.orderId()))) {
            throw new IllegalStateException("pending reservation must be replaced through its owner lane");
        }
        if (previous != null) {
            onLane(previous.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                lane.removeReservationValue(previous.orderId());
                lane.removeReservationId(previous.userId(), previous.orderId());
                return null;
            });
        }
        onLane(reservation.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            lane.storeReservation(reservation.orderId(), reservation.laneValue());
            lane.addReservationId(reservation.userId(), reservation.orderId());
            return null;
        });
        publishReservation(reservation.orderId(), reservation);
        if (previous == null) {
        }
        markReservationChanged(reservation.orderId());
        markUserChanged(reservation.userId());
        if (previous != null) markUserChanged(previous.userId());
    }

    /**
     * 保存订单前值并禁止更换用户，在所属 Lane 替换订单；没有撮合变化范围时登记 Owner 订单和用户变化。
     * @param order 本次安装、更新或交接的订单值
     */
    public void replaceOrder(OrderRuntime order) {
        assertOwner();
        accountRollback.captureUserBefore(order.userId());
        accountRollback.captureOrderBefore(order.orderId());
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        OrderRuntime previous = order(order.orderId());
        if (previous != null && previous.userId() != order.userId()) {
            throw new IllegalArgumentException("runtime order owner cannot change");
        }
        onLane(order.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        if (previous == null) {
            if (currentMatcherSettlementChangesScope() == null) {
            }
        }
        if (currentMatcherSettlementChangesScope() == null) {
            changedOrder(order.orderId(), order);
            markUserChanged(order.userId());
        }
    }

    /**
     * 保存原订单及用户前值，存在时在所属 Lane 删除并交出删除标记；原订单缺失直接结束。
     * @param orderId 本次操作关联的订单实体 ID
     */
    public void removeOrder(long orderId) {
        assertOwner();
        accountRollback.captureOrderBefore(orderId);
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        OrderRuntime previous = order(orderId);
        if (previous != null) {
            accountRollback.captureUserBefore(previous.userId());
            onLane(previous.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> { lane.removeOrder(orderId); return null; });
            publishOrder(orderId, null);
            changedOrder(orderId, null);
            markUserChanged(previous.userId());
        }
    }

    /**
     * 有活动订单路由时直接在路由用户 Lane 查询预留；没有路由时才逐 Lane 查找。
     * @param orderId 本次操作关联的订单实体 ID
     */
    private ReservationRuntime findReservation(long orderId) {
        if (factIndexes != null) {
            // 已有活动订单路由，提供应查询的所属用户
            var route = factIndexes.activeOrderRoute(orderId);
            if (route != null) {
                return onLane(route.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.reservation(orderId));
            }
        }
        for (AccountLaneState /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane : accountLanes) {
            // 当前 Lane 查到的可空预留，存在时结束逐 Lane 查找
            ReservationRuntime res = onLane(lane.laneId(), /* 本轮进入的明确账户 Lane，仅用于读取其预留 */ l -> l.reservation(orderId));
            if (res != null) return res;
        }
        return null;
    }

    /**
     * 禁止预留更换用户，在所属 Lane 保存前值并替换；若旧值仍待撮合，同步维护待预留及相关余额前后补丁，再按当前范围交接变化。
     * @param reservation 与订单对应的资金预留值
     */
    public void replaceReservation(ReservationRuntime reservation) {
        assertOwner();
        // 通过已有路由或账户检索找到的原预留，先禁止改变用户
        ReservationRuntime existing = findReservation(reservation.orderId());
        if (existing != null && existing.userId() != reservation.userId()) {
            throw new IllegalArgumentException("runtime reservation owner cannot change");
        }
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        ReservationRuntime previous = onLane(reservation.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            accountRollback.captureUserBefore(reservation.userId());
            accountRollback.captureReservationBefore(reservation.orderId(), reservation.userId());
            // 当前用户 Lane 内的原预留或持仓，用于替换前值和索引比较
            ReservationRuntime prev = lane.reservation(reservation.orderId());
            // 原预留是否仍待撮合，决定是否同时记录相关资产余额前后值
            boolean pending = prev != null && lane.pendingReservation(prev.orderId());
            if (pending) {
                accountRollback.captureBalanceBefore(prev.userId(), prev.assetId());
                accountRollback.captureBalanceBefore(reservation.userId(), reservation.assetId());
            }
            if (prev != null) lane.replacePendingReservation(prev, reservation);
            lane.storeReservation(reservation.orderId(), reservation.laneValue());
            if (prev == null)
                lane.addReservationId(reservation.userId(), reservation.orderId());
            if (pending) {
                accountRollback.captureBalanceAfter(lane, prev.userId(), prev.assetId());
                accountRollback.captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
            }
            return prev;
        });
        publishReservation(reservation.orderId(), reservation);
        if (currentMatcherSettlementChangesScope() == null) {
            markReservationChanged(reservation.orderId());
            markUserChanged(reservation.userId());
            if (previous != null) markUserChanged(previous.userId());
        }
    }

    /**
     * 要求所属用户预留存在且待撮合已经完成，保存前值后删除预留与用户索引，再登记变化。
     * @param orderId 本次操作关联的订单实体 ID
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void removeReservation(long orderId, long userId) {
        assertOwner();
        onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureReservationBefore(orderId, userId);
            // 该用户的原预留，必须存在、归属一致且不再待撮合
            ReservationRuntime current = lane.reservation(orderId);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
            }
            if (lane.pendingReservation(orderId)) {
                throw new IllegalStateException("pending reservation must complete before removal");
            }
            lane.removeReservationValue(orderId);
            lane.removeReservationId(userId, orderId);
            return null;
        });
        publishReservation(orderId, null);
        markReservationChanged(orderId);
        markUserChanged(userId);
    }

    /**
     * 把余额对象的用户、资产及数值交给原地更新入口。
     * @param balance 本次需要保存或更新的资产余额值
     */
    public void replaceBalance(BalanceRuntime balance) {
        assertOwner();
        replaceBalance(balance.userId(), balance.assetId(), balance.availableUnits(), balance.lockedUnits());
    }

    /**
     * 在有效账户范围内保存前值并原地替换余额，跨 Lane 拒绝；没有范围时临时进入用户 Lane，退出后补记 Owner 变化。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param assetId 本产品线资产内部身份
     * @param availableUnits 新的可用资金整数数量
     * @param lockedUnits 新的冻结资金整数数量
     */
    public void replaceBalance(long userId, int assetId, long availableUnits, long lockedUnits) {
        assertOwner();
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null && scoped.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("balance write crossed its owner Lane");
        if (scoped != null) {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureBalanceBefore(userId, assetId);
            replaceBalanceInLane(scoped, userId, assetId, availableUnits, lockedUnits);
            return;
        }
        onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureBalanceBefore(userId, assetId);
            replaceBalanceInLane(lane, userId, assetId, availableUnits, lockedUnits);
            return null;
        });
        markBalancesChanged();
        markUserChanged(userId);
    }

    /**
     * 要求该资产余额已经存在，原地替换可用和冻结数量，再捕获余额后值。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param assetId 本产品线资产内部身份
     * @param availableUnits 新的可用资金整数数量
     * @param lockedUnits 新的冻结资金整数数量
     */
    private void replaceBalanceInLane(AccountLaneState lane, long userId, int assetId,
                                      long availableUnits, long lockedUnits) {
        // 本次所属用户的原资产余额映射，用于按预留资产取得余额
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        // 该资产必须已经存在的余额对象，直接原地替换数值
        BalanceRuntime current = balances == null ? null : balances.get(assetId);
        if (current == null) throw new IllegalArgumentException("runtime balance is not registered");
        current.replace(availableUnits, lockedUnits);
        accountRollback.captureBalanceAfter(lane, userId, assetId);
    }

    /**
     * 核对明确 Lane 与用户，撮合范围内只捕获本 Lane 补丁并原地修改，否则沿用普通更新。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param assetId 本产品线资产内部身份
     * @param available 新的可用资金整数数量
     * @param locked 新的冻结资金整数数量
     */
    public void replaceBalance(AccountLaneState lane, long userId, int assetId, long available, long locked) {
        requireLaneAccess(lane, userId);
        if (lane.matcherSettlementChanges() == null) {
            replaceBalance(userId, assetId, available, locked);
            return;
        }
        accountRollback.captureBalanceBefore(lane, userId, assetId);
        replaceBalanceInLane(lane, userId, assetId, available, locked);
    }

    /**
     * 在所属 Lane 创建或原地更新持仓；根据非零暴露及标的变化维护活动持仓索引，最后交出本次持仓值。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param positionKey 持仓内部身份键
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param symbolId 本产品线标的内部身份
     * @param assetId 本产品线资产内部身份
     * @param instrument 本产品线标的配置对象，已有对象更新要求保持注册身份
     * @param signedQuantitySteps 按数量步长计的有符号持仓量
     * @param entryPriceTicks 按价格刻度计的持仓入场价
     * @param entryValueTicks 持仓入场价值的整数刻度值
     * @param realizedPnlUnits 已实现盈亏的资金整数数量
     * @param positionMarginUnits 持仓保证金的资金整数数量
     * @param marginMode 订单、持仓或杠杆配置的保证金模式
     * @param positionSide 持仓方向，用于持仓及强平身份区分
     */
    public PositionRuntime updatePositionInLane(AccountLaneState lane, long positionKey, long userId, int symbolId, int assetId,
                                         CoreInstrument instrument, long signedQuantitySteps,
                                         long entryPriceTicks, long entryValueTicks,
                                         long realizedPnlUnits, long positionMarginUnits,
                                         CoreMarginMode marginMode, CorePositionSide positionSide) {
        lane.assertOwner();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("position update must execute in its owning Account Lane");
        }
        // 该内部持仓键的现有值，缺失则建立新持仓
        PositionRuntime current = lane.position(positionKey);
        if (current == null) {
            // 由本次参数构造的新持仓，随后保存 Lane 形态并建立索引
            PositionRuntime created = new PositionRuntime(userId, symbolId, assetId, marginMode, positionSide,
                    instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                    realizedPnlUnits, positionMarginUnits);
            lane.storePosition(positionKey, created.laneValue());
            indexPosition(lane, positionKey, created);
            publishPosition(lane, positionKey, created);
            return created;
        }
        // 修改前持仓数量是否非零，用于判断原活动暴露索引
        boolean wasOpen = current.signedQuantitySteps() != 0;
        // 原持仓内部标的身份，标的变化时需要迁移活动索引
        int oldSymbol = current.symbolId();
        current.applyInPlace(instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
        // 修改后持仓数量是否非零，与原暴露状态一起决定索引变化
        boolean isOpen = current.signedQuantitySteps() != 0;
        if (wasOpen && (!isOpen || oldSymbol != current.symbolId()))
            unindexOpenPosition(lane, positionKey, current.userId(), oldSymbol);
        if ((!wasOpen && isOpen) || oldSymbol != current.symbolId()) indexOpenPosition(lane, positionKey, current);
        publishPosition(lane, positionKey, current);
        return current;
    }

    /**
     * 在账户线程原地更新订单执行数量、费用、状态及提交元数据，写入成交价值高低位，再发布本次变化。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param orderId 本次操作关联的订单实体 ID
     * @param executed 新的累计已执行数量
     * @param remaining 新的剩余订单数量
     * @param feeUnits 新的累计手续费整数数量
     * @param executedValueHigh 累计成交价值宽整数的高位部分
     * @param executedValueLow 累计成交价值宽整数的低位部分
     * @param status 本次需要写入的订单状态
     * @param revision 要写入的用户或订单业务修订值
     * @param commitTimestamp 原命令提交业务时间，未指定时沿用负值哨兵约定
     * @param commitPosition 原命令复制日志位置，未指定时沿用负值哨兵约定
     */
    public OrderRuntime updateOrderInLane(AccountLaneState lane, long orderId, long executed, long remaining, long feeUnits,
                                   long executedValueHigh, long executedValueLow,
                                   CoreOrderStatus status, long revision,
                                   long commitTimestamp, long commitPosition) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("order update must execute in its owning Account Lane");
        }
        // 账户原地更新执行计数后返回的订单，再补写成交价值并发布
        OrderRuntime order = lane.updateOrderInPlace(orderId, executed, remaining, feeUnits,
                status, revision, commitTimestamp, commitPosition);
        order.executionValue(executedValueHigh, executedValueLow);
        publishOrder(lane, orderId, order);
        return order;
    }

    /**
     * 在账户线程原地更新订单状态、修订及提交元数据，再交出订单变化。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param orderId 本次操作关联的订单实体 ID
     * @param status 本次需要写入的订单状态
     * @param revision 要写入的用户或订单业务修订值
     * @param commitTimestamp 原命令提交业务时间，未指定时沿用负值哨兵约定
     * @param commitPosition 原命令复制日志位置，未指定时沿用负值哨兵约定
     */
    public OrderRuntime updateOrderStatusInLane(AccountLaneState lane, long orderId, CoreOrderStatus status, long revision,
                                         long commitTimestamp, long commitPosition) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("order status update must execute in its owning Account Lane");
        }
        // 账户更新状态和元数据后返回的订单，随后交出变化
        OrderRuntime order = lane.updateOrderStatusInPlace(orderId, status, revision,
                commitTimestamp, commitPosition);
        publishOrder(lane, orderId, order);
        return order;
    }

    /**
     * 要求预留存在且释放累计量不回退，原地更新消耗与释放，同步待预留数量差，按开关交出变化。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param orderId 本次操作关联的订单实体 ID
     * @param nextConsumed 预留新的累计消耗数量
     * @param nextReleased 预留新的累计释放数量，不得小于已有累计值
     * @param publish 是否把本次预留后值写入 Lane 变化容器
     */
    public ReservationRuntime updateReservationInLane(AccountLaneState lane, long orderId, long nextConsumed, long nextReleased,
                                               boolean publish) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("reservation update must execute in its owning Account Lane");
        }
        // 当前订单对应的原预留，释放与消耗修改需同时维护待预留差额
        ReservationRuntime reservation = lane.reservation(orderId);
        if (reservation == null) throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
        // 修改前的剩余冻结量，用来修正账户待预留数量
        long previousReserved = reservation.reservedUnits();
        if (nextReleased < reservation.releasedUnits())
            throw new IllegalArgumentException("reservation release moved backwards");
        reservation.setConsumedInPlace(nextConsumed);
        if (nextReleased != reservation.releasedUnits()) reservation.releaseInPlace(nextReleased - reservation.releasedUnits());
        lane.updatePendingReservationInPlace(reservation, previousReserved);
        if (publish) publishReservation(lane, orderId, reservation);
        return reservation;
    }

    /**
     * 保存持仓前值并禁止更换用户，在所属 Lane 替换实体及索引，随后按当前范围登记持仓与用户变化。
     * @param positionKey 持仓内部身份键
     * @param position 要替换的持仓状态，用户身份不得改变
     */
    public void replacePosition(long positionKey, PositionRuntime position) {
        assertOwner();
        accountRollback.captureUserBefore(position.userId());
        accountRollback.capturePositionBefore(positionKey, position.userId());
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        PositionRuntime previous = position(positionKey);
        if (previous != null && previous.userId() != position.userId()) {
            throw new IllegalArgumentException("runtime position owner cannot change");
        }
        onLane(position.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            lane.storePosition(positionKey, position.laneValue());
            replacePositionIndexes(lane, positionKey, previous, position);
            return null;
        });
        publishPosition(positionKey, position);
        if (currentLaneCommandScope() == null) {
            changedPosition(positionKey, position);
            markUserChanged(position.userId());
            if (previous != null) markUserChanged(previous.userId());
        }
    }

    /**
     * 要求强平身份尚未存在，在所属 Lane 保存强平及活动索引并交出变化，随后登记用户变化。
     * @param liquidation 本次保存、替换或活动索引处理的强平状态
     */
    public void putLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        accountRollback.captureUserBefore(liquidation.userId());
        if (liquidation(liquidation.liquidationId()) != null) {
            throw new IllegalArgumentException("runtime liquidation already exists: " + liquidation.liquidationId());
        }
        onLane(liquidation.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            lane.storeLiquidation(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (currentLaneCommandScope() == null) {
            changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        markUserChanged(liquidation.userId());
    }

    /**
     * 在允许的非批量范围内写入标记价，并记录标的变化身份。
     * @param markPrice 要保存的标记价格状态
     */
    public void putMarkPrice(MarkPriceRuntime markPrice) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("mark-price state");
        markPrices.put(markPrice.symbolId(), markPrice);
        changedMarkPrices.add(markPrice.symbolId());
    }

    /**
     * 保存用户及风险快照前值，在所属 Lane 写入并交出变化，登记相关用户。
     * @param positionKey 持仓内部身份键
     * @param snapshot 要写入账户的风险快照状态
     */
    public void putRiskSnapshot(long positionKey, RiskSnapshotRuntime snapshot) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk snapshot");
        accountRollback.captureUserBefore(snapshot.userId());
        accountRollback.captureRiskSnapshotBefore(positionKey, snapshot.userId());
        onLane(snapshot.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            lane.storeRiskSnapshot(positionKey, snapshot);
            publishRiskSnapshot(positionKey, snapshot);
            return null;
        });
        if (currentLaneCommandScope() == null) changedRiskSnapshots.put(positionKey, snapshot);
        markUserChanged(snapshot.userId());
    }

    /**
     * 在允许的非批量范围内保存标的风险扫描续步，并登记标的变化。
     * @param scan 要保存的风险扫描续步状态
     */
    public void putRiskScan(RiskScanRuntime scan) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan state");
        riskScans.put(scan.symbolId(), scan);
        changedRiskScans.add(scan.symbolId());
    }

    /**
     * 校验非负后保存调用方提供的行情修订。
     * @param value 必须非负的行情修订值
     */
    public void setMarketRevision(long value) {
        assertOwner();
        if (value < 0) throw new IllegalArgumentException("invalid market revision");
        marketRevision = value;
    }

    /**
     * 禁止订单批量交叉修改，校验正值后保存下一个强平身份。
     * @param nextLiquidationId 需要保存的正值下一强平身份
     */
    public void setNextLiquidationId(long nextLiquidationId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("liquidation sequence");
        if (nextLiquidationId <= 0) throw new IllegalArgumentException("invalid next liquidation id");
        this.nextLiquidationId = nextLiquidationId;
    }

    /**
     * 要求原强平存在，先移除旧活动索引，按新用户保存并重建索引；现有实现允许强平用户改变，必要时分别访问新旧用户 Lane。
     * @param liquidation 本次保存、替换或活动索引处理的强平状态
     */
    public void replaceLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        accountRollback.captureUserBefore(liquidation.userId());
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        LiquidationRuntime previous = liquidation(liquidation.liquidationId());
        if (previous == null) {
            throw new IllegalArgumentException("runtime liquidation is not registered: "
                    + liquidation.liquidationId());
        }
        if (previous.userId() != liquidation.userId()) {
            onLane(previous.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                removeActiveLiquidation(lane, previous);
                lane.removeLiquidationValue(previous.liquidationId());
                publishLiquidation(previous.liquidationId(), null);
                return null;
            });
        }
        onLane(liquidation.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            if (previous.userId() == liquidation.userId()) removeActiveLiquidation(lane, previous);
            lane.storeLiquidation(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (currentLaneCommandScope() == null) {
            changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        markUserChanged(liquidation.userId());
        markUserChanged(previous.userId());
    }

    /**
     * 校验用户持仓存在，保存前值后在所属 Lane 删除实体及持仓索引，并登记删除与用户变化。
     * @param positionKey 持仓内部身份键
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void removePosition(long positionKey, long userId) {
        assertOwner();
        onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.capturePositionBefore(positionKey, userId);
            // 待删除的原持仓，必须属于当前用户
            PositionRuntime current = lane.position(positionKey);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime position is not registered: " + positionKey);
            }
            lane.removePositionValue(positionKey);
            unindexPosition(lane, positionKey, current);
            return null;
        });
        publishPosition(positionKey, null);
        changedPosition(positionKey, null);
        markUserChanged(userId);
    }

    /**
     * 原强平存在时删除所属账户值和活动索引，再交出删除标记及用户变化。
     * @param liquidationId 要删除或交接的强平实体 ID
     */
    public void removeLiquidation(long liquidationId) {
        assertOwner();
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        LiquidationRuntime previous = liquidation(liquidationId);
        if (previous != null) {
            accountRollback.captureUserBefore(previous.userId());
            onLane(previous.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                lane.removeLiquidationValue(liquidationId);
                removeActiveLiquidation(lane, previous);
                publishLiquidation(liquidationId, null);
                return null;
            });
            if (currentLaneCommandScope() == null) changedLiquidations.put(liquidationId, null);
            markUserChanged(previous.userId());
        }
    }

    /**
     * 保存已有风险快照前值并在所属 Lane 删除，交接持仓是否也已缺失的索引信息；实体缺失时仍按当前范围登记删除或清身份保留。
     * @param positionKey 持仓内部身份键
     */
    public void removeRiskSnapshot(long positionKey) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk snapshot");
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        RiskSnapshotRuntime previous = riskSnapshot(positionKey);
        if (previous != null) {
            accountRollback.captureUserBefore(previous.userId());
            accountRollback.captureRiskSnapshotBefore(positionKey, previous.userId());
            // 删除风险快照后，该内部身份是否也已没有持仓，交给发布后身份退休处理
            boolean positionAbsent = onLane(previous.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                lane.removeRiskSnapshotValue(positionKey);
                publishRiskSnapshot(positionKey, null);
                return lane.position(positionKey) == null;
            });
            if (currentLaneCommandScope() == null)
                changedRiskSnapshots.putIndexed(positionKey, null, true, positionAbsent);
        } else if (currentLaneCommandScope() != null) {
            laneDelta(currentLaneCommandScope().laneId()).putRiskSnapshot(positionKey, null);
        } else {
            riskPositionIdentities.remove(positionKey);
        }
        if (currentLaneCommandScope() == null && previous == null) changedRiskSnapshots.put(positionKey, null);
        if (previous != null) markUserChanged(previous.userId());
    }

    /**
     * 使用未显式给定提交元数据的方式取消订单，原有负值哨兵交给统一终态处理。
     * @param orderId 本次操作关联的订单实体 ID
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param releaseUnits 必须与预留剩余冻结一致的本次释放数量
     */
    public void cancelOrder(long orderId, long userId, long releaseUnits) {
        cancelOrder(orderId, userId, releaseUnits, -1, -1);
    }

    /**
     * 把取消状态和原提交元数据交给统一冻结释放入口。
     * @param orderId 本次操作关联的订单实体 ID
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param releaseUnits 必须与预留剩余冻结一致的本次释放数量
     * @param commitTimestamp 原命令提交业务时间，未指定时沿用负值哨兵约定
     * @param commitPosition 原命令复制日志位置，未指定时沿用负值哨兵约定
     */
    void cancelOrder(long orderId, long userId, long releaseUnits,
                             long commitTimestamp, long commitPosition) {
        releaseOrderToTerminal(orderId, userId, releaseUnits, commitTimestamp, commitPosition, CoreOrderStatus.CANCELED);
    }

    /**
     * 为普通终态处理补上空的已准备订单和预留，交给统一实现。
     * @param orderId 本次操作关联的订单实体 ID
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param releaseUnits 必须与预留剩余冻结一致的本次释放数量
     * @param commitTimestamp 原命令提交业务时间，未指定时沿用负值哨兵约定
     * @param commitPosition 原命令复制日志位置，未指定时沿用负值哨兵约定
     * @param terminalStatus 本次冻结释放后需要写入的订单终态
     */
    private void releaseOrderToTerminal(long orderId, long userId, long releaseUnits,
                             long commitTimestamp, long commitPosition, CoreOrderStatus terminalStatus) {
        releaseOrderToTerminal(orderId, userId, releaseUnits, commitTimestamp, commitPosition, terminalStatus,
                null, null);
    }

    /**
     * 保存用户、订单与预留前值，在所属账户完成精确冻结释放及终态更新，再交出变化并推进用户修订。已准备实体由原校验边界保证有效。
     * @param orderId 本次操作关联的订单实体 ID
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param releaseUnits 必须与预留剩余冻结一致的本次释放数量
     * @param commitTimestamp 原命令提交业务时间，未指定时沿用负值哨兵约定
     * @param commitPosition 原命令复制日志位置，未指定时沿用负值哨兵约定
     * @param terminalStatus 本次冻结释放后需要写入的订单终态
     * @param preparedOrder 可空的已在同一 Lane 校验过的订单引用
     * @param preparedReservation 可空的已在同一 Lane 校验过的预留引用
     */
    private void releaseOrderToTerminal(long orderId, long userId, long releaseUnits,
                             long commitTimestamp, long commitPosition, CoreOrderStatus terminalStatus,
                             OrderRuntime preparedOrder, ReservationRuntime preparedReservation) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        accountRollback.captureOrderBefore(orderId);
        accountRollback.captureReservationBefore(orderId);
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        // 所属 Lane 已完成冻结释放和终态更新后交出的订单与预留结果
        CanceledOrder canceled = scoped == null
                ? onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> releaseOrderToTerminalInLane(lane, orderId, userId, releaseUnits,
                        commitTimestamp, commitPosition, terminalStatus, preparedOrder, preparedReservation))
                : releaseOrderToTerminalInLane(scoped, orderId, userId, releaseUnits,
                        commitTimestamp, commitPosition, terminalStatus, preparedOrder, preparedReservation);
        publishOrder(orderId, canceled.order());
        publishReservation(orderId, canceled.reservation());
        if (currentMatcherSettlementChangesScope() == null) {
            changedOrder(orderId, canceled.order());
            markReservationChanged(orderId);
            markUserChanged(userId);
            markBalancesChanged();
        }
        advanceUserRevision(userId);
    }

    /**
     * 核对订单所属用户，要求订单与预留存在、订单尚未终态，且释放量等于剩余冻结。释放后更新终态；撮合范围内原地修改，其他路径建立更新值并维护待预留差额，最后交出订单与预留。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param orderId 本次操作关联的订单实体 ID
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param releaseUnits 必须与预留剩余冻结一致的本次释放数量
     * @param commitTimestamp 原命令提交业务时间，未指定时沿用负值哨兵约定
     * @param commitPosition 原命令复制日志位置，未指定时沿用负值哨兵约定
     * @param terminalStatus 本次冻结释放后需要写入的订单终态
     * @param preparedOrder 可空的已在同一 Lane 校验过的订单引用
     * @param preparedReservation 可空的已在同一 Lane 校验过的预留引用
     */
    private CanceledOrder releaseOrderToTerminalInLane(
            AccountLaneState lane, long orderId, long userId, long releaseUnits,
            long commitTimestamp, long commitPosition, CoreOrderStatus terminalStatus,
            OrderRuntime preparedOrder, ReservationRuntime preparedReservation) {
        if (lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("terminal order release crossed its owner Lane");
        }
        // 当前处理的原订单或更新后订单，使用范围由本分支的账户所有权限定
        OrderRuntime order = preparedOrder == null ? lane.order(orderId) : preparedOrder;
        // 当前订单对应的原预留，释放与消耗修改需同时维护待预留差额
        ReservationRuntime reservation = preparedReservation == null
                ? lane.reservation(orderId) : preparedReservation;
        if (order == null || reservation == null || order.userId() != userId || order.canceled()) {
            throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
        }
        if (releaseUnits != reservation.reservedUnits()) {
            throw new IllegalArgumentException("runtime cancellation release mismatch: " + orderId);
        }
        // 本次所属用户的原资产余额映射，用于按预留资产取得余额
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        // 按本订单或预留资产取到的余额，必须存在才能冻结或释放
        BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
        if (balance == null) {
            throw new IllegalArgumentException("runtime cancellation balance is missing: " + orderId);
        }
        accountRollback.captureBalanceBefore(userId, reservation.assetId());
        balance.release(releaseUnits);
        if (currentMatcherSettlementChangesScope() != null && currentLaneCommandScope() == lane) {
            // 修改前的剩余冻结量，用来修正账户待预留数量
            long previousReserved = reservation.reservedUnits();
            reservation.releaseInPlace(releaseUnits);
            lane.updatePendingReservationInPlace(reservation, previousReserved);
            // 增加订单修订并写入指定终态及原提交元数据后的订单
            OrderRuntime terminalOrder = lane.updateOrderStatusInPlace(orderId, terminalStatus,
                    Math.incrementExact(order.revision()), commitTimestamp, commitPosition);
            accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
            return new CanceledOrder(terminalOrder, reservation);
        }
        // 增加订单修订并写入指定终态及原提交元数据后的订单
        OrderRuntime terminalOrder = order.withStatus(terminalStatus,
                Math.incrementExact(order.revision()), commitTimestamp, commitPosition);
        // 释放本次冻结数量后的预留值，随后替换原账户预留
        ReservationRuntime released = reservation.release(releaseUnits);
        lane.replacePendingReservation(reservation, released);
        lane.putOrder(terminalOrder);
        lane.storeReservation(orderId, released.laneValue());
        accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
        return new CanceledOrder(terminalOrder, released);
    }

    /**
     * 使用原负值元数据哨兵，在明确撮合 Lane 中取消订单。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orderId 本次操作关联的订单实体 ID
     */
    public void cancelOrderInLane(AccountLaneState lane, long userId, long orderId) {
        cancelOrderInLane(lane, userId, orderId, -1, -1);
    }

    /**
     * 在明确撮合 Lane 中释放剩余冻结并把订单改为 CANCELED。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orderId 本次操作关联的订单实体 ID
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     */
    public void cancelOrderInLane(AccountLaneState lane, long userId, long orderId,
                           long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, orderId, timestamp, position, CoreOrderStatus.CANCELED);
    }

    /**
     * 在明确撮合 Lane 中释放剩余冻结并把订单改为 REJECTED。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orderId 本次操作关联的订单实体 ID
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     */
    public void rejectOrderInLane(AccountLaneState lane, long userId, long orderId,
                           long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, orderId, timestamp, position, CoreOrderStatus.REJECTED);
    }

    /**
     * 要求有效撮合范围及尚未终态的订单，释放全部剩余冻结，原地更新预留与订单并发布前后变化，最后累计用户修订。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orderId 本次操作关联的订单实体 ID
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     * @param status 本次需要写入的订单状态
     */
    private void releaseOrderToTerminalInLane(AccountLaneState lane, long userId, long orderId,
            long timestamp, long position, CoreOrderStatus status) {
        requireLaneAccess(lane, userId);
        if (lane.matcherSettlementChanges() == null)
            throw new IllegalStateException("terminal order requires its settlement Lane");
        // 当前处理的原订单或更新后订单，使用范围由本分支的账户所有权限定
        OrderRuntime order = lane.order(orderId);
        // 当前订单对应的原预留，释放与消耗修改需同时维护待预留差额
        ReservationRuntime reservation = lane.reservation(orderId);
        if (order == null || reservation == null || order.userId() != userId || order.status().terminal())
            throw new IllegalStateException("terminal order reservation is missing: " + orderId);
        // 按本订单或预留资产取到的余额，必须存在才能冻结或释放
        BalanceRuntime balance = balance(lane, userId, reservation.assetId());
        if (balance == null) throw new IllegalStateException("terminal order balance is missing: " + orderId);
        // 该预留当前全部剩余冻结量，零时不再产生余额释放
        long releaseUnits = reservation.reservedUnits();
        accountRollback.captureBalanceBefore(lane, userId, reservation.assetId());
        balance.release(releaseUnits);
        reservation.releaseInPlace(releaseUnits);
        lane.updatePendingReservationInPlace(reservation, releaseUnits);
        lane.updateOrderStatusInPlace(orderId, status, Math.incrementExact(order.revision()), timestamp, position);
        publishOrder(lane, orderId, order);
        publishReservation(lane, orderId, reservation);
        accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
        advanceUserRevision(lane, userId);
    }

    /**
     * 在明确撮合 Lane 内释放已经终态订单仍保留的冻结，原地更新预留及余额补丁；没有剩余冻结返回零。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param orderId 本次操作关联的订单实体 ID
     */
    public long releaseTerminalReservationInLane(AccountLaneState lane, long orderId) {
        lane.assertOwner();
        if (lane == null || lane.matcherSettlementChanges() == null) {
            throw new IllegalStateException("terminal release must execute in its owning Account Lane");
        }
        // 当前处理的原订单或更新后订单，使用范围由本分支的账户所有权限定
        OrderRuntime order = lane.order(orderId);
        // 当前订单对应的原预留，释放与消耗修改需同时维护待预留差额
        ReservationRuntime reservation = lane.reservation(orderId);
        if (order == null || reservation == null || !order.canceled()) {
            throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
        }
        // 该预留当前全部剩余冻结量，零时不再产生余额释放
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits == 0) return 0;
        // 本次所属用户的原资产余额映射，用于按预留资产取得余额
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(order.userId());
        // 按本订单或预留资产取到的余额，必须存在才能冻结或释放
        BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
        if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + orderId);
        accountRollback.captureBalanceBefore(lane, order.userId(), reservation.assetId());
        balance.release(releaseUnits);
        // 修改前的剩余冻结量，用来修正账户待预留数量
        long previousReserved = reservation.reservedUnits();
        reservation.releaseInPlace(releaseUnits);
        lane.updatePendingReservationInPlace(reservation, previousReserved);
        publishReservation(lane, orderId, reservation);
        accountRollback.captureBalanceAfter(lane, order.userId(), reservation.assetId());
        return releaseUnits;
    }

    /**
     * 通过所属账户释放终态订单剩余冻结，保存回滚前后值；确有释放且没有外层账户范围时登记 Owner 变化。
     * @param orderId 本次操作关联的订单实体 ID
     */
    public void releaseTerminalReservation(long orderId) {
        assertOwner();
        accountRollback.captureReservationBefore(orderId);
        // 当前处理的原订单或更新后订单，使用范围由本分支的账户所有权限定
        OrderRuntime order = order(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
        accountRollback.captureUserBefore(order.userId());
        // 所属账户只返回是否发生释放，金额和资产仍在 Lane 内处理
        boolean releasedFunds = onLane(order.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            // 当前订单对应的原预留，释放与消耗修改需同时维护待预留差额
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation == null || !order.canceled()) {
                throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
            }
            // 该预留当前全部剩余冻结量，零时不再产生余额释放
            long releaseUnits = reservation.reservedUnits();
            if (releaseUnits == 0) return false;
            // 本次所属用户的原资产余额映射，用于按预留资产取得余额
            IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(order.userId());
            // 按本订单或预留资产取到的余额，必须存在才能冻结或释放
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + orderId);
            accountRollback.captureBalanceBefore(order.userId(), reservation.assetId());
            balance.release(releaseUnits);
            // 释放本次冻结数量后的预留值，随后替换原账户预留
            ReservationRuntime released = reservation.release(releaseUnits);
            lane.replacePendingReservation(reservation, released);
            lane.storeReservation(orderId, released.laneValue());
            publishReservation(orderId, released);
            accountRollback.captureBalanceAfter(lane, order.userId(), reservation.assetId());
            return true;
        });
        if (!releasedFunds) return;
        if (currentLaneCommandScope() == null) {
            markReservationChanged(orderId);
            markUserChanged(order.userId());
            markBalancesChanged();
        }
    }

    /**
     * 保存用户及客户号索引前值，在所属 Lane 更新客户号与订单的双向索引，并标记用户变化。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientKey 客户订单号内部键，零表示没有客户号
     * @param orderId 本次操作关联的订单实体 ID
     */
    public void putClientOrder(long userId, long clientKey, long orderId) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        accountRollback.captureClientOrderBefore(userId, clientKey);
        onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            putClientOrderIndex(lane, userId, clientKey, orderId);
            return null;
        });
        markUserChanged(userId);
    }

    /**
     * 保存索引前值，在所属 Lane 删除客户号及反向关系，再登记用户变化。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientKey 客户订单号内部键，零表示没有客户号
     */
    public void removeClientOrder(long userId, long clientKey) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        accountRollback.captureClientOrderBefore(userId, clientKey);
        onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            removeClientOrderIndex(lane, userId, clientKey);
            return null;
        });
        markUserChanged(userId);
    }

    /**
     * 冷阶段内联捕获并返回完成或失败的 future；活动阶段向所属账户派发读取，邮箱拒绝及读取运行时异常完成为失败结果。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public java.util.concurrent.CompletableFuture<RealtimeUserSnapshot> realtimeSnapshot(long userId) {
        assertOwner();
        // 本轮正在处理或按用户路由选出的账户 Lane 编号
        int laneId = topology.accountLaneId(userId);
        if (!accountLanesStarted()) {
            try { return java.util.concurrent.CompletableFuture.completedFuture(onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> RealtimeUserSnapshot.capture(lane, userId))); }
            catch (/* 当前实时读取或捕获发生的运行时异常，由所在入口完成失败结果或记录捕获失败 */ RuntimeException failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
        }
        // 本次快照返回的 future，账户读取或邮箱拒绝负责完成它
        var result = new java.util.concurrent.CompletableFuture<RealtimeUserSnapshot>();
        // 账户查询邮箱是否接纳本次快照读取，拒绝则把 future 完成为失败
        boolean accepted = dispatchAccountQuery(1L << laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            try { result.complete(readAccountLane(lane, /* 已经由读取入口核对所属线程的账户 Lane，在该范围捕获用户快照 */ owned -> RealtimeUserSnapshot.capture(owned, userId))); }
            catch (/* 当前实时读取或捕获发生的运行时异常，由所在入口完成失败结果或记录捕获失败 */ RuntimeException failure) { result.completeExceptionally(failure); }
        });
        if (!accepted) result.completeExceptionally(new IllegalStateException("realtime snapshot Lane mailbox is full"));
        return result;
    }

    /**  可空实时变化捕获器，沿用已提交变化或明确快照边界，不替代账户权威状态。 返回当前可空实时捕获器引用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    RealtimeStateCapture realtimeCapture;

    /**
     * 接入可空实时捕获器，不在此启动输出线程。
     * @param capture 接入或使用的可空实时变化捕获器
     */
    public void realtimeCapture(RealtimeStateCapture capture) { assertOwner(); realtimeCapture = capture; }

    /**
     * 实时捕获器存在时记录用户元数据，捕获运行时异常只记失败。
     * @param value 需要捕获的用户元数据值
     */
    public void captureUserMetadata(com.surprising.aeron.service.account.UserRuntime value) {
        if (realtimeCapture == null) return;
        try { realtimeCapture.metadata(value); }
        catch (/* 当前实时读取或捕获发生的运行时异常，由所在入口完成失败结果或记录捕获失败 */ RuntimeException failure) { realtimeCapture.failed(); }
    }
    /**
     * 实时捕获器存在时记录预留变化，捕获运行时异常只记失败。
     * @param value 需要捕获的预留变化值
     */
    public void captureReservation(ReservationRuntime value) {
        if (realtimeCapture == null) return;
        try { realtimeCapture.reservation(value); }
        catch (/* 当前实时读取或捕获发生的运行时异常，由所在入口完成失败结果或记录捕获失败 */ RuntimeException failure) { realtimeCapture.failed(); }
    }

    /**
     * 启用私有捕获时导出本命令风险、订单和持仓变化；仅纯冷执行另导出用户与预留，活动或 FUSED 已由 Lane 交接负责。捕获异常只记失败。
     * @param capture 接入或使用的可空实时变化捕获器
     */
    public void captureRealtimeChanges(RealtimeStateCapture capture) {
        assertOwner();
        if (capture == null || !capture.privateStateEnabled()) return;
        try {
            changedRiskSnapshots.forEach((/* 本轮正在恢复、读取或捕获的业务实体身份 */ id,/* 本次风险、订单或持仓变化后值，空持仓按补丁前值捕获删除 */ value) -> capture.risk(value));
            // 活动执行通过已提交 Lane 变化交接用户和预留，Owner 内联执行也沿用该路径。
            // 纯冷执行没有这个交接边界，才在这里另导出用户与预留变化集合。
            if (!accountLanesStarted() && !fusedExecutionActive) {
                changedUsers.forEach(/* 本轮正在恢复、读取或捕获的业务实体身份 */ id -> capture.metadata(user(id)));
                changedReservations.forEach(/* 本轮正在恢复、读取或捕获的业务实体身份 */ id -> capture.reservation(reservation(id)));
            }
            changedOrders.forEach((/* 当前客户号、订单或预留对应的订单实体 ID */ orderId, /* 本次风险、订单或持仓变化后值，空持仓按补丁前值捕获删除 */ value) -> capture.order(value));
            changedPositions.forEach((/* 本次变化或退休检查涉及的持仓内部键 */ positionKey, /* 本次风险、订单或持仓变化后值，空持仓按补丁前值捕获删除 */ value) -> {
                if(value==null)capture.removedPosition(currentPatchPositionBefore(positionKey));else capture.position(value);
            });
        } catch (/* 当前实时读取或捕获发生的运行时异常，由所在入口完成失败结果或记录捕获失败 */ RuntimeException failure) { capture.failed(); }
    }

    /**
     * 复制当前命令变化用户集合，避免调用方修改原缓冲。
     */
    public LongHashSet changedUsers() {
        assertOwner();
        return new LongHashSet(changedUsers);
    }

    /**
     * 校验接收函数后直接遍历原始变化用户身份，不先创建集合副本。
     * @param consumer 接收本次变化身份或索引值的回调
     */
    public void acceptChangedUserIds(java.util.function.LongConsumer consumer) {
        assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed user consumer is required");
        changedUsers.forEach(consumer::accept);
    }

    /**
     * 标记本命令涉及余额及用户变化；具体资产明细仍由余额补丁持有。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void markBalanceChanged(long userId) {
        assertOwner();
        markBalancesChanged();
        markUserChanged(userId);
    }

    /**
     * 按当前范围把预留身份交给 Owner 集合或所属 Lane 的变化容器。
     * @param orderId 本次操作关联的订单实体 ID
     */
    private void markReservationChanged(long orderId) {
        // 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) changedReservations.add(orderId);
        else laneDelta(lane.laneId()).putReservation(orderId, lane.reservation(orderId));
    }

    /**
     * 活动账户没有当前范围时要求已有收集收据，避免退回账户读取；其他路径读取本次值后交给变化缓冲。
     * @param orderId 本次操作关联的订单实体 ID
     */
    public void changedOrder(long orderId) {
        if (currentLaneCommandScope() == null && accountLanesStarted()) {
            if (!changedOrders.containsKey(orderId))
                throw new IllegalStateException("order change requires a collected Lane receipt: " + orderId);
            return;
        }
        changedOrder(orderId, order(orderId));
    }

    /**
     * 按当前执行范围登记订单值或删除标记，不在此再次修改账户。
     * @param orderId 本次操作关联的订单实体 ID
     * @param order 本次安装、更新或交接的订单值
     */
    public void changedOrder(long orderId, OrderRuntime order) {
        // 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) changedOrders.put(orderId, order);
        else laneDelta(lane.laneId()).putOrder(orderId, order);
    }

    /**
     * 活动账户没有当前范围时要求已有持仓收据；其他路径取得本次持仓值后登记变化。
     * @param positionKey 持仓内部身份键
     */
    void changedPosition(long positionKey) {
        if (currentLaneCommandScope() == null && accountLanesStarted()) {
            if (!changedPositions.containsKey(positionKey))
                throw new IllegalStateException("position change requires a collected Lane receipt: " + positionKey);
            return;
        }
        changedPosition(positionKey, position(positionKey));
    }

    /**
     * 把持仓变化或空删除值交给当前 Lane 容器或 Owner 缓冲。
     * @param positionKey 持仓内部身份键
     * @param position 本次持仓变化后值，空值表示删除
     */
    void changedPosition(long positionKey, PositionRuntime position) {
        // 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) changedPositions.put(positionKey, position);
        else laneDelta(lane.laneId()).putPosition(positionKey, position);
    }

    /**
     * 从当前订单变化缓冲复制身份集合，空删除值对应的身份也保留。
     */
    public LongHashSet changedOrders() {
        assertOwner();
        // 本次要返回给调用方的订单变化身份副本，删除项同样保留
        LongHashSet keys = new LongHashSet();
        changedOrders.forEach((/* 当前客户号、订单或预留对应的订单实体 ID */ orderId, /* 遍历变化键时不需要的业务值，删除项身份同样保留 */ ignored) -> keys.add(orderId));
        return keys;
    }

    /**
     * 复制当前变化预留身份集合。
     */
    public LongHashSet changedReservations() {
        assertOwner();
        return new LongHashSet(changedReservations);
    }

    /**
     * 借出当前持仓变化键的原始迭代器，消费期间保留变化缓冲有效。
     */
    public org.eclipse.collections.api.iterator.LongIterator changedPositionIterator() {
        assertOwner();
        return changedPositions.longIterator();
    }

    /**
     * 判断本命令持仓变化缓冲是否非空。
     */
    public boolean hasChangedPositions() {
        assertOwner();
        return !changedPositions.isEmpty();
    }

    /**
     * 本命令变化的索引接收契约，各默认方法为空；调用方只覆盖需要的业务类型，当前访问器通常将前值传空。
     */
    public interface ChangedIndexConsumer {
        /**
         * 接收订单身份及前后值，默认不处理；当前访问入口通常把 before 传空。
         * @param orderId 本次操作关联的订单实体 ID
         * @param before 变化前值参数；当前索引访问器通常传空，由接收器沿用既有索引处理
         * @param after 本次变化后值，空值保留删除含义
         */
        default void order(long orderId, OrderRuntime before, OrderRuntime after) {}
        /**
         * 接收持仓变化，默认不处理；已准备索引值可由专门路径直接应用。
         * @param positionKey 持仓内部身份键
         * @param before 变化前值参数；当前索引访问器通常传空，由接收器沿用既有索引处理
         * @param after 本次变化后值，空值保留删除含义
         */
        default void position(long positionKey, PositionRuntime before, PositionRuntime after) {}
        /**
         * 接收强平变化或删除，默认不处理。
         * @param liquidationId 要删除或交接的强平实体 ID
         * @param before 变化前值参数；当前索引访问器通常传空，由接收器沿用既有索引处理
         * @param after 本次变化后值，空值保留删除含义
         */
        default void liquidation(long liquidationId, LiquidationRuntime before, LiquidationRuntime after) {}
        /**
         * 接收算法单变化或删除，默认不处理。
         * @param algoOrderId 算法单实体 ID
         * @param before 变化前值参数；当前索引访问器通常传空，由接收器沿用既有索引处理
         * @param after 本次变化后值，空值保留删除含义
         */
        default void algoOrder(long algoOrderId,
                               com.surprising.aeron.model.CoreAlgoOrderState before,
                               com.surprising.aeron.model.CoreAlgoOrderState after) {}
        /**
         * 接收触发单变化或删除，默认不处理。
         * @param triggerOrderId 触发单实体 ID
         * @param before 变化前值参数；当前索引访问器通常传空，由接收器沿用既有索引处理
         * @param after 本次变化后值，空值保留删除含义
         */
        default void triggerOrder(long triggerOrderId,
                                  com.surprising.aeron.model.CoreTriggerOrderState before,
                                  com.surprising.aeron.model.CoreTriggerOrderState after) {}
        /**
         * 接收定时全撤业务键及变化值，默认不处理。
         * @param key 本次查询或修改使用的业务键
         * @param before 变化前值参数；当前索引访问器通常传空，由接收器沿用既有索引处理
         * @param after 本次变化后值，空值保留删除含义
         */
        default void timer(com.surprising.aeron.model.CoreCancelAllAfterKey key,
                           com.surprising.aeron.model.CoreCancelAllAfterState before,
                           com.surprising.aeron.model.CoreCancelAllAfterState after) {}
    }

    /**
     * 遍历直接及保留 Lane 变化缓冲，向索引接收器交出本次变化。活动账本已负责持仓业务索引时跳过其重复应用；强平/触发单捕获故障单独记录，索引回调异常仍传播。
     * @param consumer 接收本次变化身份或索引值的回调
     */
    public void visitChangedIndexes(ChangedIndexConsumer consumer) {
        assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed-index consumer is required");
        // 接收器恰为事实索引时复用其已准备值入口，否则为空
        RuntimeFactIndexes preparedConsumer = consumer instanceof RuntimeFactIndexes /* 已匹配为事实索引的接收器，避免再从业务实体重算持仓索引 */ indexes ? indexes : null;
        visitChangedOrderIndexes(consumer, changedOrders.directBuffer());
        // 还未遍历的保留 Lane 变化缓冲位集合
        long remaining = changedOrders.retainedLaneMask();
        while (remaining != 0) {
            // 本轮正在处理或按用户路由选出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            visitChangedOrderIndexes(consumer, changedOrders.laneBuffer(laneId));
        }
        if (laneLifecycle.ledger() == null || preparedConsumer == null) {
            visitChangedPositionIndexes(consumer, preparedConsumer, changedPositions.directBuffer());
            remaining = changedPositions.retainedLaneMask();
            while (remaining != 0) {
                // 本轮正在处理或按用户路由选出的账户 Lane 编号
                int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
                visitChangedPositionIndexes(consumer, preparedConsumer, changedPositions.laneBuffer(laneId));
            }
        }
        for (/* 本轮有效强平后值，Lane 已完成交接。 */ int index = 0; index < changedLiquidations.size(); index++) {
            LiquidationRuntime value = changedLiquidations.valueAt(index);
            if (realtimeCapture != null && realtimeCapture.active()) {
                try { realtimeCapture.liquidation(value); }
                catch (RuntimeException failure) { realtimeCapture.failed(); }
            }
            consumer.liquidation(changedLiquidations.keyAt(index), null, value);
        }
        for (/* 本轮有效变化项、原始列表或余额补丁的遍历下标 */ int index = 0; index < changedAlgoOrders.size(); index++)
            consumer.algoOrder(changedAlgoOrders.keyAt(index), null, changedAlgoOrders.valueAt(index));
        for (/* 本轮有效变化项、原始列表或余额补丁的遍历下标 */ int index = 0; index < changedTriggerOrders.size(); index++) {
            // 当前触发单变化身份，空后值也交给接收器表示删除
            long triggerOrderId = changedTriggerOrders.keyAt(index);
            // 当前触发单变化后值，先按需捕获实时值再交给索引接收器
            CoreTriggerOrderState value = changedTriggerOrders.valueAt(index);
            if (realtimeCapture != null && realtimeCapture.active()) {
                try { realtimeCapture.trigger(value); } catch (/* 当前实时读取或捕获发生的运行时异常，由所在入口完成失败结果或记录捕获失败 */ RuntimeException failure) { realtimeCapture.failed(); }
            }
            consumer.triggerOrder(triggerOrderId, null, value);
        }
        if (!changedCancelAllAfterTimers.isEmpty())
            for (var /* 当前变化的定时全撤业务键，后值从原定时器表读取 */ key : changedCancelAllAfterTimers) consumer.timer(key, null, cancelAllAfterTimer(key));
    }

    /**
     * 只遍历本订单变化缓冲的有效项，前值传空，空后值保留删除含义。
     * @param consumer 接收本次变化身份或索引值的回调
     * @param orders 本轮有效订单变化缓冲，包含空删除值
     */
    private void visitChangedOrderIndexes(ChangedIndexConsumer consumer, RuntimeChangeBuffer<OrderRuntime> orders) {
        for (/* 本轮有效变化项、原始列表或余额补丁的遍历下标 */ int index = 0; index < orders.size(); index++)
            consumer.order(orders.keyAt(index), null, orders.valueAt(index));
    }

    /**
     * 有已准备持仓索引值且接收器为事实索引时直接应用，否则交出持仓值；只遍历当前有效项。
     * @param consumer 接收本次变化身份或索引值的回调
     * @param preparedConsumer 接收器恰为事实索引时使用的可空专门入口
     * @param positions 当前持仓变化缓冲，包含删除项或已准备索引值
     */
    private void visitChangedPositionIndexes(ChangedIndexConsumer consumer, RuntimeFactIndexes preparedConsumer,
            RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> positions) {
        for (/* 本轮有效变化项、原始列表或余额补丁的遍历下标 */ int index = 0; index < positions.size(); index++) {
            // 当前持仓变化项的内部键，配套交出已准备索引或业务值
            long key = positions.keyAt(index);
            if (positions.hasPreparedAt(index) && preparedConsumer != null)
                preparedConsumer.preparedPosition(key, positions.preparedAt(index));
            else consumer.position(key, null, positions.valueAt(index));
        }
    }

    /**
     * 日志发布后先处理风险与持仓身份退休，再清变化容器并执行普通提交清理；仍被风险记录持有的身份继续保留。
     * @param identities 与本产品线配套的内部身份注册表
     */
    public void clearCommittedChanges(RuntimeIdentityRegistry identities) {
        assertOwner();
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        // 风险先清理，以已有持仓变更排除本次随后重开的持仓；不回读Lane表。
        for (/* 本轮有效变化项、原始列表或余额补丁的遍历下标 */ int index = 0; index < changedRiskSnapshots.size(); index++) {
            // 本次变化或退休检查涉及的持仓内部键
            long positionKey = changedRiskSnapshots.keyAt(index);
            if (changedRiskSnapshots.valueAt(index) == null && changedRiskSnapshots.hasPreparedAt(index)
                    && Boolean.TRUE.equals(changedRiskSnapshots.preparedAt(index))
                    && changedPositions.get(positionKey) == null)
                releaseRetiredPositionIdentity(identities, positionKey);
        }
        changedRiskSnapshots.clear();
        releaseRetiredPositionIdentities(identities, changedPositions.directBuffer());
        // 还未遍历的保留 Lane 变化缓冲位集合
        long remaining = changedPositions.retainedLaneMask();
        while (remaining != 0) {
            // 本轮正在处理或按用户路由选出的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            releaseRetiredPositionIdentities(identities, changedPositions.laneBuffer(laneId));
        }
        changedPositions.clear();
        clearChangedKeys();
    }

    /**
     * 遍历持仓删除项，尝试释放其内部身份；不为仍存在的持仓释放。
     * @param identities 与本产品线配套的内部身份注册表
     * @param positions 当前持仓变化缓冲，包含删除项或已准备索引值
     */
    private void releaseRetiredPositionIdentities(RuntimeIdentityRegistry identities,
            RuntimeChangeBuffer<PositionRuntime> positions) {
        for (/* 本轮有效变化项、原始列表或余额补丁的遍历下标 */ int index = 0; index < positions.size(); index++)
            if (positions.valueAt(index) == null) releaseRetiredPositionIdentity(identities, positions.keyAt(index));
    }

    /**
     * 只有风险记录不再持有该键时才请求身份表释放持仓身份。
     * @param identities 与本产品线配套的内部身份注册表
     * @param positionKey 持仓内部身份键
     */
    void releaseRetiredPositionIdentity(RuntimeIdentityRegistry identities, long positionKey) {
        if (!riskPositionIdentities.contains(positionKey)) {
            identities.releasePositionKey(positionKey);
        }
    }

    /**
     * 衍生品直接结算在当前 Lane 输出中只保留一次持仓身份，核对保留结果未换键，再登记当前持仓值以便发布后归还。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param identities 与本产品线配套的内部身份注册表
     * @param instrument 本产品线标的配置对象，已有对象更新要求保持注册身份
     * @param order 本次安装、更新或交接的订单值
     */
    public void retainDirectPositionIdentity(AccountLaneState lane, RuntimeIdentityRegistry identities,
                                      CoreInstrument instrument, OrderRuntime order) {
        if (!productLine.isDerivative()) return;
        // 当前 Lane 撮合变化容器，要求支持直接持仓身份保留
        MatcherSettlementChanges changes = lane.matcherSettlementChanges();
        if (changes == null || !changes.directPositionIdentities() || (!lane.commandScopeActive() && ownerLaneScope != lane))
            throw new IllegalStateException("direct position identity requires its settlement Lane");
        // 原订单已经固定的持仓内部身份，同一 Lane 输出只保留一次
        long key = order.positionIdentityKey();
        // 本 Lane 已有的持仓输出缓冲，以键索引保证同一身份只保留一次
        var output = changes.laneDelta(lane.laneId()).positions();
        if (output.indexOf(key) >= 0) return;
        // 身份表实际保留后返回的内部键，必须与原订单键一致
        long retained = identities.retainPositionInLane(
                lane, key, order.userId(), instrument.instrumentId(), order.positionSide());
        if (retained != key) throw new IllegalStateException("position identity changed during retention");
        output.put(key, lane.position(key));
    }

    /**
     * 用当前修订精确减去待完成预留数，得到当前可提交修订；不在此推进任何水位。
     */
    public long committedRevision() {
        assertOwner();
        return Math.subtractExact(revision, pendingReservations.totalPendingReservations());
    }

    /** 仅冷执行使用的本命令资金累加器，活动账本存在时不由 Owner 再维护同一资金账。 */
    private final RuntimeFundsAccumulator coldFunds = new RuntimeFundsAccumulator();
    /**
     * 没有活动账本时借出冷阶段资金累加器，否则返回空。
     */
    public RuntimeFundsAccumulator coldCommandFunds() { return laneLifecycle.ledger() == null ? coldFunds : null; }

    /** 无专用财务控制槽时复用的账本资金及持仓索引校验条目。 */
    private final ControlFundsValidation controlFundsValidation = new ControlFundsValidation();

    /**
     * 活动财务控制已有专用槽时沿用其校验；否则仅在账户余额或持仓有变化时推进独立资金校验续步。返回通过表示已验证，财务范围关闭仍需后续请求。
     * @param externalAdjustment 本命令是否允许外部资金增减，交给账本资金守恒检查
     */
    public boolean pollControlFundsValidation(boolean externalAdjustment) {
        assertOwner();
        if (laneLifecycle.ledger() == null) return true;
        if (ledgerControl != null) return ledgerControl.validateFunds(externalAdjustment);
        // 各 Lane 是否存在余额补丁条目，不等同于每条补丁都有非零差额
        boolean accountFundsChanged = false;
        for (/* 正在汇总资金差额的余额补丁 Lane 下标 */ int laneIndex = 0; laneIndex < accountRollback.balancePatchCount(); laneIndex++) {
            // 该 Lane 已完成的账户余额前后补丁，财务收尾前保持有效
            LaneBalancePatches patches = accountRollback.balancePatch(laneIndex);
accountFundsChanged |= patches.size() != 0;
        }
        if (!accountFundsChanged && changedPositions.isEmpty()) return true;
        return controlFundsValidation.poll(externalAdjustment);
    }

    /**
     * 遍历现有各 Lane 余额前后补丁，将本控制命令资金差额追加到目标累加器，不清空目标。
     * @param target 要追加本控制命令账户资金差额的目标累加器
     */
    public void appendControlAccountFunds(RuntimeFundsAccumulator target) {
        for (/* 正在汇总资金差额的余额补丁 Lane 下标 */ int laneIndex = 0; laneIndex < accountRollback.balancePatchCount(); laneIndex++) {
            // 该 Lane 已完成的账户余额前后补丁，财务收尾前保持有效
            LaneBalancePatches patches = accountRollback.balancePatch(laneIndex);
appendBalanceFundsDelta(patches, target);
        }
    }

    /**
     * 没有专用财务命令槽时使用的账本校验续步：账本先核对账户资金并保留持仓索引撤销信息，Owner 再选择提交或回滚，最后账本发布范围关闭完成。
     */
    private final class ControlFundsValidation implements SystemLedgerLane.Entry {
        /** 账本执行 ready 时汇总账户余额差额的复用累加器，校验通过后清空。 */
        private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
        /** 账本应用控制持仓索引时保存的撤销信息，回滚则恢复，提交则清除。 */
        private final RuntimeFactIndexes.PositionUndo positionUndo = new RuntimeFactIndexes.PositionUndo();
        /**
         * queued：Owner 记录本条目是否已经被账本邮箱接纳，直到上一轮请求关闭且完成后才能开始新轮。
         * externalAdjustment：Owner 入队前固定的外部资金增减选项，经邮箱交接给账本校验。
         * rollback：Owner 选择本财务范围是否回滚，随后用 requested 发布给账本。
         */
        private boolean queued, externalAdjustment, rollback;
        /**
         * validated：账本已完成资金验证及持仓索引应用的可见标记，不等于范围已经关闭。
         * requested：Owner 允许账本进入最终提交或回滚步骤的 volatile 请求标记。
         * completed：账本已完成最终提交或回滚的 volatile 标记，初始为真，开始新轮时复位。
         */
        private volatile boolean validated, requested, completed = true;

        /**
         * Owner 尝试把本轮校验入账本；前轮已请求关闭但未完成时继续等待，邮箱满返回假，成功后只观察账本发布的 validated 标记。
         * @param externalAdjustment 本命令是否允许外部资金增减，交给账本资金守恒检查
         */
        boolean poll(boolean externalAdjustment) {
            if (queued && requested) {
                if (!completed) return false;
                queued = false;
            }
            if (!queued) {
                this.externalAdjustment = externalAdjustment;
                rollback = requested = validated = false;
                completed = false;
                if (!laneLifecycle.ledger().offer(this)) { completed = true; return false; }
                queued = true;
            }
            return validated;
        }

        /**
         * 账本先按余额补丁验证资金守恒并应用可撤销持仓索引，再发布 validated 唤醒 Owner；只有 Owner 请求提交或回滚后才允许条目进入 apply。
         */
        @Override public boolean ready() {
            if (!validated && !requested) {
                appendControlAccountFunds(funds);
                funds.requireConserved(externalAdjustment);
                funds.clear();
                applyLedgerControlPositions(positionUndo);
                validated = true;
                signalOwnerCompletion();
            }
            return requested;
        }

        /**
         * 账本按 Owner 选择回滚持仓索引或丢弃撤销记录，发布完成并唤醒 Owner；资金验证已在 ready 阶段执行。
         * @param ledger 账本实际财库引用；本收尾步骤只处理持仓索引撤销记录
         */
        @Override public void apply(TreasuryRuntime ledger) {
            if (rollback) rollbackLedgerPositions(positionUndo);
            else positionUndo.clear();
            completed = true;
            signalOwnerCompletion();
        }

        /**
         * Owner 对已排队校验发布回滚选择与关闭请求，返回是否已完成；未排队时无需等待。
         */
        boolean requestRollback() {
            if (!queued) return true;
            rollback = true;
            requested = true;
            return completed;
        }

        /**
         * 提交清理时只对已验证且未完成条目发布关闭请求；未验证就清理视为流程错误，不在此等待账本退出。
         */
        void clear() {
            if (!queued || completed) return;
            if (!validated) throw new IllegalStateException("Ledger funds validation is incomplete");
            requested = true;
        }
    }

    /**
     * 仅冷阶段追加各账户余额及财库资金差额；活动账本存在时直接返回，避免重复维护同一资金账。
     * @param accumulator 资金差额接收器，是否先清空由入口决定
     */
    public void appendFundsDelta(RuntimeFundsAccumulator accumulator) {
        assertOwner();
        if (laneLifecycle.ledger() != null) return;
        if (accumulator == null) throw new IllegalArgumentException("funds accumulator is required");
        for (/* 正在汇总资金差额的余额补丁 Lane 下标 */ int laneIndex = 0; laneIndex < accountRollback.balancePatchCount(); laneIndex++) {
            // 本次所属用户的原资产余额映射，用于按预留资产取得余额
            LaneBalancePatches balances = accountRollback.balancePatch(laneIndex);

            appendBalanceFundsDelta(balances, accumulator);

        }
        treasury.appendFundsDelta(accumulator);
    }

    /**
     * 清空目标累加器后，按一份 Lane 余额补丁重建资金差额。
     * @param patches 需要转换为资金差额的 Lane 余额前后补丁
     * @param accumulator 资金差额接收器，是否先清空由入口决定
     */
    public static void prepareBalanceFundsDelta(LaneBalancePatches patches,
                                                 RuntimeFundsAccumulator accumulator) {
        accumulator.clear();
        appendBalanceFundsDelta(patches, accumulator);
    }

    /**
     * 要求每项余额已发布后值，将缺失前后值按零处理，分别精确计算可用与冻结差额并追加用户分账项。
     * @param balances 已捕获前后值的 Lane 余额补丁
     * @param accumulator 资金差额接收器，是否先清空由入口决定
     */
    static void appendBalanceFundsDelta(LaneBalancePatches balances,
                                                RuntimeFundsAccumulator accumulator) {
        for (/* 本轮有效变化项、原始列表或余额补丁的遍历下标 */ int index = 0; index < balances.size(); index++) {
            // 本次余额、预留或划转实际所属的用户身份
            long userId = balances.userId(index);
            // 本次余额或预留对应的资产内部身份
            int assetId = balances.assetId(index);
            if (!balances.capturedAfter(index)) {
                throw new IllegalStateException("balance mutation did not publish its lane after-state");
            }
            // 补丁中的原可用数量，原余额缺失时按零处理
            long beforeAvailable = balances.presentBefore(index) ? balances.availableBefore(index) : 0;
            // 补丁中的原冻结数量，原余额缺失时按零处理
            long beforeLocked = balances.presentBefore(index) ? balances.lockedBefore(index) : 0;
            // 补丁中的新可用数量，新余额已删除时按零处理
            long afterAvailable = balances.presentAfter(index) ? balances.availableAfter(index) : 0;
            // 补丁中的新冻结数量，新余额已删除时按零处理
            long afterLocked = balances.presentAfter(index) ? balances.lockedAfter(index) : 0;
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.AVAILABLE,
                    Math.subtractExact(afterAvailable, beforeAvailable));
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.LOCKED,
                    Math.subtractExact(afterLocked, beforeLocked));
        }
    }

    /**
     * 借出当前命令回滚补丁保存的持仓前值。
     * @param positionKey 持仓内部身份键
     */
    public PositionRuntime currentPatchPositionBefore(long positionKey) {
        return accountRollback.currentPositionBefore(positionKey);
    }

    /**
     * 提交或回滚收尾时清当前命令变化，按冷财库或活动控制槽结束财务范围，再发布清余额补丁并请求独立资金校验关闭；不在此等待账本关闭完成。
     */
    public void clearChangedKeys() {
        assertOwner();
        balancesChanged = false;
        accountRollback.clearNonBalanceImages();
        clearChanged(changedUsers);
        changedOrders.clear();
        clearChanged(changedReservations);
        changedPositions.clear();
        changedLiquidations.clear();
        clearChanged(changedMarkPrices);
        changedRiskSnapshots.clear();
        clearChanged(changedRiskScans);
        changedLeverages.clear();
        changedAlgoOrders.clear();
        changedCancelAllAfterTimers.clear();
        changedTriggerOrders.clear();
        clearChanged(changedFeePolicies);
        if (laneLifecycle.ledger() == null) treasury.clearChangedKeys();
        else if (ledgerControl != null) {
            ledgerControl.commitScope();
            ledgerControl = null;
        }
        accountRollback.publishAndClearBalances();
        controlFundsValidation.clear();
    }

    /**
     * 清空非空长整数变化集合，原数量达到压缩阈值时再压缩内部容量。
     * @param values 要清理的原始变化集合或要排序复制的长整数列表
     */
    static void clearChanged(LongHashSet values) {
        if (values.isEmpty()) return;
        // 清理前集合是否达到压缩阈值，清空后按此决定是否压缩容量
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    /**
     * 清空非空整数变化集合，原数量达到阈值时压缩内部容量。
     * @param values 要清理的原始变化集合或要排序复制的长整数列表
     */
    static void clearChanged(IntHashSet values) {
        if (values.isEmpty()) return;
        // 清理前集合是否达到压缩阈值，清空后按此决定是否压缩容量
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    /**
     * 在调用方已建立的读取边界内，合并各 Lane 用户快照到新映射，不自行取得账户权限。
     */
    public LongObjectHashMap<UserRuntime> usersForSnapshot() {
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        LongObjectHashMap<UserRuntime> values = new LongObjectHashMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, AccountLaneState::usersSnapshot));
        }
        return values;
    }

    /**
     * 在已有读取边界内合并各 Lane 余额快照，返回新顶层映射。
     */
    public LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balancesForSnapshot() {
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> values = new LongObjectHashMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.balancesSnapshot()));
        }
        return values;
    }

    /**
     * 在已有读取边界内把各 Lane 订单引用收集到新映射，不深复制订单。
     */
    public LongObjectHashMap<OrderRuntime> ordersForSnapshot() {
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        LongObjectHashMap<OrderRuntime> values = new LongObjectHashMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                lane.forEachOrder(values::put);
                return null;
            });
        }
        return values;
    }

    /**
     * 在所属账户范围内，按用户、标的、持仓方向、买卖侧和保证金模式读取现有减仓单数量。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param symbolId 本产品线标的内部身份
     * @param positionSide 持仓方向，用于持仓及强平身份区分
     * @param side 订单买卖方向
     * @param marginMode 订单、持仓或杠杆配置的保证金模式
     */
    public long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        return onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.openReduceOnlyQuantity(
                userId, symbolId, positionSide, side, marginMode));
    }

    /**
     * 在已有读取边界内收集各 Lane 预留引用到新映射。
     */
    public LongObjectHashMap<ReservationRuntime> reservationsForSnapshot() {
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        LongObjectHashMap<ReservationRuntime> values = new LongObjectHashMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
                lane.forEachReservation(values::put);
                return null;
            });
        }
        return values;
    }

    /**
     * 在已有读取边界内合并各 Lane 客户订单号索引快照。
     */
    public LongObjectHashMap<LongLongHashMap> clientOrderIndexForSnapshot() {
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        LongObjectHashMap<LongLongHashMap> values = new LongObjectHashMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.clientOrdersSnapshot()));
        }
        return values;
    }

    /**
     * 在已有读取边界内合并各 Lane 提供的持仓快照。
     */
    public LongObjectHashMap<PositionRuntime> positionsForSnapshot() {
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        LongObjectHashMap<PositionRuntime> values = new LongObjectHashMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.positionsSnapshot()));
        }
        return values;
    }

    /**
     * 在已有读取边界内合并各 Lane 提供的强平快照。
     */
    public LongObjectHashMap<LiquidationRuntime> liquidationsForSnapshot() {
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        LongObjectHashMap<LiquidationRuntime> values = new LongObjectHashMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.liquidationsSnapshot()));
        }
        return values;
    }

    /**
     * 借出原标记价映射，由外层快照边界保护读取，不创建独立映射。
     */
    public IntObjectHashMap<MarkPriceRuntime> markPricesForSnapshot() {
        return markPrices;
    }

    /**
     * 在已有读取边界内合并各 Lane 提供的风险快照映射。
     */
    public LongObjectHashMap<RiskSnapshotRuntime> riskSnapshotsForSnapshot() {
        // 本次新建的汇总映射，按既有读取边界收集各账户内容
        LongObjectHashMap<RiskSnapshotRuntime> values = new LongObjectHashMap<>();
        for (/* 本轮正在处理或按用户路由选出的账户 Lane 编号 */ int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> lane.riskSnapshotsSnapshot()));
        }
        return values;
    }

    /**
     * 借出原风险扫描映射，由外层快照边界保护读取。
     */
    public IntObjectHashMap<RiskScanRuntime> riskScansForSnapshot() {
        return riskScans;
    }

    /**
     * 校验已准备订单与预留的身份、用户、标的和数量一致，再以预留剩余量调用冻结安装入口。
     * @param order 本次安装、更新或交接的订单值
     * @param reservation 与订单对应的资金预留值
     * @param clientKey 客户订单号内部键，零表示没有客户号
     */
    public void reserveOrder(OrderRuntime order, ReservationRuntime reservation, long clientKey) {
        if (order == null || reservation == null || order.orderId() != reservation.orderId()
                || order.userId() != reservation.userId() || order.symbolId() != reservation.symbolId()
                || order.quantitySteps() != reservation.orderQuantitySteps() || clientKey < 0) {
            throw new IllegalArgumentException("invalid prepared reservation");
        }
        reserveOrder(order, reservation, clientKey, reservation.reservedUnits());
    }

    /**
     * 保存命令回滚前值，在所属 Lane 冻结指定资金并安装已有订单、预留及客户号；范围内通过 Lane 变化交接，范围外登记 Owner 变化。匹配输入由调用方准备。
     * @param order 本次安装、更新或交接的订单值
     * @param reservation 与订单对应的资金预留值
     * @param clientKey 客户订单号内部键，零表示没有客户号
     * @param reservedUnits 本次需要实际冻结的资金整数数量
     */
    public void reserveOrder(OrderRuntime order, ReservationRuntime reservation,
                              long clientKey, long reservedUnits) {
        assertOwner();
        // 当前客户号、订单或预留对应的订单实体 ID
        long orderId = order.orderId();
        // 本次余额、预留或划转实际所属的用户身份
        long userId = order.userId();
        // 本次余额或预留对应的资产内部身份
        int assetId = reservation.assetId();
        if (order(orderId) != null) {
            throw new IllegalArgumentException("runtime order already exists: " + orderId);
        }
        accountRollback.captureUserBefore(userId);
        accountRollback.captureOrderBefore(orderId, userId);
        accountRollback.captureReservationBefore(orderId, userId);
        accountRollback.captureBalanceBefore(userId, assetId);
        if (clientKey != 0) accountRollback.captureClientOrderBefore(userId, clientKey);
        // 当前已经接入的可空账户范围，用于直接执行或选择交接路径
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) reserveOrderInLane(scoped, order, reservation, clientKey, reservedUnits);
        else onLane(userId, /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            reserveOrderInLane(lane, order, reservation, clientKey, reservedUnits);
            return null;
        });
        // 账户控制任务运行时 Owner 可能挂起或收集当前提交。
        // 通过 Lane 变化交接，等 Owner 观察完成后再由 collectControlReservation 登记变化。
        if (currentLaneCommandScope() == null) {
            changedOrder(orderId, order);
            markReservationChanged(orderId);
            markUserChanged(userId);
            markBalancesChanged();
        } else {
            publishOrder(orderId, order);
            publishReservation(orderId, reservation);
        }
    }

    /**
     * 核对所属 Lane、客户号唯一性、用户及余额存在，冻结指定数量并安装订单与预留索引，再捕获余额后值。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param order 本次安装、更新或交接的订单值
     * @param reservation 与订单对应的资金预留值
     * @param clientKey 客户订单号内部键，零表示没有客户号
     * @param reservedUnits 本次需要实际冻结的资金整数数量
     */
    private void reserveOrderInLane(AccountLaneState lane, OrderRuntime order, ReservationRuntime reservation,
                                    long clientKey, long reservedUnits) {
        lane.assertOwner();
        // 当前客户号、订单或预留对应的订单实体 ID
        // 本次余额、预留或划转实际所属的用户身份
        long userId = order.userId(), orderId = order.orderId();
        // 本次余额或预留对应的资产内部身份
        int assetId = reservation.assetId();
        if (lane.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("reservation crossed Account Lane");
        // 该用户客户号到订单的原始映射，零表示没有对应订单
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey))
            throw new IllegalArgumentException("runtime client order already exists: " + clientKey);
        if (lane.user(userId) == null)
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        // 本次所属用户的原资产余额映射，用于按预留资产取得余额
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        // 按本订单或预留资产取到的余额，必须存在才能冻结或释放
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        if (balance == null)
            throw new IllegalArgumentException("runtime balance is not registered: " + userId + "/" + assetId);
        balance.reserve(reservedUnits);
        lane.putOrder(order);
        lane.storeReservation(orderId, reservation);
        lane.addReservationId(userId, orderId);
        if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, orderId);
        accountRollback.captureBalanceAfter(lane, userId, assetId);
    }

    /**
     * 用已解析参数构造独立 OPEN 订单，初始修订为一；冷构造与基准可复用此入口，活动准入使用 Lane 池。
     * @param productLine 新订单唯一所属产品线
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param command 本次已经解码的费用政策或下单参数
     * @param commandId 原业务命令 UUID
     * @param symbolId 本产品线标的内部身份
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     */
    static OrderRuntime preparedOrder(com.surprising.product.api.ProductLine productLine,
            long userId, ResolvedPlaceOrder command, java.util.UUID commandId, int symbolId,
            long timestamp, long position) {
        return new OrderRuntime(command.orderId(), productLine, userId, symbolId,
                command.instrument(), command.side(), command.matchingPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                0, timestamp, timestamp, position, CoreOrderStatus.OPEN, 1);
    }

    /**
     * 纯冷阶段构造独立订单，活动或 FUSED 要求当前所属 Lane 并使用共享退休栅栏的订单池。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param command 本次已经解码的费用政策或下单参数
     * @param commandId 原业务命令 UUID
     * @param symbolId 本产品线标的内部身份
     */
    public OrderRuntime prepareControlOrder(long userId, ResolvedPlaceOrder command, java.util.UUID commandId, int symbolId) {
        if (!accountLanesStarted() && !fusedExecutionActive)
            return preparedOrder(productLine, userId, command, commandId, symbolId, 0, 0);
        // 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("active order creation requires its Account Lane");
        return lane.preparedOrder(productLine, userId, command, commandId, symbolId, 0, 0, retiredOwnerSequence);
    }

    /**
     * 校验所属范围、身份、客户号及可用余额，选择已准备值或 Lane 池订单，冻结资金并写入预留、索引及用户修订。仅未传 prepared 时自行登记待预留，普通临时下单不保存控制命令回滚前值。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param command 本次已经解码的费用政策或下单参数
     * @param commandId 原业务命令 UUID
     * @param requiredReservation 本次准入要求冻结的正值资金数量
     * @param clientKey 客户订单号内部键，零表示没有客户号
     * @param symbolId 本产品线标的内部身份
     * @param assetId 本产品线资产内部身份
     * @param coreSequence 原业务命令的 Core 序号
     * @param prepared 可空的已准备订单，调用方保持其身份与本次参数一致
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     */
    public void placeOrderInLane(AccountLaneState lane, long userId, ResolvedPlaceOrder command,
            java.util.UUID commandId, long requiredReservation, long clientKey,
            int symbolId, int assetId, long coreSequence, OrderRuntime prepared, long timestamp, long position) {
        if (lane == null || !(lane.commandScopeActive() || Thread.currentThread() == owner && ownerLaneScope == lane)
                || lane.laneId() != topology.accountLaneId(userId)
                || command == null || commandId == null || userId <= 0 || requiredReservation <= 0
                || clientKey < 0 || symbolId < 0 || assetId < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid Lane-owned provisional place order");
        }
        lane.assertOwner();
        if (lane.hasOrder(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        // 该用户客户号到订单的原始映射，零表示没有对应订单
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        // 本次下单所属用户的原状态，计算下一修订并保留当前持仓模式
        UserRuntime user = lane.user(userId);
        // 本次所属用户的原资产余额映射，用于按预留资产取得余额
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        // 按本订单或预留资产取到的余额，必须存在才能冻结或释放
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        // 当前处理的原订单或更新后订单，使用范围由本分支的账户所有权限定
        OrderRuntime order = prepared != null && !prepared.commandSlotValue() ? prepared
                : accountLanesStarted() || fusedExecutionActive
                ? lane.preparedOrder(productLine, userId, command, commandId, symbolId, timestamp, position, retiredOwnerSequence)
                : preparedOrder(productLine, userId, command, commandId, symbolId, timestamp, position);
        // 当前订单对应的原预留，释放与消耗修改需同时维护待预留差额
        ReservationRuntime reservation = order.prepareReservation(
                command.reservationKind(), assetId, requiredReservation);
        // 写入订单前计算的下一用户修订，溢出立即失败
        long nextUserRevision = Math.incrementExact(user.revision());
        balance.reserve(requiredReservation);
        lane.putOrder(order);
        lane.storeReservation(reservation.orderId(), reservation.laneValue());
        lane.addReservationId(userId, order.orderId());
        if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, order.orderId());
        lane.storeUser(userId, (accountLanesStarted() ? user.laneValue() : user)
                .withState(nextUserRevision, user.positionMode()));
        if (prepared == null) lane.markPendingReservation(order.orderId(), coreSequence);
    }

    /**
     * 在所属 Lane 保存前值并禁止换用户，写入持仓及索引；没有撮合变化范围时再登记 Owner 变化。
     * @param positionKey 持仓内部身份键
     * @param position 要保存的持仓状态，已有身份不得更换用户
     */
    public void putPosition(long positionKey, PositionRuntime position) {
        assertOwner();
        // 本次替换或删除前的原业务值，用于保存原用户和维护旧索引
        PositionRuntime previous = onLane(position.userId(), /* 本次业务所在的账户 Lane，回调仅在既有合法执行范围内使用 */ lane -> {
            accountRollback.capturePositionBefore(positionKey, position.userId());
            // 当前用户 Lane 内的原预留或持仓，用于替换前值和索引比较
            PositionRuntime prev = lane.position(positionKey);
            if (prev != null && prev.userId() != position.userId()) {
                throw new IllegalArgumentException("runtime position owner cannot change");
            }
            lane.storePosition(positionKey, position.laneValue());
            replacePositionIndexes(lane, positionKey, prev, position);
            return prev;
        });
        publishPosition(positionKey, position);
        if (currentMatcherSettlementChangesScope() == null) {
            changedPosition(positionKey, position);
            markUserChanged(position.userId());
            if (previous != null) markUserChanged(previous.userId());
        }
    }

    /**
     * 首次持仓建立用户和活动索引；已有同用户持仓只在标的或非零暴露状态变化时改动活动索引，不为普通数量或正负变化重复维护成员关系。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param positionKey 持仓内部身份键
     * @param previous 索引替换前的可空持仓值
     * @param replacement 索引替换后的持仓值，调用方已保证同一用户
     */
    private static void replacePositionIndexes(AccountLaneState lane, long positionKey,
                                               PositionRuntime previous, PositionRuntime replacement) {
        if (previous == null) {
            indexPosition(lane, positionKey, replacement);
            return;
        }
        // 调用方已经保证同一用户；普通元数据、数量及正负变化不改变用户成员关系。
        // 标的相同且仍有非零暴露时，也不重复维护标的成员关系。
        if (previous.symbolId() == replacement.symbolId()
                && (previous.signedQuantitySteps() != 0) == (replacement.signedQuantitySteps() != 0)) return;
        unindexOpenPosition(lane, positionKey, previous);
        indexOpenPosition(lane, positionKey, replacement);
    }

    /**
     * 登记用户持仓键，并按非零暴露条件维护标的活动持仓索引。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param positionKey 持仓内部身份键
     * @param position 用于登记用户及非零暴露索引的持仓值
     */
    public static void indexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        lane.indexPositionKey(position.userId(), positionKey);
        indexOpenPosition(lane, positionKey, position);
    }

    /**
     * 只为非零持仓在标的与用户下保存有序原始键列表，二分插入避免重复身份。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param positionKey 持仓内部身份键
     * @param position 非零时需要加入活动索引的持仓值
     */
    private static void indexOpenPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        if (position.signedQuantitySteps() == 0) return;
        // 当前标的按用户分组的活动持仓键索引
        LongObjectHashMap<org.eclipse.collections.impl.list.mutable.primitive.LongArrayList> byUser =
                lane.symbolPositionKeys(position.symbolId());
        if (byUser == null) {
            byUser = new LongObjectHashMap<>();
            lane.storeSymbolPositionKeys(position.symbolId(), byUser);
        }
        // 该标的该用户的有序持仓键原始列表，二分定位后按需插入
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList keys = byUser.get(position.userId());
        if (keys == null) {
            keys = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(2);
            byUser.put(position.userId(), keys);
        }
        // 本轮有效变化项、原始列表或余额补丁的遍历下标
        int index = keys.binarySearch(positionKey);
        if (index < 0) keys.addAtIndex(-index - 1, positionKey);
    }

    /**
     * 删除用户持仓键，并撤销对应标的活动索引。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param positionKey 持仓内部身份键
     * @param position 需要从用户及活动索引撤出的原持仓值
     */
    public static void unindexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        lane.unindexPositionKey(position.userId(), positionKey);
        unindexOpenPosition(lane, positionKey, position);
    }

    /**
     * 从标的及用户有序键列表删除指定身份，列表或分组空后逐层清理。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param positionKey 持仓内部身份键
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param symbolId 本产品线标的内部身份
     */
    private static void unindexOpenPosition(AccountLaneState lane, long positionKey,
                                             long userId, int symbolId) {
        // 当前标的按用户分组的活动持仓键索引
        LongObjectHashMap<org.eclipse.collections.impl.list.mutable.primitive.LongArrayList> byUser =
                lane.symbolPositionKeys(symbolId);
        // 该标的该用户的原持仓键列表，二分定位后移除目标身份
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList keys =
                byUser == null ? null : byUser.get(userId);
        if (keys == null) return;
        // 本轮有效变化项、原始列表或余额补丁的遍历下标
        int index = keys.binarySearch(positionKey);
        if (index < 0) return;
        keys.removeAtIndex(index);
        if (keys.isEmpty()) byUser.remove(userId);
        if (byUser.isEmpty()) lane.removeSymbolPositionKeys(symbolId);
    }

    /**
     * 旧持仓非零时按其用户和标的删除活动持仓索引，零持仓无需处理。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param positionKey 持仓内部身份键
     * @param position 需要撤销活动暴露索引的原持仓值
     */
    private static void unindexOpenPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        if (position.signedQuantitySteps() == 0) return;
        unindexOpenPosition(lane, positionKey, position.userId(), position.symbolId());
    }

    /**
     * 没有当前账户范围时设置 Owner 余额变化标记；Lane 内明细由原余额补丁交接。
     */
    public void markBalancesChanged() {
        if (currentLaneCommandScope() == null) balancesChanged = true;
    }

    /** 缺少持仓索引时复用的共享空原始列表，调用方只能在有效范围读取，不能修改。 */
    private static final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList EMPTY_POSITION_KEYS =
            new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(0);

    /**
     * 把原始长整数列表复制为有序去重集合，供明确的导出读取使用。
     * @param values 要清理的原始变化集合或要排序复制的长整数列表
     */
    static TreeSet<Long> toSortedSet(org.eclipse.collections.impl.list.mutable.primitive.LongArrayList values) {
        // 从原始长整数列表复制出的有序去重集合
        TreeSet<Long> sorted = new TreeSet<>();
        for (/* 本轮有效变化项、原始列表或余额补丁的遍历下标 */ int index = 0; index < values.size(); index++) sorted.add(values.get(index));
        return sorted;
    }

    /**
     * 更新客户号到订单及订单到客户号双向索引；客户号改指另一订单时先移除旧反向关系。调用方保证订单号为正，零保留为原始映射缺失值。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientKey 客户订单号内部键，零表示没有客户号
     * @param orderId 本次操作关联的订单实体 ID
     */
    public static void putClientOrderIndex(AccountLaneState lane, long userId, long clientKey, long orderId) {
        // 该用户客户号到订单的原始映射，零表示没有对应订单
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (userClientOrders == null) {
            userClientOrders = new Long2LongHashMap(lane.admissionIndexCapacity(), 0.65f, 0, false);
            lane.storeUserClientOrders(userId, userClientOrders);
        }
        // 订单 ID 使用正值，零保留为原始映射的缺失值。
        // 直接读取即可判断旧指向，避免先 contains 再 get 的重复探测。
        // 客户号原来指向的订单号，零表示没有旧关系
        long previousOrderId = userClientOrders.get(clientKey);
        // 客户号原来是否有非零订单指向，决定是否撤销旧反向关系
        boolean hadPrevious = previousOrderId != 0;
        userClientOrders.put(clientKey, orderId);
        if (hadPrevious && previousOrderId != orderId) {
            removeClientOrderReverse(lane, previousOrderId, clientKey);
        }
        lane.clientKeysByOrderId().add(orderId, clientKey);
    }

    /**
     * 客户号存在时删除正向及反向关系并返回原订单号，未找到返回空。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param clientKey 客户订单号内部键，零表示没有客户号
     */
    public static Long removeClientOrderIndex(AccountLaneState lane, long userId, long clientKey) {
        // 该用户客户号到订单的原始映射，零表示没有对应订单
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (userClientOrders == null) return null;
        // 当前客户号、订单或预留对应的订单实体 ID
        long orderId = userClientOrders.get(clientKey);
        if (orderId == 0) return null;
        userClientOrders.remove(clientKey);
        removeClientOrderReverse(lane, orderId, clientKey);
        return orderId;
    }

    /**
     * 只删除订单与客户号的反向关联，供正向索引更新配套调用。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param orderId 本次操作关联的订单实体 ID
     * @param clientKey 客户订单号内部键，零表示没有客户号
     */
    static void removeClientOrderReverse(AccountLaneState lane, long orderId, long clientKey) {
        lane.clientKeysByOrderId().remove(orderId, clientKey);
    }

    /**
     * 为未完成且未取消的强平建立用户、标的、方向索引，同一持仓方向出现不同活动强平时拒绝。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param liquidation 本次保存、替换或活动索引处理的强平状态
     */
    static void indexActiveLiquidation(AccountLaneState lane, LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        // 当前用户按标的分组的活动强平索引
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(liquidation.userId());
        if (bySymbol == null) {
            bySymbol = new IntObjectHashMap<>();
            lane.storeActiveLiquidations(liquidation.userId(), bySymbol);
        }
        // 当前标的按持仓方向保存的活动强平身份映射
        LongObjectHashMap<Long> bySide = bySymbol.get(liquidation.symbolId());
        if (bySide == null) {
            bySide = new LongObjectHashMap<>();
            bySymbol.put(liquidation.symbolId(), bySide);
        }
        // 同用户、标的和方向当前已有的可空强平 ID，禁止另一个活动身份占位
        Long current = bySide.get(liquidation.positionSide().ordinal());
        if (current != null && current != liquidation.liquidationId()) {
            throw new IllegalStateException("multiple active runtime liquidations for one position");
        }
        bySide.put(liquidation.positionSide().ordinal(), liquidation.liquidationId());
    }

    /**
     * 旧强平仍属活动分类时删除其方向索引，并逐层清理空标的与用户分组。
     * @param lane 明确所属的账户 Lane，沿用调用入口的所有权与完成边界
     * @param liquidation 本次保存、替换或活动索引处理的强平状态
     */
    static void removeActiveLiquidation(AccountLaneState lane, LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        // 当前用户按标的分组的活动强平索引
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(liquidation.userId());
        // 当前标的按持仓方向保存的活动强平身份映射
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(liquidation.symbolId());
        if (bySide == null) return;
        bySide.remove(liquidation.positionSide().ordinal());
        if (bySide.isEmpty()) bySymbol.remove(liquidation.symbolId());
        if (bySymbol.isEmpty()) lane.removeActiveLiquidations(liquidation.userId());
    }

    /**
     * 判断强平是否尚未 COMPLETED 且未 CANCELED，用作活动索引的统一分类。
     * @param liquidation 本次保存、替换或活动索引处理的强平状态
     */
    static boolean active(LiquidationRuntime liquidation) {
        return liquidation.status() != CoreLiquidationState.Status.COMPLETED
                && liquidation.status() != CoreLiquidationState.Status.CANCELED;
    }

    /**
     * 将已完成控制下单收据交给待预留跟踪器，在 Owner 观察任务完成后登记。
     * @param order 本次安装、更新或交接的订单值
     * @param coreSequence 原业务命令的 Core 序号
     */
    public void collectControlReservation(OrderRuntime order, long coreSequence) {
        pendingReservations.collectControlReservation(order, coreSequence);
    }

    /**
     * 通过唯一跟踪器登记用户订单及对应 Core 序号的待完成预留。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orderId 本次操作关联的订单实体 ID
     * @param coreSequence 原业务命令的 Core 序号
     */
    public void markPendingReservation(long userId, long orderId, long coreSequence) { pendingReservations.markPendingReservation(userId, orderId, coreSequence); }
    /**
     * 通过唯一跟踪器完成给定用户订单的该序号预留。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orderId 本次操作关联的订单实体 ID
     * @param coreSequence 原业务命令的 Core 序号
     */
    public void completePendingReservation(long userId, long orderId, long coreSequence) { pendingReservations.completePendingReservation(userId, orderId, coreSequence); }
    /**
     * 让唯一跟踪器完成指定 Core 序号的全部待预留。
     * @param coreSequence 原业务命令的 Core 序号
     */
    public void completePendingReservations(long coreSequence) { pendingReservations.completePendingReservations(coreSequence); }
    /**
     * 查询唯一跟踪器是否还保留待完成预留。
     */
    public boolean hasPendingReservations() { return pendingReservations.hasPendingReservations(); }
    /**
     * 以同一业务序号和提交序号，把已准备单笔或批量订单交给直接结算准备入口。
     * @param sequence 当前直接结算使用的业务 Core 序号
     * @param laneMask 当前直接结算初始需要覆盖的账户 Lane 位集合
     * @param single 单笔路径已有的订单值，批量路径可为空
     * @param orders 批量路径已有的订单值数组，单笔路径可为空
     * @param count 本次订单数组的实际有效项数，不以数组容量代替
     * @param commandId 原业务命令 UUID
     * @param shard 本命令对应的 Matcher 分片
     * @param identities 与本产品线配套的内部身份注册表
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     * @param cancellations 结算前需要处理的撤单身份列表
     * @param target 接收本次直接结算订单结果的既有槽
     */
    public MatcherSettlementEvent prepareDirectMatcherSettlement(long sequence, long laneMask,
            OrderRuntime single, OrderRuntime[] orders, int count, java.util.UUID commandId, int shard,
            RuntimeIdentityRegistry identities, long timestamp, long position, List<Long> cancellations,
            LaneOrderResultTarget target) {
        return settlements.prepareDirect(sequence, sequence, laneMask, single, orders, count, commandId, shard,
                identities, timestamp, position, cancellations, target);
    }

    /**
     * 普通下单准入尚未完成时先准备直接结算槽，固定命令证明字段；实际账户订单由后续准入收据接入。
     * @param sequence 当前直接结算使用的业务 Core 序号
     * @param laneMask 当前直接结算初始需要覆盖的账户 Lane 位集合
     * @param admission 已有下单或改单准入结果，沿用对应事件生命周期
     * @param commandId 原业务命令 UUID
     * @param shard 本命令对应的 Matcher 分片
     * @param identities 与本产品线配套的内部身份注册表
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     * @param cancellations 结算前需要处理的撤单身份列表
     * @param target 接收本次准入后直接结算订单结果的既有槽
     */
    public MatcherSettlementEvent prepareDirectMatcherSettlement(
            long sequence, long laneMask, PlaceAdmissionEvent admission,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, List<Long> cancellations, LaneOrderResultTarget target) {
        return settlements.prepareDirectAdmission(sequence, laneMask, admission, commandId, shard,
                identities, timestamp, position, cancellations, target);
    }

    /**
     * 显式区分原业务序号与提交序号，准备直接撮合结算事件，不在此应用账户成交。
     * @param coreSequence 原业务命令的 Core 序号
     * @param commitSequence 本次实际账户提交序号，与原业务序号显式区分
     * @param laneMask 当前直接结算初始需要覆盖的账户 Lane 位集合
     * @param single 单笔路径已有的订单值，批量路径可为空
     * @param orders 批量路径已有的订单值数组，单笔路径可为空
     * @param count 本次订单数组的实际有效项数，不以数组容量代替
     * @param commandId 原业务命令 UUID
     * @param shard 本命令对应的 Matcher 分片
     * @param identities 与本产品线配套的内部身份注册表
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     * @param cancellations 结算前需要处理的撤单身份列表
     * @param target 接收本次直接结算订单结果的既有槽
     */
    public MatcherSettlementEvent prepareDirectMatcherSettlement(long coreSequence, long commitSequence,
            long laneMask, OrderRuntime single, OrderRuntime[] orders, int count, java.util.UUID commandId,
            int shard, RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, LaneOrderResultTarget target) {
        return settlements.prepareDirect(coreSequence, commitSequence, laneMask, single, orders, count,
                commandId, shard, identities, timestamp, position, cancellations, target);
    }

    /**
     * 把已解决的改单准入及原提交边界交给直接替换结算准备入口。
     * @param sequence 当前直接结算使用的业务 Core 序号
     * @param commitSequence 本次实际账户提交序号，与原业务序号显式区分
     * @param laneMask 当前直接结算初始需要覆盖的账户 Lane 位集合
     * @param admission 已有下单或改单准入结果，沿用对应事件生命周期
     * @param commandId 原业务命令 UUID
     * @param shard 本命令对应的 Matcher 分片
     * @param identities 与本产品线配套的内部身份注册表
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     * @param cancellations 结算前需要处理的撤单身份列表
     * @param resultTarget 可空的直接结算最终订单结果槽
     */
    public MatcherSettlementEvent prepareDirectReplacement(long sequence, long commitSequence, long laneMask,
            com.surprising.aeron.service.order.command.ResolvedMatchingAdmission admission,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, List<Long> cancellations, LaneOrderResultTarget resultTarget) {
        return settlements.prepareDirectReplacement(sequence, commitSequence, laneMask, admission, commandId, shard,
                identities, timestamp, position, cancellations, resultTarget);
    }

    /**
     * 为直接撤单准备补上空的最终结果槽。
     * @param sequence 当前直接结算使用的业务 Core 序号
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orderId 本次操作关联的订单实体 ID
     * @param commandId 原业务命令 UUID
     * @param shard 本命令对应的 Matcher 分片
     * @param identities 与本产品线配套的内部身份注册表
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     */
    public MatcherSettlementEvent prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position) {
        return prepareDirectCancellation(sequence, userId, orderId, commandId, shard, identities,
                timestamp, position, null);
    }

    /**
     * 按用户、订单和原提交边界准备直接撤单结算事件。
     * @param sequence 当前直接结算使用的业务 Core 序号
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orderId 本次操作关联的订单实体 ID
     * @param commandId 原业务命令 UUID
     * @param shard 本命令对应的 Matcher 分片
     * @param identities 与本产品线配套的内部身份注册表
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     * @param resultTarget 可空的直接结算最终订单结果槽
     */
    public MatcherSettlementEvent prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, LaneOrderResultTarget resultTarget) {
        return settlements.prepareDirectCancellation(sequence, userId, orderId, commandId, shard,
                identities, timestamp, position, resultTarget);
    }

    /**
     * 把批量撤单身份、有效项数及是否最后一段交给直接结算准备入口。
     * @param sequence 当前直接结算使用的业务 Core 序号
     * @param finalChunk 本次是否为批量撤单最后一段，决定最终收尾边界
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     * @param orders 本段实际撤单的原始订单号数组
     * @param count 本次订单数组的实际有效项数，不以数组容量代替
     * @param commandId 原业务命令 UUID
     * @param shard 本命令对应的 Matcher 分片
     * @param identities 与本产品线配套的内部身份注册表
     * @param timestamp 原命令业务时间，随账户任务或结算边界保存
     * @param position 原复制日志位置，随本次账户或结算边界保存
     * @param resultTarget 可空的直接结算最终订单结果槽
     */
    public MatcherSettlementEvent prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, long[] orders,
            int count, java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, LaneOrderResultTarget resultTarget) {
        return settlements.prepareDirectCancelBatch(sequence, finalChunk, userId, orders, count, commandId, shard,
                identities, timestamp, position, resultTarget);
    }

    /**
     * 让结算派发器归还已完成并收集的事件资源，沿用其消费者完成检查。
     * @param event 要收集或归还的原结算事件，沿用派发器生命周期检查
     */
    public void releaseMatcherSettlement(MatcherSettlementEvent event) { settlements.releaseMatcherSettlement(event); }
    /**
     * 让结算派发器丢弃尚可安全撤销的事件，沿用原生命周期检查。
     * @param event 要收集或归还的原结算事件，沿用派发器生命周期检查
     */
    public void discardMatcherSettlement(MatcherSettlementEvent event) { settlements.discardMatcherSettlement(event); }
    /**
     * 使用空资金接收器收集批次撮合结算，沿用统一派发器。
     * @param event 要收集或归还的原结算事件，沿用派发器生命周期检查
     * @param terminalOrderSink 可空的本批终态订单身份接收器
     */
    public RuntimeTreasuryDelta collectOrderBatchMatcherSettlement(
            MatcherSettlementEvent event, TerminalOrderSink terminalOrderSink) {
        return collectOrderBatchMatcherSettlement(event, null, terminalOrderSink);
    }

    /**
     * 把批次结算事件、可选资金及终态接收器交给唯一结算派发器收集。
     * @param event 要收集或归还的原结算事件，沿用派发器生命周期检查
     * @param funds 可空的本批资金差额接收器
     * @param terminalOrderSink 可空的本批终态订单身份接收器
     */
    public RuntimeTreasuryDelta collectOrderBatchMatcherSettlement(
            MatcherSettlementEvent event, RuntimeFundsAccumulator funds, TerminalOrderSink terminalOrderSink) {
        return settlements.collectOrderBatchMatcherSettlement(event, funds, terminalOrderSink);
    }

    /**
     * 借出包含直接与保留 Lane 输出的订单变化容器。
     */
    public OwnerIndexedChanges<OrderRuntime, Void> orderChanges() { return changedOrders; }
    /**
     * 借出包含持仓值及已准备索引的变化容器。
     */
    public OwnerIndexedChanges<PositionRuntime, RuntimePositionIndexValue> positionChanges() { return changedPositions; }

    /**
     * 按数组下标借出原账户 Lane，调用方保证下标及访问边界有效。
     * @param index 调用方已经核对有效性的账户 Lane 数组下标
     */
    public AccountLaneState accountLane(int index) { return accountLanes[index]; }
    /**
     * 返回账户 Lane 数量。
     */
    public int accountLaneCount() { return accountLanes.length; }

    /**
     * 直接向 Owner 变化集合加入用户身份，调用方保证当前收集范围正确。
     * @param userId 本次业务所属用户身份，按固定拓扑路由
     */
    public void changedUser(long userId) { changedUsers.add(userId); }
    /**
     * 直接向 Owner 变化集合加入预留订单身份。
     * @param orderId 本次操作关联的订单实体 ID
     */
    public void changedReservation(long orderId) { changedReservations.add(orderId); }
    /**
     * 登记仍由风险记录持有的持仓身份，延后其退休释放。
     * @param key 本次查询或修改使用的业务键
     */
    public void addRiskPositionIdentity(long key) { riskPositionIdentities.add(key); }
    /**
     * 取消风险记录对持仓身份的保留标记。
     * @param key 本次查询或修改使用的业务键
     */
    public void removeRiskPositionIdentity(long key) { riskPositionIdentities.remove(key); }
    /**
     * 直接恢复调用方提供的修订值，不在此校验或推进业务。
     * @param value 由恢复调用方直接交接的修订值
     */
    public void restoreRevision(long value) { revision = value; }
}
