package com.surprising.aeron.service.matcher;

import com.surprising.aeron.service.config.TradingCpuAffinity;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.orchestration.trade.CommandSlot;
import com.surprising.aeron.service.state.settlement.MatcherSettlementEvent;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

/**
 * 一个 Matcher 分片的串行执行与完成交接。队列模式由单一 Owner 提交、独立 MatcherThread 执行、Owner 按本分片顺序回收传输槽；融合模式直接由当前交易线程执行，只保留一个普通完成槽。实际成交事实由结算事件或 CommandSlot 持有，传输槽不复制整份交易状态；线程所有权与对象使用期限由调用链遵守。
 */
@Slf4j
public final class MatcherCommandPipeline implements AutoCloseable {
    /** 配置独立 Matcher 空闲等待策略的系统属性名。 */
    private static final String WAIT_STRATEGY_PROPERTY =
            "surprising.aeron.matcher-pipeline-wait-strategy";
    /** Owner 等启动或控制结果时先自旋的次数。 */
    private static final int IDLE_SPINS = 1_024;
    /** Owner 超过自旋次数后的单次短暂等待纳秒上限。 */
    private static final long IDLE_PARK_NANOS = 1_000L;
    /** 自适应 Matcher 在空闲时先自旋的次数。 */
    private static final int WORKER_IDLE_SPINS = 64;
    /** 自适应 Matcher 一次空闲 park 的纳秒上限。 */
    private static final long WORKER_IDLE_PARK_NANOS = 100_000L;

