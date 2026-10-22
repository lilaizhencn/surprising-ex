package com.surprising.aeron.bootstrap.account;

import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration.SettlementWaitStrategy;

import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.settlement.MatcherSettlementEvent;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * 交接请求字段之前的填充基类，隔开频繁跨线程写入的数据，不保存业务。
 */
abstract class SettlementLaneWorkerLhsPadding {
    /**
     * p00：交接请求字段之前的填充，不保存业务值。
     * p01：交接请求字段之前的填充，不保存业务值。
     * p02：交接请求字段之前的填充，不保存业务值。
     * p03：交接请求字段之前的填充，不保存业务值。
     * p04：交接请求字段之前的填充，不保存业务值。
     * p05：交接请求字段之前的填充，不保存业务值。
     * p06：交接请求字段之前的填充，不保存业务值。
     * p07：交接请求字段之前的填充，不保存业务值。
     */
    @SuppressWarnings("unused")
    private long p00, p01, p02, p03, p04, p05, p06, p07;
}

/**
 * 保存 Owner 请求的账户所有权交接轮次。
 */
abstract class SettlementLaneWorkerHandoffRequested extends SettlementLaneWorkerLhsPadding {
    /** Owner 发布的请求轮次，是否重复请求由外层交接协议保证。 */
    protected volatile long requestedHandoff;
}

/**
 * 请求轮次与完成轮次之间的填充，减少相邻写入争用。
 */
abstract class SettlementLaneWorkerMidPadding1 extends SettlementLaneWorkerHandoffRequested {
    /**
     * p10：交接请求与完成字段之间的填充，不保存业务值。
     * p11：交接请求与完成字段之间的填充，不保存业务值。
     * p12：交接请求与完成字段之间的填充，不保存业务值。
     * p13：交接请求与完成字段之间的填充，不保存业务值。
     * p14：交接请求与完成字段之间的填充，不保存业务值。
     * p15：交接请求与完成字段之间的填充，不保存业务值。
     * p16：交接请求与完成字段之间的填充，不保存业务值。
     * p17：交接请求与完成字段之间的填充，不保存业务值。
     */
    @SuppressWarnings("unused")
    private long p10, p11, p12, p13, p14, p15, p16, p17;
}

/**
 * 保存账户线程已经执行交接并释放所有权的轮次。
 */
abstract class SettlementLaneWorkerHandoffCompleted extends SettlementLaneWorkerMidPadding1 {
    /** Lane 执行普通邮箱中的交接任务并释放所有权后发布的轮次；不表示其他邮箱都已排空。 */
    protected volatile long completedHandoff;
}

/**
 * 交接完成与恢复轮次之间的填充，不参与资金或订单状态。
 */
abstract class SettlementLaneWorkerMidPadding2 extends SettlementLaneWorkerHandoffCompleted {
    /**
     * p20：交接完成与恢复字段之间的填充，不保存业务值。
     * p21：交接完成与恢复字段之间的填充，不保存业务值。
     * p22：交接完成与恢复字段之间的填充，不保存业务值。
     * p23：交接完成与恢复字段之间的填充，不保存业务值。
     * p24：交接完成与恢复字段之间的填充，不保存业务值。
     * p25：交接完成与恢复字段之间的填充，不保存业务值。
     * p26：交接完成与恢复字段之间的填充，不保存业务值。
     * p27：交接完成与恢复字段之间的填充，不保存业务值。
     */
    @SuppressWarnings("unused")
    private long p20, p21, p22, p23, p24, p25, p26, p27;
}

/**
 * 保存 Owner 已归还账户后发布的恢复轮次。
 */
abstract class SettlementLaneWorkerHandoffResumed extends SettlementLaneWorkerMidPadding2 {
    /** Owner 归还临时账户所有权后发布的恢复轮次。 */
    protected volatile long resumedHandoff;
}

/**
 * 恢复轮次与休眠标记之间的填充。
 */
abstract class SettlementLaneWorkerMidPadding3 extends SettlementLaneWorkerHandoffResumed {
    /**
     * p30：恢复轮次与休眠字段之间的填充，不保存业务值。
     * p31：恢复轮次与休眠字段之间的填充，不保存业务值。
     * p32：恢复轮次与休眠字段之间的填充，不保存业务值。
     * p33：恢复轮次与休眠字段之间的填充，不保存业务值。
     * p34：恢复轮次与休眠字段之间的填充，不保存业务值。
     * p35：恢复轮次与休眠字段之间的填充，不保存业务值。
     * p36：恢复轮次与休眠字段之间的填充，不保存业务值。
     * p37：恢复轮次与休眠字段之间的填充，不保存业务值。
     */
    @SuppressWarnings("unused")
    private long p30, p31, p32, p33, p34, p35, p36, p37;
}

/**
 * 保存账户线程休眠声明，供各生产者决定是否发出唤醒。
 */
abstract class SettlementLaneWorkerParked extends SettlementLaneWorkerMidPadding3 {
    /** 工作线程在 park 前先发布的休眠声明，随后还会重新检查全部相关输入。 */
    protected volatile boolean parked;
}

/**
 * 休眠标记之后的填充，减少与后续字段写入争用。
 */
abstract class SettlementLaneWorkerRhsPadding extends SettlementLaneWorkerParked {
    /**
     * p40：休眠字段之后的填充，不保存业务值。
     * p41：休眠字段之后的填充，不保存业务值。
     * p42：休眠字段之后的填充，不保存业务值。
     * p43：休眠字段之后的填充，不保存业务值。
     * p44：休眠字段之后的填充，不保存业务值。
     * p45：休眠字段之后的填充，不保存业务值。
     * p46：休眠字段之后的填充，不保存业务值。
     * p47：休眠字段之后的填充，不保存业务值。
     */
    @SuppressWarnings("unused")
    private long p40, p41, p42, p43, p44, p45, p46, p47;
}

