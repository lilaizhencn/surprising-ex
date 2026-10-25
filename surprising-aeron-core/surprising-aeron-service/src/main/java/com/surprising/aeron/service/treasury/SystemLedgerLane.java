package com.surprising.aeron.service.treasury;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 原 Treasury 的单写者执行边界。一个 Owner 生产者把任务交给账本线程的有界 SPSC 队列。消费序号只释放队列容量，任务业务完成由各自回执发布，不能用队列为空代替资金完成。
 */
public final class SystemLedgerLane implements AutoCloseable {
    /**
     * 原命令或事件持有的财务任务契约。执行器负责就绪与线程交接，任务自身发布业务回执，队列不另造资金状态。
     */
    public interface Entry {
        /**
         * 在当前持有 Treasury 的执行线程应用本任务，任务自身负责保存输入及发布业务完成回执，发布后不得继续读可能被复用的任务字段。
         * @param ledger 需要交接单写者所有权的原 Treasury
         */
        void apply(TreasuryRuntime ledger);
        /**
         * 默认任务输入已就绪；特殊控制关闭任务会在此推进验证，不能把所有实现的 ready 都理解为纯读取。
         */
        default boolean ready() { return true; }
    }

    /**
     * 序号值之前的代码布局填充层，不保存任何资金或命令状态。
     */
    /**
     * 保存单调序号的容器，发布生产、消费或彻底执行进度。
     */
    private static final class PaddedSequence {
        /** 用 volatile 发布的单调序号，负一表示还未发布或消费任何任务。 */
        protected volatile long value = -1L;
    }

    /** 整个执行器交接的同一份权威 Treasury，不创建账本副本。 */
    private final TreasuryRuntime ledger;
    /** 保存任务引用的二次幂物理 SPSC 环，逻辑容量另由 capacity 限制。 */
    private final Entry[] ring;
    /** 允许排队而尚未释放传输容量的任务上限。 */
    private final int capacity;
    /** 物理环长度减一，用于按序号映射槽位。 */
    private final int mask;
    /** 唯一生产者已经完整发布到环的最后任务序号。 */
    private final PaddedSequence producerSequence = new PaddedSequence();
    /** 账本已接管并释放传输槽的最后序号，可能仍在执行 apply。 */
    private final PaddedSequence consumerSequence = new PaddedSequence();
    /** 账本线程 apply 完整返回后才推进的序号，内联交接用它确认没有在途写入。 */
    private final PaddedSequence appliedSequence = new PaddedSequence();
    /** 只由生产者持有的消费序号缓存，用于减少满队列检测读取。 */
    private long cachedConsumerSequence = -1L;
    /** 构造时的唯一 Owner 生产线程，也是允许内联应用的线程。 */
    private final Thread producer;
    /**  消费财务任务的守护账本线程。 返回本执行器创建的账本工作线程，用于所有权和生命周期检查。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final Thread thread;
    /** 账本线程故障交给所属运行时的通知入口。 */
    private final Consumer<Throwable> failurePublisher;
    /** 是否继续运行的 volatile 标记，关闭或故障置 false。 */
    private volatile boolean running = true;
    /** 构造线程可结束启动等待的 volatile 标记，故障退出也置 true，成功与否仍须 assertHealthy。 */
    private volatile boolean started;
    /** 账本线程首轮退出时保留的故障，调用者通过健康检查观察。 */
    private volatile Throwable failure;

    /**
     * 校验输入，准备物理环和逻辑容量，把原财库交给守护账本线程；固定为 BUSY_SPIN 忙等。
     * @param ledger 需要交接单写者所有权的原 Treasury
     * @param capacity 逻辑排队容量，必须为正，物理环向上取二次幂且至少两个槽
     * @param failurePublisher 工作线程故障接收回调
     */
    public SystemLedgerLane(TreasuryRuntime ledger, int capacity, Consumer<Throwable> failurePublisher) {
        if (ledger == null || capacity <= 0 || failurePublisher == null)
            throw new IllegalArgumentException("system ledger, capacity and failure publisher are required");
        this.ledger = ledger;
        this.capacity = capacity;
        // 按请求容量向上取二次幂、至少两个槽的物理环长度。
        int ringSize = capacity <= 1 ? 2 : Integer.highestOneBit(capacity - 1) << 1;
        this.ring = new Entry[ringSize];
        this.mask = ringSize - 1;
        this.failurePublisher = failurePublisher;
        producer = Thread.currentThread();
        thread = new Thread(this::run, "core-system-ledger-lane");
        thread.setDaemon(true);
        ledger.handoffTo(thread);
        thread.start();
        // 等待启动或停止线程的单调时钟截止点。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!started && System.nanoTime() < deadline) LockSupport.parkNanos(this, 100_000L);
        if (!started) {
            running = false;
            throw new IllegalStateException("system ledger Lane did not start");
        }
        assertHealthy();
    }