    /** 对唤醒请求标记执行比较交换的 VarHandle，配合队列发布避免遗漏唤醒。 */
    private static final java.lang.invoke.VarHandle PARK_REQUESTED;
    // 初始化请求标记的原子访问句柄，字段不匹配时让类初始化明确失败。
    static {
        try {
            PARK_REQUESTED = java.lang.invoke.MethodHandles.lookup()
                    .findVarHandle(Wakeup.class, "requested", long.class);
        } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 队列模式固定容量的传输槽，构造内容后通过提交/完成游标交接。 */
    private Slot[] slots;
    /** 配置的二次幂队列容量，融合模式不据此分配传输环。 */
    private final int requestedCapacity;
    /** 融合模式唯一待收集的普通结果槽，直达结算不占用它。 */
    private Slot inlineCompletion;
    /** 融合模式本线程已成功执行的提交计数。 */
    private long inlineSubmitted;
    /** 容量减一，用于把递增位置映射为环形下标。 */
    private final int mask;
    /** 构造时读取的独立 worker 空闲等待策略。 */
    private final WaitStrategy waitStrategy;
    /** 队列模式的 Matcher 工作线程，启动后不清空，禁止重启。 */
    private volatile Thread worker;
    /** 融合模式唯一拥有线程，在该线程同步执行所有 Matcher 动作。 */
    private Thread inlineOwner;
    /** 独立 worker 开始接收命令前执行的分片激活动作。 */
    private Runnable startupAction;
    /** Owner 写、Matcher 读的已发布提交位置。 */
    private final PaddedSequence submittedPosition = new PaddedSequence();
    /** Owner 写的已回收位置，发布方用来判断窗口是否满。 */
    private final PaddedSequence consumedPosition = new PaddedSequence();
    /** Owner 缓存的消费位置，容量检查或峰值更新时刷新。 */
    private long cachedConsumedPosition;
    /** Matcher 写、Owner 读的连续完成位置。 */
    private final PaddedSequence completedPosition = new PaddedSequence();
    /** Owner 缓存的完成位置，减少反复读取共享游标。 */
    private long cachedCompletedPosition;
    /** 发布方与 Matcher 配合设置的可见唤醒请求标记。 */
    private final Wakeup wakeup = new Wakeup();
    /** 是否仍接收新命令，关闭或故障时清为 false，worker 仍按分支处理已提交任务。 */
    private volatile boolean accepting;
    /** 发布侧观测到的提交减回收窗口占用峰值。 */
    private volatile int submissionHighWaterMark;
    /** 工作线程观测到的完成减回收槽位峰值。 */
    private volatile int completionHighWaterMark;
    /** 启动动作已结束的可见标记，成功与失败均会置位。 */
    private volatile boolean started;
    /** 启动动作或线程绑定阶段的可见失败原因。 */
    private volatile Throwable startupFailure;
    /** 完成事实发布或主循环致命失败，Owner 操作时重新抛出。 */
    private volatile Throwable publicationFailure;
    /** Owner 本地控制调用计数，取负后成为独立 token。 */
    private long controlSequence;
    /** 正常退出主循环后由 worker 执行的关闭动作。 */
    private Runnable shutdownAction;
    /** worker 关闭动作的异常，close 等待线程退出后传播。 */
    private volatile Throwable shutdownFailure;
    /** 当前 Matcher 全局分片号，也用于线程名与 CPU 绑定。 */
    private final int workerId;
    /** Owner 单生产者、Matcher 单消费者的有界查询信箱，请求记录提交栅栏而不复制状态。 */
    private OneToOneConcurrentArrayQueue<BackgroundRead<?>> backgroundReads;
    /** Matcher 发布完成或可见故障后的可空唤醒回调。 */
    private volatile Runnable completionSignal;
    /**
     * 安装可空的完成唤醒回调；Matcher 发布完成或发现发布故障时调用，回调不在此方法执行。
     * @param signal 完成或发布故障后执行的可空唤醒动作
     */
    public void completionSignal(Runnable signal) { completionSignal = signal; }
    /**
     * 队列模式把查询和当前已提交位置放入有界单生产者读信箱，Matcher 执行到该位置后再读；融合模式要求当前线程就是拥有者并立即执行。关闭或信箱满返回失败 Future，运行异常转为失败结果，查询不会复制交易状态。
     * @param <T> 读取或控制调用返回值的类型
     * @param read 到达提交栅栏后在 Matcher 拥有线程执行的读取函数
     */
    public <T> java.util.concurrent.CompletableFuture<T> readAtSubmissionFence(Supplier<T> read) {
        if (!accepting) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new RejectedExecutionException("matcher read mailbox unavailable"));
        }
        if (inlineOwner != null) {
            assertInlineOwner();
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(read.get());
            } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ RuntimeException failure) {
                return java.util.concurrent.CompletableFuture.failedFuture(failure);
            }
        }
        // 当前查询自己的完成 Future，信箱满时立即失败。
        var future = new java.util.concurrent.CompletableFuture<T>();
        if (!backgroundReads.offer(new BackgroundRead<>(submittedPosition.value, read, future))) {
            future.completeExceptionally(new RejectedExecutionException("matcher read mailbox unavailable"));
        } else if (waitStrategy != WaitStrategy.BUSY_SPIN && wakeup.requested != 0L
                && PARK_REQUESTED.compareAndSet(wakeup, 1L, 0L)) {
            LockSupport.unpark(worker);
        }
        return future;
    }
    /**
     * 携带提交栅栏和完成 Future 的读取请求，在同分片先前提交执行完后由 Matcher 读取；不是交易状态副本。
     * @param <T> 读取或控制调用返回值的类型
     * @param fence 读取前必须已执行完成的提交位置，不是 Core 业务提交序号
     * @param read 到达提交栅栏后在 Matcher 拥有线程执行的读取函数
     * @param future 当前查询独立的完成 Future
     */
    private record BackgroundRead<T>(long fence,Supplier<T> read,java.util.concurrent.CompletableFuture<T> future) {
        /**
         * 在 Matcher 线程执行读取函数，将值或运行异常写入请求自己的 Future；不捕获 Error，也不取消已执行读取。
         */
        void execute() {
            try {
                future.complete(read.get());
            } catch (/* 当前读取函数抛出的运行异常，只使本次读取 Future 失败。 */ RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        }
    }

    /**
     * 创建分片零的指定容量队列并立即启动独立 Matcher 线程。
     * @param requestedCapacity 正的二次幂队列容量
     */
    public MatcherCommandPipeline(int requestedCapacity) {
        this(0, requestedCapacity, true);
    }

    /**
     * 创建分片零的指定容量流水线，可推迟选择队列或融合启动。
     * @param requestedCapacity 正的二次幂队列容量
     * @param startImmediately 是否在构造时立即启动独立 worker
     */
    public MatcherCommandPipeline(int requestedCapacity, boolean startImmediately) {
        this(0, requestedCapacity, startImmediately);
    }

    /**
     * 校验非负分片号与二次幂容量，读取等待策略并保存环形掩码；需要立即启动时创建队列和独立工作线程。
     * @param workerId 非负 Matcher 分片编号
     * @param requestedCapacity 正的二次幂队列容量
     * @param startImmediately 是否在构造时立即启动独立 worker
     */
    public MatcherCommandPipeline(int workerId, int requestedCapacity, boolean startImmediately) {
        if (workerId < 0) throw new IllegalArgumentException("matcher worker id must be non-negative");
        if (requestedCapacity <= 0 || (requestedCapacity & (requestedCapacity - 1)) != 0) {
            throw new IllegalArgumentException("matcher pipeline capacity must be a power of two");
        }
        this.workerId = workerId;
        this.waitStrategy = configuredWaitStrategy();
        this.requestedCapacity = requestedCapacity;
        mask = requestedCapacity - 1;
        if (startImmediately) start(null);
    }

    /**
     * 绑定当前交易线程并建立单个普通完成槽，执行可选激活动作后才接收请求；不创建环形队列或工作线程。若激活动作抛出，started 已设为 true，不能假定仍可重新启动。
     * @param action 启动或关闭时的可空动作，执行线程见方法说明
     */
    public synchronized void startInline(Runnable action) {
        if (worker != null || started || inlineOwner != null) {
            throw new IllegalStateException("matcher pipeline is already started");
        }
        inlineOwner = Thread.currentThread();
        inlineCompletion = new Slot();
        started = true;
        if (action != null) action.run();
        accepting = true;
    }

    /**
     * 要求当前线程就是融合模式绑定的交易线程，避免同步模式被其他线程调用。
     */
    private void assertInlineOwner() {
        if (inlineOwner != Thread.currentThread()) {
            throw new IllegalStateException("fused matcher is bound to another thread");
        }
    }

    /**
     * 使用禁用 CPU 绑定的配置启动独立 Matcher 线程。
     * @param action 启动或关闭时的可空动作，执行线程见方法说明
     */
    public void start(Runnable action) {
        start(action, TradingCpuAffinity.disabled());
    }

    /**
     * 创建有界读信箱和预分配命令槽，保存激活动作后启动指定分片 MatcherThread，并同步等待启动结果；已有 worker 时直接返回，不允许关闭后重新启动。
     * @param action 启动或关闭时的可空动作，执行线程见方法说明
     * @param affinity Matcher 线程 CPU 绑定配置
     */
    public synchronized void start(Runnable action,
            TradingCpuAffinity affinity) {
        if (worker != null) return;
        if (started || startupFailure != null) {
            throw new IllegalStateException("matcher pipeline cannot be restarted");
        }
        backgroundReads = new OneToOneConcurrentArrayQueue<>(requestedCapacity);
        slots = new Slot[requestedCapacity];
        for (/* 正在预分配的传输槽下标。 */ int index = 0; index < requestedCapacity; index++) slots[index] = new Slot();
        startupAction = action;
        accepting = true;
        // 携带全局分片编号的独立 Matcher 线程。
        MatcherThread matcherWorker = new MatcherThread(() -> run(affinity), "core-matcher-" + workerId, workerId);
        worker = matcherWorker;
        matcherWorker.start();
        awaitStartup();
    }

    /**
     * 提交一条正 Core 序号的普通命令，不附带结算事件。
     * @param coreSequence 普通命令使用的正 Core 序号
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     */
    public void submit(long coreSequence, Supplier<?> command) {
        submit(coreSequence, command, null);
    }

    /**
     * 校验正序号和非空函数，将命令及可选结算事件交给内部发布路径。
     * @param coreSequence 普通命令使用的正 Core 序号
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     * @param settlement 可空的结算事件，直达模式由它保留原生成交事实
     */
    public void submit(long coreSequence, Supplier<?> command,
                MatcherSettlementEvent settlement) {
        if (coreSequence <= 0 || command == null) {
            throw new IllegalArgumentException("matcher pipeline command is invalid");
        }
        submitInternal(coreSequence, command, settlement);
    }

    /**
     * 提交需要直接写回 CommandSlot 的普通结果，要求目标非空且不能同时使用 direct 结算事件；函数非空由内部方法继续检查。
     * @param coreSequence 普通命令使用的正 Core 序号
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     * @param settlement 可空的结算事件，直达模式由它保留原生成交事实
     * @param resultTarget 普通撮合结果直接写回的 CommandSlot
     */
    void submit(long coreSequence, Supplier<?> command,
                MatcherSettlementEvent settlement,
                CommandSlot resultTarget) {
        if (coreSequence <= 0 || resultTarget == null || settlement != null && settlement.direct())
            throw new IllegalArgumentException("invalid command result target");
        submitInternal(coreSequence, command, settlement, resultTarget);
    }

    /**
     * 校验参数后提交一个负 token 控制调用，按限时等待结果并转换类型；超时抛异常但不撤销已提交命令，调用方仍需处理该分片未收集的队头。
     * @param <T> 读取或控制调用返回值的类型
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     * @param timeoutNanos 等待本次控制结果的正纳秒上限，不会取消已提交动作
     */
    public <T> T call(Supplier<T> command, long timeoutNanos) {
        if (command == null || timeoutNanos <= 0) {
            throw new IllegalArgumentException("matcher control call is invalid");
        }
        // 当前控制请求生成的负 token。
        long token = submitControl(command);
        // 当前业务动作返回或队头读取的结果引用。
        Object result = awaitResult(token, timeoutNanos);
        if (result == null) throw new IllegalStateException("matcher control call timed out");
        // 经调用方泛型契约转换的控制结果，运行时不另验证具体类型。
        @SuppressWarnings("unchecked")
        T typed = (T) result;
        return typed;
    }

    /**
     * 以精确递增的本地控制计数生成负 token 并提交，提交与收集分开；提交失败也已占用该计数，控制溢出会抛异常。
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     */
    public long submitControl(Supplier<?> command) {
        controlSequence = Math.incrementExact(controlSequence);
        // 当前控制请求生成的负 token。
        long token = -controlSequence;
        submitInternal(token, command);
        return token;
    }

    /**
     * 只接受负 token，尝试收集本分片队头的控制结果，未完成或不是队头就返回空。
     * @param token 非零传输标识，正数为普通命令，负数为控制调用
     */
    public Object pollControl(long token) {
        if (token >= 0) throw new IllegalArgumentException("matcher control token must be negative");
        return pollResult(token);
    }

    /**
     * 把无结算事件的普通或控制 token 转入内部发布方法。
     * @param token 非零传输标识，正数为普通命令，负数为控制调用
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     */
    private void submitInternal(long token, Supplier<?> command) {
        submitInternal(token, command, null);
    }

    /**
     * 把可选结算事件转入完整发布路径，不指定 CommandSlot 回写目标。
     * @param token 非零传输标识，正数为普通命令，负数为控制调用
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     * @param settlement 可空的结算事件，直达模式由它保留原生成交事实
     */
    private void submitInternal(long token, Supplier<?> command,
                                MatcherSettlementEvent settlement) {
        submitInternal(token, command, settlement, null);
    }

    /**
     * 先拒绝无效输入、发布故障和关闭状态；融合模式直接执行，队列模式由 Owner 回收就绪直达槽、检查窗口容量并填槽，最后用提交游标发布。队满立即拒绝，不覆盖未消费槽，必要时唤醒等待的 Matcher。
     * @param token 非零传输标识，正数为普通命令，负数为控制调用
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     * @param settlement 可空的结算事件，直达模式由它保留原生成交事实
     * @param resultTarget 普通撮合结果直接写回的 CommandSlot
     */
    private void submitInternal(long token, Supplier<?> command,
                                MatcherSettlementEvent settlement,
                                CommandSlot resultTarget) {
        if (token == 0 || command == null) throw new IllegalArgumentException("matcher command is invalid");
        rethrowPublicationFailure();
        if (!accepting) throw new RejectedExecutionException("matcher pipeline is closed");
        if (inlineOwner != null) {
            executeInline(token, command, settlement, resultTarget);
            return;
        }
        skipReadyDirectSlots();
        // 当前传输游标位置，按所在方法区分提交、完成或消费。
        long position = submittedPosition.value;
        if (position - cachedConsumedPosition >= slots.length) {
            cachedConsumedPosition = consumedPosition.value;
            if (position - cachedConsumedPosition >= slots.length) {
                throw new RejectedExecutionException("matcher pipeline is full");
            }
        }
        // 当前游标对应的固定传输槽，发布/消费由游标控制。
        Slot slot = slots[(int) position & mask];
        if (slot.command != null || slot.result != null || slot.failure != null || slot.token != 0) {
            throw new IllegalStateException("matcher pipeline slot was not released");
        }
        slot.token = token;
        slot.command = command;
        slot.settlement = settlement;
        slot.resultTarget = resultTarget;
        slot.directSettlement = settlement != null && settlement.direct();
        submittedPosition.value = position + 1;
        // 当前提交位置与已回收位置之差，用于峰值统计。
        int depth = Math.toIntExact(position + 1 - cachedConsumedPosition);
        if (depth > submissionHighWaterMark) {
            cachedConsumedPosition = consumedPosition.value;
            depth = Math.toIntExact(position + 1 - cachedConsumedPosition);
            if (depth > submissionHighWaterMark) {
                submissionHighWaterMark = depth;
            }
        }
        if (waitStrategy != WaitStrategy.BUSY_SPIN && wakeup.requested != 0L
                && PARK_REQUESTED.compareAndSet(wakeup, 1L, 0L)) {
            LockSupport.unpark(worker);
        }
    }

    /**
     * 在唯一拥有线程同步执行并校验结算结果，直达事件在 begin/complete 之间发布；普通结果补 Core 序号后交给 CommandSlot 或唯一完成槽。异常直接传播并标记事件失败，不经过独立工作线程的故障转交。
     * @param token 非零传输标识，正数为普通命令，负数为控制调用
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     * @param settlement 可空的结算事件，直达模式由它保留原生成交事实
     * @param resultTarget 普通撮合结果直接写回的 CommandSlot
     */
    private void executeInline(long token, Supplier<?> command, MatcherSettlementEvent settlement,
                               CommandSlot resultTarget) {
        assertInlineOwner();
        // 当前游标对应的固定传输槽，发布/消费由游标控制。
        Slot slot = inlineCompletion;
        if (slot.token != 0) throw new IllegalStateException("previous fused matcher result was not collected");
        // 当前结算事件是否走直达事实路径。
        boolean direct = settlement != null && settlement.direct();
        if (direct) settlement.beginMatcherPublication();
        // 当前业务动作返回或队头读取的结果引用。
        Object result;
        try {
            result = command.get();
            if (token > 0 && result instanceof CoreMatchingResult /* 普通 Core 撮合结果，在 Matcher 线程原位补齐 Core 序号。 */ matchingResult)
                result = matchingResult.withCoreSequenceInPlace(token);
            if (settlement != null && !settlement.resultPrepared())
                throw new IllegalStateException("direct matcher did not publish its native result");
        } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ Throwable failure) {
            if (settlement != null) settlement.failDirect(failure);
            // 融合模式没有独立 worker 的完成边界，执行异常在当前线程直接传播。
            if (failure instanceof RuntimeException /* 可直接按原类型传播的运行异常。 */ runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error /* 可直接按原类型传播的 JVM Error。 */ error) throw error;
            throw new IllegalStateException("fused matcher command failed", failure);
        } finally {
            if (direct) settlement.completeMatcherPublication();
        }
        inlineSubmitted++;
        submissionHighWaterMark = 1;
        if (resultTarget != null) {
            resultTarget.publishMatcherResult(workerId, (CoreMatchingResult) result);
            return; // 结果已经交给现有命令上下文，无需保留传输完成槽。
        }
        if (direct) return; // 事件保留原生事实直到 Lane 结算及回收完成，不占普通结果槽。
        if (result == null) throw new IllegalStateException("fused matcher returned no result");
        slot.token = token;
        slot.result = result;
        completionHighWaterMark = 1;
    }

    /**
     * 按正 Core 序号收集队头结果，只允许 CoreMatchingResult，类型不符明确抛异常；未就绪或非队头返回空。
     * @param expectedCoreSequence 准备收集的正 Core 命令序号
     */
    public CoreMatchingResult poll(long expectedCoreSequence) {
        if (expectedCoreSequence <= 0) throw new IllegalArgumentException("matcher sequence must be positive");
        // 当前业务动作返回或队头读取的结果引用。
        Object result = pollResult(expectedCoreSequence);
        if (result == null) return null;
        if (result instanceof CoreMatchingResult /* 普通 Core 撮合结果，在 Matcher 线程原位补齐 Core 序号。 */ matchingResult) return matchingResult;
        throw new IllegalStateException("matcher pipeline returned an invalid matching result: "
                + result.getClass().getName());
    }

    /**
     * 探查本分片队头已完成的正 token，自动回收可跳过的直达槽；未完成或队头属于负 token 控制调用则返回零，不越过控制结果。
     */
    public long completedMatchingSequence() {
        if (inlineOwner != null) {
            assertInlineOwner();
            return Math.max(0, inlineCompletion.token);
        }
        while (true) {
            rethrowPublicationFailure();
            skipReadyDirectSlots();
            // 当前传输游标位置，按所在方法区分提交、完成或消费。
            long position = consumedPosition.value;
            if (position >= cachedCompletedPosition) {
                cachedCompletedPosition = completedPosition.value;
                if (position >= cachedCompletedPosition) return 0;
            }
            // 当前游标对应的固定传输槽，发布/消费由游标控制。
            Slot slot = slots[(int) position & mask];
            // 前面跳过直达槽之后，Matcher 仍可能刚发布新的直达完成。
            // 暴露 token 前重查槽位；直达原生事实由事件和 Lane 消费。
            if (slot.token > 0 && slot.directSettlement) continue;
            return slot.token > 0 ? slot.token : 0;
        }
    }

    /**
     * 只消费匹配预期 token 的已完成队头，清槽并推进 Owner 消费游标后返回结果或抛出命令异常；非队头只是暂不可消费。融合模式读取唯一完成槽，队列模式消费后继续回收就绪直达槽。
     * @param expectedToken 只允许消费的目标队头 token
     */
    private Object pollResult(long expectedToken) {
        rethrowPublicationFailure();
        if (expectedToken == 0) throw new IllegalArgumentException("matcher token must be non-zero");
        if (inlineOwner != null) {
            assertInlineOwner();
            if (inlineCompletion.token != expectedToken) return null;
            // 当前业务动作返回或队头读取的结果引用。
            Object result = inlineCompletion.result;
            inlineCompletion.clear();
            return result;
        }
        // 当前传输游标位置，按所在方法区分提交、完成或消费。
        long position = consumedPosition.value;
        if (position >= cachedCompletedPosition) {
            cachedCompletedPosition = completedPosition.value;
            if (position >= cachedCompletedPosition) return null;
        }
        // 当前游标对应的固定传输槽，发布/消费由游标控制。
        Slot slot = slots[(int) position & mask];
        // 同一分片仍按提交顺序发布完成；Owner 可能按另一种 Core 序号顺序探查。
        // 非队头 token 暂不可消费，先收集队头后才会轮到它。
        if (slot.token != expectedToken) return null;
        if (slot.command == null) throw new IllegalStateException("matcher completion publication gap");
        // 当前业务动作返回或队头读取的结果引用。
        Object result = slot.result;
        // 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。
        Throwable failure = slot.failure;
        slot.clear();
        consumedPosition.value = position + 1;
        if (failure != null) {
            if (failure instanceof RuntimeException /* 可直接按原类型传播的运行异常。 */ runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error /* 可直接按原类型传播的 JVM Error。 */ error) throw error;
            throw new IllegalStateException("matcher pipeline command failed", failure);
        }
        if (result == null) throw new IllegalStateException("matcher pipeline returned no result");
        skipReadyDirectSlots();
        return result;
    }

    /**
     * 仅由 Owner 在完成游标已发布后回收连续的正 token 直达传输槽，不再读取可能已回收的结算事件；遇到失败通过正常收集路径传播，不静默跳过。
     */
    private void skipReadyDirectSlots() {
        while (true) {
            // 当前传输游标位置，按所在方法区分提交、完成或消费。
            long position = consumedPosition.value;
            if (position >= cachedCompletedPosition) {
                cachedCompletedPosition = completedPosition.value;
                if (position >= cachedCompletedPosition) return;
            }
            // 当前游标对应的固定传输槽，发布/消费由游标控制。
            Slot slot = slots[(int) position & mask];
            // Matcher 发布完成游标后，只有 Owner 回收传输槽。
            // 结算事件可能已经复用，这里只依据传输槽自己的游标与固定标记。
            if (slot.token <= 0 || !slot.directSettlement) return;
            if (slot.failure != null) {
                pollResult(slot.token); // 通过普通收集路径传播失败，避免回收时丢失错误。
                return;
            }
            slot.clear();
            consumedPosition.value = position + 1;
        }
    }

    /**
     * 先轮询自旋，空闲后短暂 park，按 nanoTime 截止返回结果或空；等待中断抛异常，不取消队列内任务。只检查与当前预期 token 相同的队头。
     * @param expectedToken 只允许消费的目标队头 token
     * @param timeoutNanos 等待本次控制结果的正纳秒上限，不会取消已提交动作
     */
    private Object awaitResult(long expectedToken, long timeoutNanos) {
        if (timeoutNanos <= 0) return null;
        // 当前等待的 nanoTime 截止点。
        long deadline = System.nanoTime() + timeoutNanos;
        // 连续空闲次数，决定自旋还是短暂等待。
        int idle = 0;
        while (true) {
            // 当前业务动作返回或队头读取的结果引用。
            Object result = pollResult(expectedToken);
            if (result != null) return result;
            // 距当前等待截止点尚余的纳秒数。
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            if (idle++ < IDLE_SPINS) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(this, Math.min(remaining, IDLE_PARK_NANOS));
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("matcher pipeline wait was interrupted");
                }
            }
        }
    }

    /**
     * 队列模式返回已提交但尚未执行完成的游标差；融合模式同步执行，返回零。
     */
    public int submissionDepth() {
        if (inlineOwner != null) return 0;
        return Math.toIntExact(submittedPosition.value - completedPosition.value);
    }

    /**
     * 队列模式返回已完成但 Owner 尚未回收的传输槽数，含待回收直达槽；融合模式只看唯一普通结果槽。
     */
    public int completionDepth() {
        if (inlineOwner != null) return inlineCompletion.token == 0 ? 0 : 1;
        return Math.toIntExact(completedPosition.value - consumedPosition.value);
    }

    /**
     * 返回占着传输窗口的提交数，即已提交减已回收；融合模式只有零或一个待收集普通结果。
     */
    public int inFlight() {
        if (inlineOwner != null) return inlineCompletion.token == 0 ? 0 : 1;
        return Math.toIntExact(submittedPosition.value - consumedPosition.value);
    }

    /**
     * 返回队列已发布的提交游标，融合模式返回已执行的本地提交计数。
     */
    public long submittedPosition() {
        return inlineOwner != null ? inlineSubmitted : submittedPosition.value;
    }

    /**
     * 返回队列配置容量，融合模式的普通结果容量固定为一。
     */
    public int capacity() {
        return inlineOwner != null ? 1 : requestedCapacity;
    }

    /**
     * 返回发布侧观测到的窗口占用峰值，包含尚未由 Owner 回收的完成槽，不等同于 submissionDepth 峰值。
     */
    public int submissionHighWaterMark() {
        return submissionHighWaterMark;
    }

    /**
     * 返回工作线程观测到的未回收完成槽峰值，融合模式成功保留普通完成时记为一。
     */
    public int completionHighWaterMark() {
        return completionHighWaterMark;
    }

    /**
     * 按配置申请当前 Matcher 的 CPU 绑定并执行主循环，退出自动释放绑定；启动前异常转为 startupFailure，启动后异常转为 publicationFailure 并通知 Owner，停止接收新任务。
     * @param configuration Matcher 线程 CPU 绑定配置
     */
    private void run(TradingCpuAffinity configuration) {
        try (/* 当前 Matcher 获得的 CPU 绑定句柄，退出 try 时自动释放。 */ var affinity = configuration.matcher(workerId)) {
            runMatching();
        } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ Throwable failure) {
            if (!started) {
                startupFailure = failure;
                started = true;
            } else {
                publicationFailure = failure;
                // 当前读取的可空完成唤醒回调。
                Runnable signal = completionSignal;
                if (signal != null) signal.run();
            }
            accepting = false;
        }
    }

    /**
     * 先执行激活动作，再按提交顺序消费槽并在相应提交栅栏执行读请求；原生事实先发布到事件或 CommandSlot，随后发布传输完成游标并唤醒 Owner。命令异常留在槽中传播，发布异常终止工作；正常离开循环后拒绝剩余读请求并执行关闭动作。启动动作失败会提前返回。
     */
    private void runMatching() {
        try {
            if (startupAction != null) startupAction.run();
        } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ Throwable failure) {
            startupFailure = failure;
            accepting = false;
        } finally {
            started = true;
        }
        if (startupFailure != null) return;
        // 当前传输游标位置，按所在方法区分提交、完成或消费。
        long position = completedPosition.value;
        // Matcher 本地缓存的已发布提交位置。
        long cachedSubmitted = submittedPosition.value;
        // Matcher 本地缓存的 Owner 已回收位置，用于完成峰值统计。
        long cachedConsumed = consumedPosition.value;
        // 连续空闲次数，决定自旋还是短暂等待。
        int idle = 0;
        try {
            while (accepting || position < (cachedSubmitted = submittedPosition.value)) {
                // 读信箱队头请求，只有执行位置达到其提交栅栏才读取。
                BackgroundRead<?> read = backgroundReads.peek();
                if (read != null) {
                    // 判断读栅栏前先观察提交游标，让同一 Owner 先发布的读取
                    // 在其栅栏到达后执行，避免被后来提交的命令越过。
                    cachedSubmitted = submittedPosition.value;
                    if (position >= read.fence()) {
                        backgroundReads.poll();
                        read.execute();
                        continue;
                    }
                }
                if (position >= cachedSubmitted) {
                    cachedSubmitted = submittedPosition.value;
                    if (position >= cachedSubmitted) {
                        if (waitStrategy == WaitStrategy.BUSY_SPIN) {
                            Thread.onSpinWait();
                        } else if (idle++ < WORKER_IDLE_SPINS) {
                            Thread.onSpinWait();
                        } else {
                            if (accepting && position >= (cachedSubmitted = submittedPosition.value) && backgroundReads.isEmpty()) {
                                wakeup.requested = 1L;
                                if (accepting && position >= (cachedSubmitted = submittedPosition.value) && backgroundReads.isEmpty()) {
                                    LockSupport.parkNanos(this, WORKER_IDLE_PARK_NANOS);
                                }
                                wakeup.requested = 0L;
                            }
                        }
                        continue;
                    }
                }
                idle = 0;
                // 当前游标对应的固定传输槽，发布/消费由游标控制。
                Slot slot = slots[(int) position & mask];
                // 当前传输槽发布的业务函数。
                Supplier<?> command = slot.command;
                if (command == null || slot.token == 0) {
                    slot.failure = new IllegalStateException("matcher command publication gap");
                } else {
                    // 当前命令关联的可空结算事件，在原生事实发布窗口使用。
                    MatcherSettlementEvent settlement = slot.settlement;
                    try {
                        if (settlement != null && settlement.direct()) settlement.beginMatcherPublication();
                        // 当前业务动作返回或队头读取的结果引用。
                        Object result = command.get();
                        // Matcher 先在当前结果对象上绑定 Core 序号，再把同一引用发布到命令槽。
                        slot.result = slot.token > 0 && result instanceof CoreMatchingResult /* 普通 Core 撮合结果，在 Matcher 线程原位补齐 Core 序号。 */ matchingResult
                                ? matchingResult.withCoreSequenceInPlace(slot.token) : result;
                        if (settlement != null && !settlement.resultPrepared()) {
                            throw new IllegalStateException("direct matcher did not publish its native result");
                        }
                    } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ Throwable failure) {
                        if (settlement != null) settlement.failDirect(failure);
                        slot.failure = failure;
                    } finally {
                        if (settlement != null && settlement.direct()) settlement.completeMatcherPublication();
                    }
                }
                // 先交付命令结果，再发布传输完成游标。
                // Owner 随即可能复用命令槽，下面只使用已经取得的局部引用。
                // 当前命令结果应直接发布到的 CommandSlot，发布后不再访问该上下文。
                CommandSlot target = slot.resultTarget;
                // 没有命令异常且存在回写目标时的普通撮合结果。
                CoreMatchingResult routedResult = target != null && slot.failure == null
                        ? (CoreMatchingResult) slot.result : null;
                if (routedResult != null) {
                    try { target.publishMatcherResult(workerId, routedResult); }
                    catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ Throwable failure) {
                        publicationFailure = failure;
                        accepting = false;
                    }
                }
                position++;
                completedPosition.value = position;
                // 当前读取的可空完成唤醒回调。
                Runnable signal = completionSignal;
                if (signal != null) signal.run();
                if (position - cachedConsumed > completionHighWaterMark) {
                    cachedConsumed = consumedPosition.value;
                    // 当前已完成但 Owner 尚未回收的传输槽数。
                    int compDepth = Math.toIntExact(position - cachedConsumed);
                    if (compDepth > completionHighWaterMark) {
                        completionHighWaterMark = compDepth;
                    }
                }
                if (publicationFailure != null) break;
            }
        } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ Throwable failure) {
            // 包括 completeMatcherPublication 在内的发布失败都必须通知 Owner，
            // 使已经退出的 worker 显示为故障，不能只表现为队列暂时空闲。
            publicationFailure = failure;
            accepting = false;
            // 当前读取的可空完成唤醒回调。
            Runnable signal = completionSignal;
            if (signal != null) signal.run();
        }
        // worker 退出时尚未执行的读请求，逐个完成为失败。
        BackgroundRead<?> unfinishedRead;
        while ((unfinishedRead = backgroundReads.poll()) != null) {
            unfinishedRead.future().completeExceptionally(new RejectedExecutionException("matcher stopped"));
        }
        if (shutdownAction != null) {
            try {
                shutdownAction.run();
            } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ Throwable failure) {
                shutdownFailure = failure;
            }
        }
    }

    /**
     * 读取系统属性并规范大小写，只允许 BUSY_SPIN 或 ADAPTIVE，空白和其他取值明确拒绝。
     */
    private static WaitStrategy configuredWaitStrategy() {
        // 系统属性中的等待策略原文，规范大小写后解析枚举。
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.BUSY_SPIN.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (/* 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。 */ RuntimeException failure) {
            throw new IllegalArgumentException(
                    WAIT_STRATEGY_PROPERTY + " must be BUSY_SPIN or ADAPTIVE", failure);
        }
    }

    /**
     * 独立 Matcher 队列空闲时的等待方式，不改变命令执行顺序。
     */
    private enum WaitStrategy {
        /** 空闲时持续自旋，提交时不走 park 唤醒。 */
        BUSY_SPIN,
        /** 空闲先短暂自旋，再设置唤醒请求并有限 park。 */
        ADAPTIVE
    }

    /**
     * 把工作线程发布阶段的可见故障抛给 Owner，保留 RuntimeException 或 Error 类型，其他异常包装为 IllegalStateException。
     */
    private void rethrowPublicationFailure() {
        // 当前捕获或观察到的失败原因，按所在边界写入 Future、槽位或启动/发布故障标记。
        Throwable failure = publicationFailure;
        if (failure instanceof RuntimeException /* 需要按当前等待或发布路径传播的异常。 */ exception) throw exception;
        if (failure instanceof Error /* 可直接按原类型传播的 JVM Error。 */ error) throw error;
        if (failure != null) throw new IllegalStateException("matcher result publication failed", failure);
    }

    /**
     * 最多等待三十秒观察启动完成标记，先自旋再短 park；超时停止接收，启动失败按原异常种类传播。这里只观察标记，不 join 或清理超时线程。
     */
    private void awaitStartup() {
        // 当前等待的 nanoTime 截止点。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        // 连续空闲次数，决定自旋还是短暂等待。
        int idle = 0;
        while (!started && System.nanoTime() < deadline) {
            if (idle++ < IDLE_SPINS) Thread.onSpinWait();
            else LockSupport.parkNanos(this, IDLE_PARK_NANOS);
        }
        if (!started) {
            accepting = false;
            throw new IllegalStateException("matcher pipeline startup timed out");
        }
        if (startupFailure instanceof RuntimeException /* 可直接按原类型传播的运行异常。 */ runtimeFailure) throw runtimeFailure;
        if (startupFailure instanceof Error /* 可直接按原类型传播的 JVM Error。 */ error) throw error;
        if (startupFailure != null) {
            throw new IllegalStateException("matcher pipeline startup failed", startupFailure);
        }
    }

    /**
     * 不附带业务关闭动作地停止流水线。
     */
    @Override
    public void close() {
        close(null);
    }

    /**
     * 停止接收新任务并最多等独立 worker 五秒，由 worker 执行已提交任务和关闭动作；融合/未启动路径在当前线程执行动作。已停止路径可在当前线程补执行动作，中断按路径恢复标记，超时或已记录关闭异常向上抛出。
     * @param action 启动或关闭时的可空动作，执行线程见方法说明
     */
    public void close(Runnable action) {
        if (inlineOwner != null) assertInlineOwner();
        // 当前队列 worker 引用，空值表示未启用独立工作线程。
        Thread activeWorker = worker;
        if (activeWorker == null) {
            accepting = false;
            if (action != null) action.run();
            return;
        }
        if (!accepting) {
            if (activeWorker.isAlive()) {
                try {
                    activeWorker.join(TimeUnit.SECONDS.toMillis(5));
                } catch (/* 需要按当前等待或发布路径传播的异常。 */ InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("matcher pipeline shutdown was interrupted", exception);
                }
            }
            if (activeWorker.isAlive()) throw new IllegalStateException("matcher pipeline did not stop");
            if (action != null) action.run();
            return;
        }
        shutdownAction = action;
        accepting = false;
        LockSupport.unpark(activeWorker);
        // 本次关闭等待是否曾被中断，退出等待后恢复线程中断标记。
        boolean interrupted = false;
        // 当前等待的 nanoTime 截止点。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (activeWorker.isAlive() && System.nanoTime() < deadline) {
            try {
                activeWorker.join(10);
            } catch (/* 需要按当前等待或发布路径传播的异常。 */ InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (activeWorker.isAlive()) throw new IllegalStateException("matcher pipeline did not stop");
        if (shutdownFailure instanceof RuntimeException /* 可直接按原类型传播的运行异常。 */ runtimeFailure) throw runtimeFailure;
        if (shutdownFailure instanceof Error /* 可直接按原类型传播的 JVM Error。 */ error) throw error;
        if (shutdownFailure != null) {
            throw new IllegalStateException("matcher pipeline shutdown failed", shutdownFailure);
        }
    }

    /**
     * 只承担 Owner 与单个 Matcher 间传输的可复用槽：命令、完成引用和错误按游标发布，Owner 回收后才能覆盖。
     */
    private static final class Slot {
        /** 正 Core 命令号或负控制 token，零表示传输槽可复用。 */
        private long token;
        /** 由 Matcher 或融合拥有线程调用的业务函数，调用方保证输入发布后稳定。 */
        private Supplier<?> command;
        /** 普通撮合结果直接回写的命令上下文，直达事件路径为空。 */
        private CommandSlot resultTarget;
        /** 在完成游标发布前写入的命令结果引用。 */
        private Object result;
        /** 命令执行失败时保存的异常，Owner 收集时传播。 */
        private Throwable failure;
        /** 可选结算事件，仅按约定租约访问，不代表传输槽拥有其整个生命周期。 */
        private com.surprising.aeron.service.state.settlement.MatcherSettlementEvent settlement;
        /** 提交时固定下来的直达标记，事件回收后 Owner 仍可安全据此回收传输槽。 */
        private boolean directSettlement;

        /**
         * 由 Owner 在安全消费边界清掉全部传输引用和标记，允许后续环形位置复用；不清理账户状态或结算事件自己的生命周期。
         */
        private void clear() {
            token = 0;
            command = null;
            resultTarget = null;
            result = null;
            failure = null;
            settlement = null;
            directSettlement = false;
        }
    }

    /**
     * 共享游标的左侧占位字段基类，用于拉开热点字段距离，不承诺特定 JVM 的缓存行布局。
     */
    private static class PaddedSequenceLhsPadding {
        /**
         * p00：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p01：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p02：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p03：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p04：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p05：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p06：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p07：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p08：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p09：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0a：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0b：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0c：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0d：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0e：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         */
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    /**
     * 真正存放 volatile 游标的层，左右两层只提供空间填充。
     */
    private static class PaddedSequenceValue extends PaddedSequenceLhsPadding {
        /** 在左右填充之间的 volatile 游标值，用作发布和消费可见性边界。 */
        protected volatile long value;
    }

    /**
     * 带左右填充的提交、完成或消费游标对象，各游标各自只有约定写者。
     */
    private static final class PaddedSequence extends PaddedSequenceValue {
        /**
         * p10：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p11：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p12：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p13：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p14：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p15：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p16：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p17：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p18：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p19：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1a：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1b：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1c：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1d：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1e：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         */
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }

    /**
     * 唤醒请求标记的左侧占位字段基类，避免与相邻热点过近。
     */
    private static class WakeupLhsPadding {
        /**
         * p00：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p01：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p02：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p03：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p04：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p05：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p06：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p07：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p08：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p09：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0a：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0b：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0c：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0d：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p0e：游标或唤醒标记左侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         */
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    /**
     * 存放实际 volatile 唤醒请求标记的层，提交方通过 VarHandle 比较交换。
     */
    private static class WakeupValue extends WakeupLhsPadding {
        /** Matcher 准备 park 时写一，提交方 CAS 清零并 unpark；不是命令完成状态。 */
        protected volatile long requested;
    }

    /**
     * 带左右填充的唤醒状态对象，协调自适应等待与提交方 unpark。
     */
    private static final class Wakeup extends WakeupValue {
        /**
         * p10：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p11：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p12：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p13：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p14：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p15：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p16：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p17：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p18：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p19：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1a：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1b：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1c：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1d：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         * p1e：游标或唤醒标记右侧的占位 long，尝试拉开热点字段，实际缓存行布局仍取决于 JVM。
         */
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }
}