/**
 * 启动与账户执行步骤：为一个账户 Lane 建立实际工作线程，合并单 Owner 控制邮箱、独立准入邮箱及各 Matcher 分片独占生产的结算环。账户所有权交接时暂停业务消费；环槽复用与任务自身完成分别管理。
 */
@Slf4j
public final class SettlementLaneWorker extends SettlementLaneWorkerRhsPadding implements AutoCloseable {
    /** 普通队首等输入时由自旋切换到 yield 的次数阈值，固定为 1024。 */
    private static final int BUSY_SPIN_YIELD_THRESHOLD = 1024;

    /**
     * 可投递到账户线程的任务契约；任务先声明输入就绪，再在账户实际拥有者线程执行。
     */
    public interface Command {
        /**
         * 在本工作线程拥有账户时执行一条已就绪任务，完成交接由任务自身负责。
         * @param lane 任务执行的唯一所属账户 Lane
         */
        void execute(AccountLaneState lane);
        /**
         * 判断普通或准入任务需要的账本输入是否已到齐，默认就绪；已经入队不等于可以执行。
         */
        default boolean accountReady() { return true; }
    }

    /** 账户线程保存故障后调用的发布器，通知运行时并唤醒 Owner。 */
    private final java.util.function.Consumer<Throwable> failurePublisher;
    /** 单 Owner 到本账户线程的普通控制任务引用邮箱。 */
    private final Command[] commands;
    /** 独立的单 Owner 准入邮箱，避免被普通队首尚未就绪的撮合输入挡住。 */
    private final Command[] admissionCommands;
    /** 每个 Matcher 分片独占生产的结算环，由本账户线程合并消费。 */
    private final MatcherSettlementRing[] matcherSettlementRings;
    /** 普通邮箱二次幂容量减一，用于原始游标映射到数组下标。 */
    private final int indexMask;
    /** 独立准入邮箱下标掩码。 */
    private final int admissionIndexMask;
    /** 构造时从 JVM 属性固定的等待策略。 */
    private final SettlementWaitStrategy waitStrategy;
    /** BLOCKING 空闲阶段 park 前自旋预算，构造时限定为 0 至 4096。 */
    private final int spinLimit;
    /** 本工作线程唯一拥有的账户 Lane。 */
    private final AccountLaneState lane;
    /**  构造时启动的实际 LaneThread，始终带有所属账户引用。 借出实际账户工作线程引用，供生命周期及诊断使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final Thread thread;
    /** Owner 已发布普通任务的上界，以 volatile 游标交接可见性。 */
    private final PaddedSequence producerSequence = new PaddedSequence();
    /** 账户已取走普通任务的上界，取走后任务仍可能在执行。 */
    private final PaddedSequence consumerSequence = new PaddedSequence();
    /** 普通邮箱生产者缓存的消费上界，只在接近容量时刷新。 */
    private long cachedConsumerSequence;
    /** Owner 已发布准入任务的上界。 */
    private final PaddedSequence admissionProducerSequence = new PaddedSequence();
    /** 账户已取走准入任务的上界，不代表准入业务已经完成。 */
    private final PaddedSequence admissionConsumerSequence = new PaddedSequence();
    /** 准入生产者缓存的消费上界。 */
    private long cachedAdmissionConsumerSequence;
    /** 线程已完成初始绑定或已发布启动故障的可见标记，供构造方等待。 */
    private volatile boolean started;
    /** 是否继续接纳和正常推进任务，关闭或故障时清为假。 */
    private volatile boolean running = true;
    /**  账户线程已保存的原 Throwable，健康检查及关闭按原类型传播。 读取已经发布的可空账户线程故障。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile Throwable failure;
    /**
     * 创建一个账户工作线程，默认一个 Matcher 结算环，故障回调为空操作，CPU 亲和使用禁用配置。
     * @param role 用于实际线程名的非空角色名称
     * @param lane 任务执行的唯一所属账户 Lane
     * @param requestedCapacity 所需正值邮箱容量，构造时向上取二次幂，溢出报错
     */
    public SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity) {
        this(role, lane, requestedCapacity, /* 默认构造不向外发布故障时忽略的原异常参数 */ ignored -> { });
    }

    /**
     * 使用给定故障发布器创建账户工作线程，默认一个 Matcher 结算环。
     * @param role 用于实际线程名的非空角色名称
     * @param lane 任务执行的唯一所属账户 Lane
     * @param requestedCapacity 所需正值邮箱容量，构造时向上取二次幂，溢出报错
     * @param failurePublisher 必须存在的原故障发布回调
     */
    public SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity,
                         java.util.function.Consumer<Throwable> failurePublisher) {
        this(role, lane, requestedCapacity, failurePublisher, 1);
    }

    /**
     * 按 Matcher 分片数建立结算环，CPU 亲和默认禁用。
     * @param role 用于实际线程名的非空角色名称
     * @param lane 任务执行的唯一所属账户 Lane
     * @param requestedCapacity 所需正值邮箱容量，构造时向上取二次幂，溢出报错
     * @param failurePublisher 必须存在的原故障发布回调
     * @param matcherShardCount Matcher 分片正数量，每片建立一个独占生产的结算环
     */
    public SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity,
                         java.util.function.Consumer<Throwable> failurePublisher,
                         int matcherShardCount) {
        this(role, lane, requestedCapacity, failurePublisher, matcherShardCount,
                TradingCpuAffinity.disabled());
    }

    /**
     * 校验输入，将邮箱容量向上取二次幂，读取等待配置，建立各来源邮箱并绑定 LaneThread 后立即启动；等待线程完成所有权绑定或发布启动故障，再把原故障传播给创建方。
     * @param role 用于实际线程名的非空角色名称
     * @param lane 任务执行的唯一所属账户 Lane
     * @param requestedCapacity 所需正值邮箱容量，构造时向上取二次幂，溢出报错
     * @param failurePublisher 必须存在的原故障发布回调
     * @param matcherShardCount Matcher 分片正数量，每片建立一个独占生产的结算环
     * @param affinity 工作线程使用的 CPU 亲和配置
     */
    public SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity,
                         java.util.function.Consumer<Throwable> failurePublisher,
                         int matcherShardCount, TradingCpuAffinity affinity) {
        this(role, lane, requestedCapacity, failurePublisher, matcherShardCount, affinity,
                CoreRuntimeConfiguration.standalone());
    }

    public SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity,
            java.util.function.Consumer<Throwable> failurePublisher, int matcherShardCount,
            TradingCpuAffinity affinity, CoreRuntimeConfiguration configuration) {
        this.failurePublisher = java.util.Objects.requireNonNull(failurePublisher);
        if (role == null || role.isBlank() || lane == null || requestedCapacity <= 0
                || matcherShardCount <= 0) {
            throw new IllegalArgumentException("invalid settlement lane worker");
        }
        // 向上取整后的二次幂物理邮箱容量，各来源邮箱分别使用
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        commands = new Command[capacity];
        admissionCommands = new Command[capacity];
        matcherSettlementRings = new MatcherSettlementRing[matcherShardCount];
        for (/* 正在创建结算环的 Matcher 分片下标 */ int shard = 0; shard < matcherShardCount; shard++)
            matcherSettlementRings[shard] = new MatcherSettlementRing(capacity);
        indexMask = capacity - 1;
        admissionIndexMask = capacity - 1;
        waitStrategy = configuration.settlementWaitStrategy();
        spinLimit = configuration.settlementSpinLimit();
        this.lane = lane;
        thread = new LaneThread("core-" + role + "-lane-" + lane.laneId(), () -> run(affinity), lane);
        lane.bindWorker(thread);
        thread.start();
        while (!started && failure == null) Thread.onSpinWait();
        rethrowFailure();
    }

    /** 复用的普通控制任务，只释放账户所有权并发布交接完成轮次，不修改资金。 */
    private final Command handoffCommand = /* 交接命令实际所属账户，只释放所有权并发布完成轮次 */ lane -> {
        lane.releaseOwnerForHandoff();
        completedHandoff = requestedHandoff;
    };

    /**
     * 先发布请求轮次，再把复用交接命令放入普通邮箱；每轮只请求一次由调用方保证，入队失败不自动撤销已写轮次。
     * @param epoch 外层协议管理的本轮所有权交接身份
     */
    public void requestHandoff(long epoch) {
        requestedHandoff = epoch;
        submit(handoffCommand);
    }
    /**
     * 只比较 Lane 已发布的交接轮次，不阻塞等待或重新绑定账户。
     * @param epoch 外层协议管理的本轮所有权交接身份
     */
    public boolean handoffReady(long epoch) { return completedHandoff == epoch; }
    /**
     * Owner 归还账户后发布恢复轮次，并按休眠声明唤醒工作线程。
     * @param epoch 外层协议管理的本轮所有权交接身份
     */
    public void resumeHandoff(long epoch) {
        resumedHandoff = epoch;
        unparkIfParked();
    }

    /**
     * 读取工作线程是否已声明准备休眠；这个标记本身不证明线程此刻仍停在 park。
     */
    public boolean isParked() { return parked; }

    /**
     * 单 Owner 向普通邮箱发布任务，检查故障、关闭、物理容量及总采样深度；先放引用再发布游标并按需唤醒，返回一开始计数的入队序号，不表示任务完成。
     * @param command 要发布、检查或执行的非空合法账户任务
     */
    public long submit(Command command) {
        if (command == null) throw new IllegalArgumentException("settlement command is required");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        // 生产者本次准备写入的零起始位置
        long next = producerSequence.value;
        if (next - cachedConsumerSequence >= commands.length) {
            cachedConsumerSequence = consumerSequence.value;
            if (next - cachedConsumerSequence >= commands.length) {
                throw new RejectedExecutionException("settlement lane queue is full");
            }
        }
        if (depth() >= commands.length) {
            throw new RejectedExecutionException("settlement lane queue is full");
        }
        commands[(int) next & indexMask] = command;
        producerSequence.value = next + 1;
        unparkIfParked();
        return next + 1;
    }

    /**
     * 单 Owner 向独立准入邮箱发布任务，只重新检查该物理邮箱容量；总预算由上层预检查承担，先发布引用和游标再唤醒。
     * @param command 要发布、检查或执行的非空合法账户任务
     */
    public long submitAdmission(Command command) {
        if (command == null) throw new IllegalArgumentException("admission command is required");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        // 生产者本次准备写入的零起始位置
        long next = admissionProducerSequence.value;
        if (next - cachedAdmissionConsumerSequence >= admissionCommands.length) {
            cachedAdmissionConsumerSequence = admissionConsumerSequence.value;
            if (next - cachedAdmissionConsumerSequence >= admissionCommands.length) {
                throw new RejectedExecutionException("settlement lane admission queue is full");
            }
        }
        admissionCommands[(int) next & admissionIndexMask] = command;
        admissionProducerSequence.value = next + 1;
        unparkIfParked();
        return next + 1;
    }

    /**
     * 每个分片仅由其 Matcher 生产者发布已经就绪的结算事件；环满时自旋并检查故障和关闭，未另设超时或中断退出，成功后按需唤醒账户线程。
     * @param matcherShard 本事件生产者唯一所属的 Matcher 分片下标
     * @param event 已经准备就绪并在消费结束前保持有效的结算事件
     */
    public long publishMatcherSettlement(int matcherShard, MatcherSettlementEvent event) {
        if (event == null || !event.readyForLane()
                || matcherShard < 0 || matcherShard >= matcherSettlementRings.length)
            throw new IllegalArgumentException("invalid Matcher settlement publication");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        // 当前检查或消费的 Matcher 独占生产环
        MatcherSettlementRing ring = matcherSettlementRings[matcherShard];
        while (ring.isFull()) {
            rethrowFailure();
            if (!running) throw new RejectedExecutionException("settlement lane is closed");
            Thread.onSpinWait();
        }
        // 分片环返回的零起始发布位置，外层返回值再加一
        long position = ring.publish(event);
        unparkIfParked();
        return position + 1;
    }

    /**
     * 只在工作线程已经声明休眠时发放唤醒许可，配合其休眠前再次检查输入。
     */
    private void unparkIfParked() {
        if (parked) {
            java.util.concurrent.locks.LockSupport.unpark(thread);
        }
    }

    /**
     * 汇总普通、准入及各 Matcher 邮箱的瞬时排队深度；不含已经取走但尚在执行的任务，也不是所有游标的一致快照。
     */
    public int depth() {
        // 本轮各 Matcher 环采样深度总和
        long direct = 0;
        for (MatcherSettlementRing /* 当前检查或消费的 Matcher 独占生产环 */ ring : matcherSettlementRings) direct += ring.depth();
        return Math.toIntExact((producerSequence.value - consumerSequence.value)
                + (admissionProducerSequence.value - admissionConsumerSequence.value) + direct);
    }

    /**
     * 汇总各物理邮箱容量供观测使用，不修改任一邮箱的接纳规则。
     */
    public int metricsQueueCapacity() {
        // 本轮累计的各物理邮箱观测容量，不改变接纳门禁
        int capacity = Math.addExact(commands.length, admissionCommands.length);
        for (MatcherSettlementRing /* 当前检查或消费的 Matcher 独占生产环 */ ring : matcherSettlementRings) capacity = Math.addExact(capacity, ring.events.length);
        return capacity;
    }

    /**
     * 只返回运行、无故障且总采样深度小于普通邮箱容量的判断，不抛出已存故障或预留位置。
     */
    public boolean hasCapacity() {
        return running && failure == null && depth() < commands.length;
    }

    /**
     * 先检查运行与故障及准入物理容量，再采样总深度；符合准入预算时返回深度，否则返回负一，不预留队列位置。
     */
    public int admissionDepthIfAvailable() {
        if (!running || failure != null) return -1;
        // 生产者本次准备写入的零起始位置
        long next = admissionProducerSequence.value;
        if (next - cachedAdmissionConsumerSequence >= admissionCommands.length) {
            cachedAdmissionConsumerSequence = admissionConsumerSequence.value;
            if (next - cachedAdmissionConsumerSequence >= admissionCommands.length) {
                return -1;
            }
        }
        // 本轮普通、准入及直接结算总采样深度
        int currentDepth = depth();
        return currentDepth < admissionCommands.length ? currentDepth : -1;
    }

    /**
     * 有故障时按原类型继续抛出，不消费任务或完成结果。
     */
    public void assertHealthy() {
        rethrowFailure();
    }

    /**
     * 账户线程主循环：绑定亲和与所有权，先处理交接暂停，再优先推进就绪准入，按主队首序号合并 Matcher 邮件并执行普通任务。取到任务后先释放邮箱槽，真实完成由原事件发布；故障保存原因并通知运行时，退出时释放账户所有权。
     * @param configuration 本次线程取得账户 CPU 亲和租约所用配置
     */
    private void run(TradingCpuAffinity configuration) {
        // 账户循环当前尚未取走的普通邮箱位置
        long next = consumerSequence.value;
        // 账户已观察到的普通邮箱发布上界，耗尽时才刷新
        long cachedProducerSeq = producerSequence.value;
        // 账户当前尚未取走的独立准入邮箱位置
        long admissionNext = admissionConsumerSequence.value;
        // 账户已见准入发布上界，耗尽时刷新
        long cachedAdmissionProducerSeq = admissionProducerSequence.value;
        // BLOCKING 无工作时已自旋次数，达到配置预算后再 park
        int idleSpins = 0;
        try (/* 实际工作线程取得的 CPU 亲和租约，线程退出时关闭 */ var affinity = configuration.accountLane(lane.laneId())) {
            lane.bindOwner();
            started = true;
            // 本线程已经重新绑定过的最后交接轮次，防止重复绑定
            long reboundHandoff = 0;
            // 普通队首输入尚未就绪的 BUSY_SPIN 退避计数
            int payloadWaitSpins = 0;
            while (running || next < (cachedProducerSeq = producerSequence.value)
                    || admissionNext < (cachedAdmissionProducerSeq = admissionProducerSequence.value)
                    || hasMatcherSettlementWork()) {
                if (completedHandoff > reboundHandoff) {
                    if (resumedHandoff < completedHandoff) {
                        if (!running) break;
                        switch (waitStrategy) {
                            case BUSY_SPIN -> Thread.onSpinWait();
                            case YIELDING -> Thread.yield();
                            case BLOCKING -> parkUntilWork(true);
                        }
                        continue;
                    }
                    lane.bindOwner();
                    reboundHandoff = completedHandoff;
                }
                // 先推进独立准入邮箱。普通队首尚在等 Matcher 或账本输入时，
                // 不能因此停止检查能够完成后续准入的消息。
                if (admissionNext >= cachedAdmissionProducerSeq) {
                    cachedAdmissionProducerSeq = admissionProducerSequence.value;
                }
                if (admissionNext < cachedAdmissionProducerSeq) {
                    // 当前准入消费位置对应的数组槽下标
                    int admissionIndex = (int) admissionNext & admissionIndexMask;
                    // 准入队首引用，输入就绪后先取走再执行
                    Command admission = admissionCommands[admissionIndex];
                    if (admission == null) throw new IllegalStateException("admission publication gap");
                    if (ready(admission)) {
                        admissionCommands[admissionIndex] = null;
                        admissionNext++;
                        admissionConsumerSequence.value = admissionNext;
                        execute(admission);
                        idleSpins = 0;
                        payloadWaitSpins = 0;
                        continue;
                    }
                }
                // 所有权交接是访问边界。交出账户后、Owner 归还之前，
                // 工作线程不能继续消费 Matcher 环并修改账户。
                if (next >= cachedProducerSeq) {
                    cachedProducerSeq = producerSequence.value;
                }
                // 本轮选择的可空直接结算事件
                MatcherSettlementEvent direct = peekMatcherSettlement();
                // 本轮可空普通队首，与直接结算比较优先级
                Command main = next < cachedProducerSeq ? commands[(int) next & indexMask] : null;
                if (direct != null && shouldRunMatcherSettlement(direct, main)) {
                    // Matcher 环只发布已经满足 Lane 就绪条件的事实。
                    // 该邮箱不保存等待载荷的阶段，发布入口先核对一次约定。
                    pollMatcherSettlement(direct);
                    execute(direct);
                    idleSpins = 0;
                    payloadWaitSpins = 0;
                    continue;
                }
                if (next < cachedProducerSeq) {
                    // 当前原始游标通过掩码换算出的物理数组槽
                    int index = (int) next & indexMask;
                    // 本轮保存到局部的普通队首，释放数组槽后再执行
                    Command command = commands[index];
                    if (command == null) throw new IllegalStateException("settlement lane publication gap");
                    if (ready(command)) {
                        // 任务引用已经保存在局部，先清槽并推进消费位置，再执行任务。
                        // 队列深度因此只表示仍在排队的工作，业务完成仍等原事件通知。
                        commands[index] = null;
                        next++;
                        consumerSequence.value = next;
                        execute(command);
                        idleSpins = 0;
                        payloadWaitSpins = 0;
                        continue;
                    }
                    if (!running) throw new IllegalStateException("Lane stopped before its matcher payload arrived");

                    // 普通队首合法但输入尚未就绪，需要让出 CPU 给能够补齐输入的线程。
                    // BUSY_SPIN 先短暂自旋，达到阈值后调用 yield；本分支并不 park。
                    if (waitStrategy == SettlementWaitStrategy.BUSY_SPIN) {
                        backoffPayloadWait(payloadWaitSpins++);
                        continue;
                    }
                }
                switch (waitStrategy) {
                    case BUSY_SPIN -> Thread.onSpinWait();
                    case YIELDING -> Thread.yield();
                    case BLOCKING -> {
                        if (idleSpins < spinLimit) {
                            idleSpins++;
                            Thread.onSpinWait();
                        } else {
                            parkUntilWork(false);
                        }
                    }
                }
            }
        } catch (/* 本账户线程实际保存或传播的原故障 */ Throwable laneFailure) {
            failure = laneFailure;
            running = false;
            started = true;
            try { failurePublisher.accept(laneFailure); }
            catch (/* 故障回调本身抛出的异常，不覆盖原故障而是附加其中 */ Throwable reportingFailure) {
                if (reportingFailure != laneFailure) laneFailure.addSuppressed(reportingFailure);
            }
        } finally {
            lane.releaseOwnerForHandoff();
        }
    }

    /**
     * 先声明休眠，再检查关闭、准入、交接恢复或可执行主任务，仍无工作才最多 park 十万纳秒，finally 清声明；这里只探测，不在 Owner 持权期间执行账户任务。
     * @param waitingForHandoff 当前是否正在等 Owner 归还账户所有权
     */
    private void parkUntilWork(boolean waitingForHandoff) {
        parked = true;
        try {
            if (!running) return;
            if (admissionConsumerSequence.value < admissionProducerSequence.value) {
                // 准入队首引用，输入就绪后先取走再执行
                Command admission = admissionCommands[(int) admissionConsumerSequence.value & admissionIndexMask];
                if (admission != null && ready(admission)) return;
            }
            if (waitingForHandoff) {
                if (resumedHandoff >= completedHandoff) return;
            } else {
                // 本轮可空普通队首，与直接结算比较优先级
                Command main = consumerSequence.value < producerSequence.value
                        ? commands[(int) consumerSequence.value & indexMask] : null;
                // 本轮选择的可空直接结算事件
                MatcherSettlementEvent direct = peekMatcherSettlement();
                if (direct != null && shouldRunMatcherSettlement(direct, main)) {
                    return;
                } else if (main != null && ready(main)) {
                    return;
                }
            }
            LockSupport.parkNanos(this, 100_000L);
        } finally {
            parked = false;
        }
    }

    /**
     * BUSY_SPIN 等待普通队首输入时，按累计次数先自旋、达到阈值后让出 CPU；此方法没有 park 调用。
     * @param spins 普通队首等待输入的累计循环次数
     */
    private void backoffPayloadWait(int spins) {
        if (spins < BUSY_SPIN_YIELD_THRESHOLD) {
            Thread.onSpinWait();
        } else {
            Thread.yield();
        }
    }

    /**
     * 在当前已绑定的账户上执行任务，不在此提前发布业务终态。
     * @param command 要发布、检查或执行的非空合法账户任务
     */
    private void execute(Command command) {
        command.execute(lane);
    }

    /**
     * 保留已存 RuntimeException 或 Error 的原对象抛出，其他 Throwable 包装为账户线程故障。
     */
    private void rethrowFailure() {
        // 本账户线程实际保存或传播的原故障
        Throwable laneFailure = failure;
        if (laneFailure instanceof RuntimeException /* 原故障已是 RuntimeException，保留对象直接抛出 */ runtimeFailure) throw runtimeFailure;
        if (laneFailure instanceof Error /* 原故障属于 Error，保留原类型与对象抛出 */ error) throw error;
        if (laneFailure != null) throw new IllegalStateException("account lane failed", laneFailure);
    }

    /**
     * 撮合事件使用自身 Lane 就绪条件，其他合法任务使用 accountReady；调用方保证对象非空且类型正确。
     * @param command 要发布、检查或执行的非空合法账户任务
     */
    private static boolean ready(Object command) {
        return command instanceof MatcherSettlementEvent /* 已按具体任务类型匹配到的事件，读取其就绪条件或 Core 序号 */ event ? event.readyForLane()
                : ((Command) command).accountReady();
    }

    /**
     * 检查原故障后，只判断独立准入物理邮箱是否仍有位置，不检查总深度预算。
     */
    public boolean hasAdmissionCapacity() {
        rethrowFailure();
        return running && admissionProducerSequence.value - admissionConsumerSequence.value < admissionCommands.length;
    }

    /**
     * 在每个 Matcher 环队首中选 Core 序号最小的一条；序号相同保留先遇到的分片，不取走事件。
     */
    private MatcherSettlementEvent peekMatcherSettlement() {
        // 当前选中的最早分片队首，尚未从环取走
        MatcherSettlementEvent selected = null;
        // 当前候选的最小 Core 序号，初始为 long 最大值
        long selectedSequence = Long.MAX_VALUE;
        for (MatcherSettlementRing /* 当前检查或消费的 Matcher 独占生产环 */ ring : matcherSettlementRings) {
            // 当前分片可空队首，非空才参与序号比较
            MatcherSettlementEvent candidate = ring.peek();
            if (candidate == null) continue;
            // 当前候选直接结算的 Core 序号
            long sequence = candidate.coreSequence();
            if (selected == null || sequence < selectedSequence) {
                selected = candidate;
                selectedSequence = sequence;
            }
        }
        return selected;
    }

    /**
     * 判断是否有任一 Matcher 邮箱深度非零，不消费事件。
     */
    private boolean hasMatcherSettlementWork() {
        for (MatcherSettlementRing /* 当前检查或消费的 Matcher 独占生产环 */ ring : matcherSettlementRings) {
            if (ring.depth() != 0) return true;
        }
        return false;
    }

    /**
     * 按对象身份找到此前选中的分片队首并释放其槽，找不到视为发布协议错误。
     * @param expected 此前从各分片环选中的原事件对象
     */
    private void pollMatcherSettlement(MatcherSettlementEvent expected) {
        for (MatcherSettlementRing /* 当前检查或消费的 Matcher 独占生产环 */ ring : matcherSettlementRings) {
            if (ring.peek() == expected) {
                ring.poll();
                return;
            }
        }
        throw new IllegalStateException("Matcher settlement publication disappeared");
    }

    /**
     * 主邮箱空时可处理直接结算；无序号控制任务优先，未就绪主队首也挡住直接结算，其他情况仅让严格更早的直接结算先执行，同序号仍由主队首优先。
     * @param direct 各分片队首选出的最早直接结算事件
     * @param main 普通邮箱当前可空队首，用它决定控制优先与业务顺序
     */
    private static boolean shouldRunMatcherSettlement(MatcherSettlementEvent direct, Command main) {
        if (main == null) return true;
        // 普通队首已识别的 Core 序号，零表示无序号控制任务
        long mainSequence = commandSequence(main);
        // 交接、指标等无 Core 序号的控制任务保持在直接业务邮件之前。
        if (mainSequence == 0) return false;
        if (!ready(main)) return false;
        return direct.coreSequence() < mainSequence;
    }

    /**
     * 从已知撮合、撤单、提交、批量准入及改单任务取 Core 序号，其他控制任务返回零。
     * @param command 要发布、检查或执行的非空合法账户任务
     */
    private static long commandSequence(Command command) {
        if (command instanceof MatcherSettlementEvent /* 已按具体任务类型匹配到的事件，读取其就绪条件或 Core 序号 */ event) return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.order.state.LaneCancelEvent /* 已按具体任务类型匹配到的事件，读取其就绪条件或 Core 序号 */ event)
            return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.core.change.LaneCommitEvent /* 已按具体任务类型匹配到的事件，读取其就绪条件或 Core 序号 */ event)
            return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent /* 已按具体任务类型匹配到的事件，读取其就绪条件或 Core 序号 */ event)
            return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent /* 已按具体任务类型匹配到的事件，读取其就绪条件或 Core 序号 */ event)
            return event.coreSequence();
        return 0;
    }

    /**
     * 清运行标记后最多等待五秒，每次 join 十毫秒，记录并最终恢复调用者中断；仍存活则报错，否则传播线程原故障。此处不强制中断工作线程，也不承诺未就绪任务全部完成。
     */
    @Override
    public void close() {
        running = false;
        // 关闭等待期间调用线程是否被中断，最后恢复其中断标记
        boolean interrupted = false;
        // 关闭等待五秒的单调纳秒截止时间
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                thread.join(10);
            } catch (/* 调用者 join 等待被中断，先记录并继续等待，最后恢复中断标记 */ InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new IllegalStateException("settlement lane did not stop");
        rethrowFailure();
    }

    /**
     * 邮箱游标之前的填充，隔开生产者和消费者的频繁写入。
     */
    private static class PaddedSequenceLhsPadding {
        /**
         * p00：邮箱位置之前的填充，不保存业务值。
         * p01：邮箱位置之前的填充，不保存业务值。
         * p02：邮箱位置之前的填充，不保存业务值。
         * p03：邮箱位置之前的填充，不保存业务值。
         * p04：邮箱位置之前的填充，不保存业务值。
         * p05：邮箱位置之前的填充，不保存业务值。
         * p06：邮箱位置之前的填充，不保存业务值。
         * p07：邮箱位置之前的填充，不保存业务值。
         * p08：邮箱位置之前的填充，不保存业务值。
         * p09：邮箱位置之前的填充，不保存业务值。
         * p0a：邮箱位置之前的填充，不保存业务值。
         * p0b：邮箱位置之前的填充，不保存业务值。
         * p0c：邮箱位置之前的填充，不保存业务值。
         * p0d：邮箱位置之前的填充，不保存业务值。
         * p0e：邮箱位置之前的填充，不保存业务值。
         */
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    /**
     * 保存邮箱跨线程可见的单调位置值。
     */
    private static class PaddedSequenceValue extends PaddedSequenceLhsPadding {
        /** 该邮箱已发布或已消费的位置，volatile 写入向另一方交接可见性。 */
        protected volatile long value;
    }

    /**
     * 邮箱位置之后继续填充的完整游标槽。
     */
    private static final class PaddedSequence extends PaddedSequenceValue {
        /**
         * p10：邮箱位置之后的填充，不保存业务值。
         * p11：邮箱位置之后的填充，不保存业务值。
         * p12：邮箱位置之后的填充，不保存业务值。
         * p13：邮箱位置之后的填充，不保存业务值。
         * p14：邮箱位置之后的填充，不保存业务值。
         * p15：邮箱位置之后的填充，不保存业务值。
         * p16：邮箱位置之后的填充，不保存业务值。
         * p17：邮箱位置之后的填充，不保存业务值。
         * p18：邮箱位置之后的填充，不保存业务值。
         * p19：邮箱位置之后的填充，不保存业务值。
         * p1a：邮箱位置之后的填充，不保存业务值。
         * p1b：邮箱位置之后的填充，不保存业务值。
         * p1c：邮箱位置之后的填充，不保存业务值。
         * p1d：邮箱位置之后的填充，不保存业务值。
         * p1e：邮箱位置之后的填充，不保存业务值。
         */
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }

    /**
     * Matcher 环生产位置之前的填充。
     */
    private static class MatcherSettlementRingLhsPadding {
        /**
         * p00：Matcher 环生产位置之前的填充，不保存业务值。
         * p01：Matcher 环生产位置之前的填充，不保存业务值。
         * p02：Matcher 环生产位置之前的填充，不保存业务值。
         * p03：Matcher 环生产位置之前的填充，不保存业务值。
         * p04：Matcher 环生产位置之前的填充，不保存业务值。
         * p05：Matcher 环生产位置之前的填充，不保存业务值。
         * p06：Matcher 环生产位置之前的填充，不保存业务值。
         * p07：Matcher 环生产位置之前的填充，不保存业务值。
         * p08：Matcher 环生产位置之前的填充，不保存业务值。
         * p09：Matcher 环生产位置之前的填充，不保存业务值。
         * p0a：Matcher 环生产位置之前的填充，不保存业务值。
         * p0b：Matcher 环生产位置之前的填充，不保存业务值。
         * p0c：Matcher 环生产位置之前的填充，不保存业务值。
         * p0d：Matcher 环生产位置之前的填充，不保存业务值。
         * p0e：Matcher 环生产位置之前的填充，不保存业务值。
         */
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    /**
     * 单 Matcher 生产者的位置和它缓存的账户消费上界。
     */
    private static class MatcherSettlementRingProducerPosition extends MatcherSettlementRingLhsPadding {
        /** 本分片环已发布位置，单 Matcher 写入并向账户线程可见。 */
        protected volatile long producerPosition;
        /** Matcher 缓存的账户消费上界，判满时按需刷新。 */
        protected long cachedConsumerPosition;
    }

    /**
     * Matcher 环生产位置与账户消费位置之间的填充。
     */
    private static class MatcherSettlementRingMidPadding extends MatcherSettlementRingProducerPosition {
        /**
         * p10：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p11：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p12：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p13：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p14：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p15：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p16：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p17：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p18：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p19：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p1a：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p1b：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p1c：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p1d：Matcher 环生产与消费位置之间的填充，不保存业务值。
         * p1e：Matcher 环生产与消费位置之间的填充，不保存业务值。
         */
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }

    /**
     * 账户消费者的位置和它缓存的 Matcher 发布上界。
     */
    private static class MatcherSettlementRingConsumerPosition extends MatcherSettlementRingMidPadding {
        /** 账户已取走的环位置，清引用后用 release 发布。 */
        protected volatile long consumerPosition;
        /** 账户缓存的 Matcher 发布上界，队首到达上界时刷新。 */
        protected long cachedProducerPosition;
    }

    /**
     * Matcher 环消费位置之后的填充。
     */
    private static class MatcherSettlementRingRhsPadding extends MatcherSettlementRingConsumerPosition {
        /**
         * p20：Matcher 环消费位置之后的填充，不保存业务值。
         * p21：Matcher 环消费位置之后的填充，不保存业务值。
         * p22：Matcher 环消费位置之后的填充，不保存业务值。
         * p23：Matcher 环消费位置之后的填充，不保存业务值。
         * p24：Matcher 环消费位置之后的填充，不保存业务值。
         * p25：Matcher 环消费位置之后的填充，不保存业务值。
         * p26：Matcher 环消费位置之后的填充，不保存业务值。
         * p27：Matcher 环消费位置之后的填充，不保存业务值。
         * p28：Matcher 环消费位置之后的填充，不保存业务值。
         * p29：Matcher 环消费位置之后的填充，不保存业务值。
         * p2a：Matcher 环消费位置之后的填充，不保存业务值。
         * p2b：Matcher 环消费位置之后的填充，不保存业务值。
         * p2c：Matcher 环消费位置之后的填充，不保存业务值。
         * p2d：Matcher 环消费位置之后的填充，不保存业务值。
         * p2e：Matcher 环消费位置之后的填充，不保存业务值。
         */
        @SuppressWarnings("unused")
        private long p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p2a, p2b, p2c, p2d, p2e;
    }

    /**
     * 一个 Matcher 到一个账户 Lane 的固定引用环，双方各自推进游标；环槽释放与业务事件完成分别管理。
     */
    private static final class MatcherSettlementRing extends MatcherSettlementRingRhsPadding {
        /** 消费位置字段访问器，归还环槽时执行 release 写入。 */
        private static final java.lang.invoke.VarHandle CONSUMER_POSITION_VH;
        /** 初始化消费位置访问器，失败时停止该环类型初始化。 */
        static {
            try {
                CONSUMER_POSITION_VH = java.lang.invoke.MethodHandles.lookup()
                        .findVarHandle(MatcherSettlementRingConsumerPosition.class, "consumerPosition", long.class);
            } catch (/* 消费位置访问器初始化失败，阻止环类型继续初始化 */ ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /** 固定的结算事件引用数组，事件对象生命周期独立于环槽。 */
        private final MatcherSettlementEvent[] events;
        /** 外层保证为二次幂的环容量减一。 */
        private final int mask;

        /**
         * 按外层已经核对的二次幂容量建立固定事件引用环与下标掩码。
         * @param capacity 外层已保证合法的二次幂环容量
         */
        MatcherSettlementRing(int capacity) {
            events = new MatcherSettlementEvent[capacity];
            mask = capacity - 1;
        }

        /**
         * 生产者先用缓存的消费水位判满，必要时读取最新消费位置，不预留槽。
         */
        boolean isFull() {
            // 本环当前零起始生产或消费位置，具体写入权归对应一方
            long position = producerPosition;
            if (position - cachedConsumerPosition >= events.length) {
                cachedConsumerPosition = consumerPosition;
                return position - cachedConsumerPosition >= events.length;
            }
            return false;
        }

        /**
         * 单 Matcher 生产者检查容量及槽已释放，先放入事件再发布生产位置，返回零起始发布位置。
         * @param event 已经准备就绪并在消费结束前保持有效的结算事件
         */
        long publish(MatcherSettlementEvent event) {
            // 本环当前零起始生产或消费位置，具体写入权归对应一方
            long position = producerPosition;
            if (position - cachedConsumerPosition >= events.length) {
                cachedConsumerPosition = consumerPosition;
                if (position - cachedConsumerPosition >= events.length) {
                    throw new RejectedExecutionException("Matcher settlement ring is full");
                }
            }
            // 当前原始游标通过掩码换算出的物理数组槽
            int index = (int) position & mask;
            if (events[index] != null) {
                throw new IllegalStateException("Matcher settlement ring slot was not released");
            }
            events[index] = event;
            producerPosition = position + 1;
            return position;
        }

        /**
         * 账户消费者优先使用已见生产上界，必要时刷新后借出队首引用；没有发布项返回空。
         */
        MatcherSettlementEvent peek() {
            // 本环当前零起始生产或消费位置，具体写入权归对应一方
            long position = consumerPosition;
            if (position >= cachedProducerPosition) {
                cachedProducerPosition = producerPosition;
                if (position >= cachedProducerPosition) {
                    return null;
                }
            }
            return events[(int) position & mask];
        }

        /**
         * 账户消费者核对队首存在，清引用后以 release 推进消费位置，使生产者可以复用原环槽。
         */
        void poll() {
            // 本环当前零起始生产或消费位置，具体写入权归对应一方
            long position = consumerPosition;
            // 当前原始游标通过掩码换算出的物理数组槽
            int index = (int) position & mask;
            if (position >= cachedProducerPosition) {
                cachedProducerPosition = producerPosition;
                if (position >= cachedProducerPosition) {
                    throw new IllegalStateException("Matcher settlement ring is empty");
                }
            }
            if (events[index] == null) {
                throw new IllegalStateException("Matcher settlement ring is empty");
            }
            events[index] = null;
            CONSUMER_POSITION_VH.setRelease(this, position + 1);
        }

        /**
         * 采样分片生产与消费位置差，并把瞬时负差截为零，仅用于排队观测。
         */
        long depth() {
            return Math.max(0L, producerPosition - consumerPosition);
        }
    }
}