    /**
     * 只允许原 Owner 生产者非阻塞投递，满逻辑容量返回 false；如财库当前被 Owner 内联持有，先交还账本和业务索引再发布序号，阻塞等待模式下按停车标记唤醒。
     * @param entry 仍由命令或事件租约持有输入及完成状态的非空财务任务
     */
    public boolean offer(Entry entry) {
        if (Thread.currentThread() != producer)
            throw new IllegalStateException("system ledger mailbox has one Owner producer");
        if (entry == null) throw new IllegalArgumentException("system ledger entry is required");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("system ledger Lane is closed");
        // 生产者准备发布的下一任务序号。
        long nextSeq = producerSequence.value + 1;
        if (nextSeq - cachedConsumerSequence > capacity) {
            cachedConsumerSequence = consumerSequence.value;
            if (nextSeq - cachedConsumerSequence > capacity) return false;
        }
        ring[(int) nextSeq & mask] = entry;
        producerSequence.value = nextSeq;
        return true;
    }

    /**
     * 返回按生产与消费序号计算并截为非负的排队深度；任务开始 apply 前已释放消费容量，因此此数不包含全部在执行资金工作。
     */
    int depth() { return (int) Math.max(0L, producerSequence.value - consumerSequence.value); }
    /**
     * 供生产者检查逻辑容量并刷新其消费序号缓存，关闭或有故障时返回 false；不提供多生产者访问授权。
     */
    boolean hasCapacity() {
        if (!running || failure != null) return false;
        // 生产者准备发布的下一任务序号。
        long nextSeq = producerSequence.value + 1;
        if (nextSeq - cachedConsumerSequence <= capacity) return true;
        cachedConsumerSequence = consumerSequence.value;
        return nextSeq - cachedConsumerSequence <= capacity;
    }

    /**
     * 把账本线程保留的运行时异常或 Error 原样交给调用方，其他故障包装后抛出。
     */
    public void assertHealthy() {
        // 当前健康检查或账本线程捕获的故障。
        Throwable problem = failure;
        if (problem instanceof RuntimeException /* 已识别为运行时异常的原故障，健康检查直接抛出。 */ runtime) throw runtime;
        if (problem instanceof Error /* 已识别为 Error 的原故障，健康检查直接抛出。 */ error) throw error;
        if (problem != null) throw new IllegalStateException("system ledger Lane failed", problem);
    }

    /**
     * 账本线程绑定原财库后按 FIFO 等队首就绪，先释放传输槽再执行资金，apply 返回后推进彻底执行序号；忙等或有界停车按配置选择。故障保存并通知 Owner，退出释放财库所有权，启动等待者也会被放行检查故障。
     */
    private void run() {
        // 阻塞等待策略下停车前已忙等的次数。
        // 账本当前应接管的下一 FIFO 序号。
        long nextConsumeSeq = 0;
        // 账本线程缓存的最后生产发布序号，必要时刷新。
        long cachedProducerSeq = -1L;
        // 账本已看到但尚需等待就绪的队首任务引用。
        Entry head = null;
        try {
            ledger.bindOwner();
            started = true;
            while (true) {
                if (head == null) {
                    if (nextConsumeSeq > cachedProducerSeq) {
                        cachedProducerSeq = producerSequence.value;
                    }
                    if (nextConsumeSeq <= cachedProducerSeq) {
                        head = ring[(int) nextConsumeSeq & mask];
                    } else if (!running) {
                        break;
                    }
                }
                if (head != null && head.ready()) {
                    // 账本已接管就绪队首，先释放传输容量再应用资金。
                    // 业务完成仍以任务自己的发布标记为准，Owner 不必等响应退休才继续入队。
                    ring[(int) nextConsumeSeq & mask] = null;
                    consumerSequence.value = nextConsumeSeq;
                    // 当前已接管任务的序号，保留到 apply 返回后再发布彻底执行进度。
                    long appliedSeq = nextConsumeSeq;
                    nextConsumeSeq++;
                    // 已从队首移到局部并接管的就绪任务，业务完成发布后不再读其字段。
                    Entry readyEntry = head;
                    head = null;
                    readyEntry.apply(ledger);
                    // apply 可能已发布完成并允许命令槽立即复用，
                    // 交接后不再读取任务字段，只推进局部保存的序号。
                    appliedSequence.value = appliedSeq;
                    continue;
                }
                if (!running && head != null)
                    throw new IllegalStateException("system ledger stopped before settlement was ready");
                Thread.onSpinWait();
            }
        } catch (/* 当前健康检查或账本线程捕获的故障。 */ Throwable problem) {
            failure = problem;
            running = false;
            try { failurePublisher.accept(problem); }
            catch (/* 故障通知回调自身抛出的异常，附加到原故障作为诊断。 */ Throwable reportingFailure) { problem.addSuppressed(reportingFailure); }
        } finally {
            ledger.transferOwner(null);
            started = true;
        }
    }

    /**
     * 停止接收工作并唤醒线程，最多等五秒退出；保留调用者中断标记。已有就绪项可继续消费，停止时仍有未就绪队首会作为故障报告，不静默丢弃资金工作。
     */
    @Override public void close() {
        running = false;
        LockSupport.unpark(thread);
        // 关闭等待期间是否收到过中断，用于退出前恢复调用者标记。
        boolean interrupted = false;
        // 等待启动或停止线程的单调时钟截止点。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try { thread.join(10L); }
            catch (/* 线程 join 收到的中断异常，先记录中断并继续有界等待，不丢失最终标记。 */ InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new IllegalStateException("system ledger Lane did not stop");
        assertHealthy();
    }
}
