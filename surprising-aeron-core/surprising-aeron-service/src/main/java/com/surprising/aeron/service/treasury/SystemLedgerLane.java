package com.surprising.aeron.service.treasury;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 原 Treasury 的单写者执行边界。一个 Owner 生产者把任务交给账本线程的有界 SPSC 队列；FUSED 只有在全部入队任务彻底执行完时才能交接同一财库及业务索引到 Owner 内联执行。消费序号只释放队列容量，任务业务完成由各自回执发布，不能用队列为空代替资金完成。
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

    /** 账本等待策略的 JVM 属性名，只接受 BUSY_SPIN 或 BLOCKING。 */
    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.system-ledger-wait-strategy";
    /**
     * 账本没有可执行队首时采用的等待方式，由 JVM 属性在构造时固定。
     */
    public enum WaitStrategy { /** 没有可执行队首时持续忙等，不进入定时停车。 */ BUSY_SPIN, /** 先有界忙等，再按停车标记唤醒或每次约一百微秒定时复查。 */ BLOCKING }

    /**
     * 序号值之前的代码布局填充层，不保存任何资金或命令状态。
     */
    private static class SequenceLhsPadding {
        /**
         * p01：序号布局使用的第1个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p02：序号布局使用的第2个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p03：序号布局使用的第3个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p04：序号布局使用的第4个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p05：序号布局使用的第5个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p06：序号布局使用的第6个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p07：序号布局使用的第7个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p08：序号布局使用的第8个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p09：序号布局使用的第9个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p10：序号布局使用的第10个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p11：序号布局使用的第11个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p12：序号布局使用的第12个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p13：序号布局使用的第13个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p14：序号布局使用的第14个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p15：序号布局使用的第15个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         */
        @SuppressWarnings("unused")
        protected long p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15;
    }

    /**
     * 独立 volatile 序号值层，用来发布生产、消费或彻底执行进度。
     */
    private static class SequenceValue extends SequenceLhsPadding {
        /** 用 volatile 发布的单调序号，负一表示还未发布或消费任何任务。 */
        protected volatile long value = -1L;
    }

    /**
     * 在序号值周围增加填充字段的容器，目的在于隔开不同线程频繁写入的序号，不改变序号业务含义。
     */
    private static final class PaddedSequence extends SequenceValue {
        /**
         * p16：序号布局使用的第16个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p17：序号布局使用的第17个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p18：序号布局使用的第18个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p19：序号布局使用的第19个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p20：序号布局使用的第20个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p21：序号布局使用的第21个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p22：序号布局使用的第22个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p23：序号布局使用的第23个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p24：序号布局使用的第24个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p25：序号布局使用的第25个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p26：序号布局使用的第26个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p27：序号布局使用的第27个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p28：序号布局使用的第28个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p29：序号布局使用的第29个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         * p30：序号布局使用的第30个填充 long，只用于隔开频繁写入字段，不保存业务状态。
         */
        @SuppressWarnings("unused")
        protected long p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30;
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
    /** 原财库所有权迁移时同步迁移账本独占业务索引的回调。 */
    private final Consumer<Thread> businessOwnerBinder;
    /** 构造时读取并固定的忙等或阻塞等待方式。 */
    private final WaitStrategy waitStrategy;
    /** 仅生产者使用的所有权记录，true 时账本线程无已发布工作，Owner 持原财库内联执行。 */
    private boolean producerHoldsLedger;
    /** 是否继续运行的 volatile 标记，关闭或故障置 false。 */
    private volatile boolean running = true;
    /** 构造线程可结束启动等待的 volatile 标记，故障退出也置 true，成功与否仍须 assertHealthy。 */
    private volatile boolean started;
    /**  账本线程发布的停车状态，生产者据此决定是否 unpark。 返回账本线程当前是否已发布停车等待标记，不表示业务队列一定为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private volatile boolean parked;
    /** 账本线程首轮退出时保留的故障，调用者通过健康检查观察。 */
    private volatile Throwable failure;

    /**
     * 使用空业务索引迁移回调构造账本执行器，其他启动及线程检查由完整构造器处理。
     * @param ledger 需要交接单写者所有权的原 Treasury
     * @param capacity 逻辑排队容量，必须为正，物理环向上取二次幂且至少两个槽
     * @param failurePublisher 工作线程故障接收回调
     */
    public SystemLedgerLane(TreasuryRuntime ledger, int capacity, Consumer<Throwable> failurePublisher) {
        this(ledger, capacity, failurePublisher, /* 无额外业务索引迁移的构造重载收到的线程，本空回调不使用它。 */ owner -> { });
    }

    /**
     * 校验输入，准备至少两个物理槽的二次幂环和逻辑容量，保存唯一生产者并把原财库交给守护账本线程；有界等待启动，启动失败或工作线程故障直接报错。
     * @param ledger 需要交接单写者所有权的原 Treasury
     * @param capacity 逻辑排队容量，必须为正，物理环向上取二次幂且至少两个槽
     * @param failurePublisher 工作线程故障接收回调
     * @param businessOwnerBinder 接管财库时同步迁移账本独占业务索引到目标线程的回调
     */
    public SystemLedgerLane(TreasuryRuntime ledger, int capacity, Consumer<Throwable> failurePublisher,
                     Consumer<Thread> businessOwnerBinder) {
        if (ledger == null || capacity <= 0 || failurePublisher == null || businessOwnerBinder == null)
            throw new IllegalArgumentException("system ledger, capacity and failure publisher are required");
        this.businessOwnerBinder = businessOwnerBinder;
        this.ledger = ledger;
        this.capacity = capacity;
        // 按请求容量向上取二次幂、至少两个槽的物理环长度。
        int ringSize = capacity <= 1 ? 2 : Integer.highestOneBit(capacity - 1) << 1;
        this.ring = new Entry[ringSize];
        this.mask = ringSize - 1;
        this.failurePublisher = failurePublisher;
        this.waitStrategy = configuredWaitStrategy();
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
        if (producerHoldsLedger) {
            // 账本线程此刻静默（appliedSequence==producerSequence），先把账本与索引交还给它再发布任务。
            ledger.transferOwner(thread);
            businessOwnerBinder.accept(thread);
            producerHoldsLedger = false;
        }
        producerSequence.value = nextSeq;
        if (waitStrategy == WaitStrategy.BLOCKING && parked) {
            LockSupport.unpark(thread);
        }
        return true;
    }

    /**
     * 仅在此前全部入队任务已从 apply 完整返回且本任务就绪时，由生产者接管原 Treasury 和索引并内联应用；不具备条件返回 false，调用方可沿原 offer 路径排队。
     * @param entry 仍由命令或事件租约持有输入及完成状态的非空财务任务
     */
    public boolean tryApplyInline(Entry entry) {
        if (Thread.currentThread() != producer)
            throw new IllegalStateException("system ledger mailbox has one Owner producer");
        if (entry == null) throw new IllegalArgumentException("system ledger entry is required");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("system ledger Lane is closed");
        if (appliedSequence.value != producerSequence.value || !entry.ready()) return false;
        if (!producerHoldsLedger) {
            ledger.transferOwner(producer);
            businessOwnerBinder.accept(producer);
            producerHoldsLedger = true;
        }
        entry.apply(ledger);
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
        int spins = 0;
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
                    spins = 0;
                    continue;
                }
                if (!running && head != null)
                    throw new IllegalStateException("system ledger stopped before settlement was ready");
                if (waitStrategy == WaitStrategy.BUSY_SPIN) {
                    Thread.onSpinWait();
                    continue;
                }
                if (spins++ < 256) { Thread.onSpinWait(); continue; }
                parked = true;
                try {
                    if (head == null && nextConsumeSeq <= (cachedProducerSeq = producerSequence.value)) {
                        head = ring[(int) nextConsumeSeq & mask];
                    }
                    if (running && (head == null || !head.ready()))
                        LockSupport.parkNanos(this, 100_000L);
                } finally { parked = false; }
                spins = 0;
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
     * 读取 JVM 等待策略属性，去首尾空白并转大写，未配置默认 BUSY_SPIN；非法值按配置错误拒绝。
     */
    private static WaitStrategy configuredWaitStrategy() {
        // 从 JVM 属性取得的等待策略文本，缺失时为 BUSY_SPIN。
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.BUSY_SPIN.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (/* 非法等待策略转换时捕获的异常，用来说明配置错误。 */ RuntimeException failure) {
            throw new IllegalArgumentException(
                    WAIT_STRATEGY_PROPERTY + " must be BUSY_SPIN or BLOCKING", failure);
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
