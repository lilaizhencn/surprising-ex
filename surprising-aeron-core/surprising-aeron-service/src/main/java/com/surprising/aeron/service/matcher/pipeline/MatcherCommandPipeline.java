package com.surprising.aeron.service.matcher.pipeline;

import com.surprising.aeron.bootstrap.matcher.MatcherThread;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.service.matcher.CoreMatchingResult;
import com.surprising.aeron.service.order.CommandSlot;
import com.surprising.aeron.service.settlement.MatcherSettlementEvent;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

/**
 * 一个 Matcher 分片的串行执行与完成交接。由单一 Owner 提交、独立 MatcherThread 执行、Owner 按本分片顺序回收传输槽。实际成交事实由结算事件或 CommandSlot 持有，传输槽不复制整份交易状态；线程所有权与对象使用期限由调用链遵守。
 */
@Slf4j
public final class MatcherCommandPipeline implements AutoCloseable {
    /** Owner 等启动或控制结果时先自旋的次数。 */
    private static final int IDLE_SPINS = 1_024;
    /** Owner 超过自旋次数后的单次短暂等待纳秒上限。 */
    private static final long IDLE_PARK_NANOS = 1_000L;
    /** 固定容量的传输槽，构造内容后通过提交/完成游标交接。 */
    private Slot[] slots;
    /** 配置的二次幂队列容量。 */
    private final int requestedCapacity;
    /** 容量减一，用于把递增位置映射为环形下标。 */
    private final int mask;
    /** Matcher 工作线程，启动后不清空，禁止重启。 */
    private volatile Thread worker;
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
    /** 是否仍接收新命令，关闭或故障时清为 false，worker 仍按分支处理已提交任务。 */
    private volatile boolean accepting;
    /**  发布侧观测到的提交减回收窗口占用峰值。 返回发布侧观测到的窗口占用峰值，包含尚未由 Owner 回收的完成槽，不等同于 submissionDepth 峰值。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile int submissionHighWaterMark;
    /**  工作线程观测到的完成减回收槽位峰值。 返回工作线程观测到的未回收完成槽峰值。 */
    @Accessors(fluent = true, chain = false)
    @Getter
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
    /** 固定的分片完成通知；只标记原分片就绪位，独立流水线没有组接收者。 */
    private final Runnable completionPublished;
    /**
     * 把查询和当前已提交位置放入有界单生产者读信箱，Matcher 执行到该位置后再读。关闭或信箱满返回失败 Future，运行异常转为失败结果，查询不会复制交易状态。
     * @param <T> 读取或控制调用返回值的类型
     * @param read 到达提交栅栏后在 Matcher 拥有线程执行的读取函数
     */
    public <T> java.util.concurrent.CompletableFuture<T> readAtSubmissionFence(Supplier<T> read) {
        if (!accepting) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new RejectedExecutionException("matcher read mailbox unavailable"));
        }
        // 当前查询自己的完成 Future，信箱满时立即失败。
        var future = new java.util.concurrent.CompletableFuture<T>();
        if (!backgroundReads.offer(new BackgroundRead<>(submittedPosition.value, read, future))) {
            future.completeExceptionally(new RejectedExecutionException("matcher read mailbox unavailable"));

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
         * 发布读取结果或异常；致命 Error 完成 Future 后继续交给 worker 故障边界。
         */
        void execute() {
            try {
                future.complete(read.get());
            } catch (RuntimeException | Error failure) {
                future.completeExceptionally(failure);
                if (failure instanceof Error fatal) throw fatal;
            }
        }
    }

    /** 装配指定分片的有界传输队列；由启动边界显式启动 worker。 */
    public MatcherCommandPipeline(int workerId, int requestedCapacity,
            Runnable completionPublished) {
        this.completionPublished = completionPublished;
        if (requestedCapacity <= 0 || (requestedCapacity & (requestedCapacity - 1)) != 0) {
            throw new IllegalArgumentException("matcher pipeline capacity must be a power of two");
        }
        this.workerId = workerId;
        this.requestedCapacity = requestedCapacity;
        mask = requestedCapacity - 1;
    }

    /**
     * 创建有界读信箱和预分配命令槽，保存激活动作后启动指定分片 MatcherThread，并同步等待启动结果；已有 worker 时直接返回，不允许关闭后重新启动。
     * @param action 启动或关闭时的可空动作，执行线程见方法说明
     * @param affinity Matcher 线程 CPU 绑定配置
     */
    public synchronized void start(Runnable action,
            TradingCpuAffinity affinity) {
        if (worker != null) return;
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
        submitInternal(coreSequence, command, settlement, null);
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
     * 以精确递增的本地控制计数生成负 token 并提交，提交与收集分开；提交失败也已占用该计数，控制溢出会抛异常。
     * @param command 需要在 Matcher 拥有线程执行的非空函数
     */
    public long submitControl(Supplier<?> command) {
        controlSequence = Math.incrementExact(controlSequence);
        // 当前控制请求生成的负 token。
        long token = -controlSequence;
        submitInternal(token, command, null, null);
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
     * 先拒绝无效输入、发布故障和关闭状态；由 Owner 回收就绪直达槽、检查窗口容量并填槽，最后用提交游标发布。队满立即拒绝，不覆盖未消费槽。
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
     * 只消费匹配预期 token 的已完成队头，清槽并推进 Owner 消费游标后返回结果或抛出命令异常；非队头只是暂不可消费。消费后继续回收就绪直达槽。
     * @param expectedToken 只允许消费的目标队头 token
     */
    private Object pollResult(long expectedToken) {
        rethrowPublicationFailure();
        if (expectedToken == 0) throw new IllegalArgumentException("matcher token must be non-zero");
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
     * 返回已提交但尚未执行完成的游标差。
     */
    public int submissionDepth() {
        return Math.toIntExact(submittedPosition.value - completedPosition.value);
    }

    /**
     * 返回已完成但 Owner 尚未回收的传输槽数，含待回收直达槽。
     */
    public int completionDepth() {
        return Math.toIntExact(completedPosition.value - consumedPosition.value);
    }

    /**
     * 返回队列配置容量。
     */
    public int capacity() {
        return requestedCapacity;
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
                // 当前读取的固定分片就绪通知。
                Runnable signal = completionPublished;
                if (signal != null) signal.run();
            }
            accepting = false;
        }
    }

    /**
     * 先执行激活动作，再按提交顺序消费槽并在相应提交栅栏执行读请求；原生事实先发布到事件或 CommandSlot，随后发布传输完成游标并标记所属分片就绪。命令异常留在槽中传播，发布异常终止工作；正常离开循环后拒绝剩余读请求并执行关闭动作。启动动作失败会提前返回。
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
                        Thread.onSpinWait();
                        continue;
                    }
                }
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
                // 当前读取的固定分片就绪通知。
                Runnable signal = completionPublished;
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
            // 当前读取的固定分片就绪通知。
            Runnable signal = completionPublished;
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
    public boolean workerStopped() { return worker == null || !worker.isAlive(); }

    @Override
    public void close() {
        close(null);
    }

    /**
     * 停止接收新任务并最多等独立 worker 五秒，由 worker 执行已提交任务和关闭动作；未启动路径在当前线程执行动作。已停止路径可在当前线程补执行动作，中断按路径恢复标记，超时或已记录关闭异常向上抛出。
     * @param action 启动或关闭时的可空动作，执行线程见方法说明
     */
    public void close(Runnable action) {
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
        /** 由 Matcher 拥有线程调用的业务函数，调用方保证输入发布后稳定。 */
        private Supplier<?> command;
        /** 普通撮合结果直接回写的命令上下文，直达事件路径为空。 */
        private CommandSlot resultTarget;
        /** 在完成游标发布前写入的命令结果引用。 */
        private Object result;
        /** 命令执行失败时保存的异常，Owner 收集时传播。 */
        private Throwable failure;
        /** 可选结算事件，仅按约定租约访问，不代表传输槽拥有其整个生命周期。 */
        private com.surprising.aeron.service.settlement.MatcherSettlementEvent settlement;
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
     * 存放 volatile 游标的槽位，用作发布和消费可见性边界。
     */
    private static final class PaddedSequence {
        /** volatile 游标值，用作发布和消费可见性边界。 */
        protected volatile long value;
    }

}
