package com.surprising.aeron.service.state;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** The original Treasury ledger has one writer; the Owner only publishes financial work. */
public final class SystemLedgerLane implements AutoCloseable {
    /** Input and completion belong to the existing command slot throughout this handoff. */
    public interface Entry {
        void apply(TreasuryRuntime ledger);
        default boolean ready() { return true; }
    }

    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.system-ledger-wait-strategy";
    public enum WaitStrategy { BUSY_SPIN, BLOCKING }

    private static class SequenceLhsPadding {
        @SuppressWarnings("unused")
        protected long p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15;
    }

    private static class SequenceValue extends SequenceLhsPadding {
        protected volatile long value = -1L;
    }

    private static final class PaddedSequence extends SequenceValue {
        @SuppressWarnings("unused")
        protected long p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30;
    }

    private final TreasuryRuntime ledger;
    private final Entry[] ring;
    private final int capacity;
    private final int mask;
    private final PaddedSequence producerSequence = new PaddedSequence();
    private final PaddedSequence consumerSequence = new PaddedSequence();
    /** 账本线程完成 apply 之后才推进；等于 producerSequence 表示所有已入队任务都已彻底执行完。 */
    private final PaddedSequence appliedSequence = new PaddedSequence();
    private long cachedConsumerSequence = -1L;
    private final Thread producer;
    private final Thread thread;
    private final Consumer<Throwable> failurePublisher;
    /** 账本所有权随线程移交时，同步迁移由账本线程独占的业务索引。 */
    private final Consumer<Thread> businessOwnerBinder;
    private final WaitStrategy waitStrategy;
    /** 仅生产者线程读写：true 表示账本当前由 Owner 线程内联持有，账本线程处于静默。 */
    private boolean producerHoldsLedger;
    private volatile boolean running = true;
    private volatile boolean started;
    private volatile boolean parked;
    private volatile Throwable failure;

    SystemLedgerLane(TreasuryRuntime ledger, int capacity, Consumer<Throwable> failurePublisher) {
        this(ledger, capacity, failurePublisher, owner -> { });
    }

    SystemLedgerLane(TreasuryRuntime ledger, int capacity, Consumer<Throwable> failurePublisher,
                     Consumer<Thread> businessOwnerBinder) {
        if (ledger == null || capacity <= 0 || failurePublisher == null || businessOwnerBinder == null)
            throw new IllegalArgumentException("system ledger, capacity and failure publisher are required");
        this.businessOwnerBinder = businessOwnerBinder;
        this.ledger = ledger;
        this.capacity = capacity;
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!started && System.nanoTime() < deadline) LockSupport.parkNanos(this, 100_000L);
        if (!started) {
            running = false;
            throw new IllegalStateException("system ledger Lane did not start");
        }
        assertHealthy();
    }

    boolean offer(Entry entry) {
        if (Thread.currentThread() != producer)
            throw new IllegalStateException("system ledger mailbox has one Owner producer");
        if (entry == null) throw new IllegalArgumentException("system ledger entry is required");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("system ledger Lane is closed");
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
     * FUSED 的唯一同步结算入口：仅当此前所有入队任务都已彻底执行完且任务已就绪时，
     * 由 Owner 线程直接持有账本并 apply，省掉一次跨线程交接与等待。
     * 否则返回 false，调用方回退到 {@link #offer(Entry)} 的异步路径。
     */
    boolean tryApplyInline(Entry entry) {
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

    Thread thread() { return thread; }
    int depth() { return (int) Math.max(0L, producerSequence.value - consumerSequence.value); }
    boolean hasCapacity() {
        if (!running || failure != null) return false;
        long nextSeq = producerSequence.value + 1;
        if (nextSeq - cachedConsumerSequence <= capacity) return true;
        cachedConsumerSequence = consumerSequence.value;
        return nextSeq - cachedConsumerSequence <= capacity;
    }
    boolean parked() { return parked; }

    void assertHealthy() {
        Throwable problem = failure;
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem instanceof Error error) throw error;
        if (problem != null) throw new IllegalStateException("system ledger Lane failed", problem);
    }

    private void run() {
        int spins = 0;
        long nextConsumeSeq = 0;
        long cachedProducerSeq = -1L;
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
                    // The worker now owns the ready head. Release transport capacity before
                    // applying money; business completion still comes from the entry's release
                    // marker. Owner can continuously enqueue even before a response retires.
                    ring[(int) nextConsumeSeq & mask] = null;
                    consumerSequence.value = nextConsumeSeq;
                    long appliedSeq = nextConsumeSeq;
                    nextConsumeSeq++;
                    Entry readyEntry = head;
                    head = null;
                    readyEntry.apply(ledger);
                    // apply may publish completion and immediately allow slot reuse. Never
                    // read entry fields again after that ownership handoff.
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
        } catch (Throwable problem) {
            failure = problem;
            running = false;
            try { failurePublisher.accept(problem); }
            catch (Throwable reportingFailure) { problem.addSuppressed(reportingFailure); }
        } finally {
            ledger.transferOwner(null);
            started = true;
        }
    }

    private static WaitStrategy configuredWaitStrategy() {
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.BUSY_SPIN.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    WAIT_STRATEGY_PROPERTY + " must be BUSY_SPIN or BLOCKING", failure);
        }
    }

    @Override public void close() {
        running = false;
        LockSupport.unpark(thread);
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try { thread.join(10L); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new IllegalStateException("system ledger Lane did not stop");
        assertHealthy();
    }
}
