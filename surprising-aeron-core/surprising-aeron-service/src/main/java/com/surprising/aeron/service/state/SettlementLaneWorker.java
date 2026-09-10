package com.surprising.aeron.service.state;

import java.util.Locale;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Permanent SPSC event loop for one Account Lane. */
final class SettlementLaneWorker implements AutoCloseable {
    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.settlement-wait-strategy";
    private static final String SPIN_LIMIT_PROPERTY = "surprising.aeron.settlement-spin-limit";

    interface Command {
        void execute(AccountLaneState lane);
    }

    private final Command[] commands;
    private final int indexMask;
    private final WaitStrategy waitStrategy;
    private final int spinLimit;
    private final AccountLaneState lane;
    private final Thread thread;
    private final PaddedSequence producerSequence = new PaddedSequence();
    private final PaddedSequence consumerSequence = new PaddedSequence();
    private volatile boolean started;
    private volatile boolean running = true;
    private volatile Throwable failure;
    /** Lane先声明休眠再重读工作/恢复序号；生产者CAS认领一次唤醒，合并同轮后续通知。 */
    private volatile boolean parkRequested;
    private static final VarHandle PARK_REQUESTED;
    static {
        try { PARK_REQUESTED = MethodHandles.lookup().findVarHandle(SettlementLaneWorker.class, "parkRequested", boolean.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity) {
        if (role == null || role.isBlank() || lane == null || requestedCapacity <= 0) {
            throw new IllegalArgumentException("invalid settlement lane worker");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        commands = new Command[capacity];
        indexMask = capacity - 1;
        waitStrategy = configuredWaitStrategy();
        spinLimit = Integer.parseInt(System.getProperty(SPIN_LIMIT_PROPERTY, "0"));
        if (spinLimit < 0 || spinLimit > 4096)
            throw new IllegalArgumentException("settlement spin limit must be in [0,4096]");
        this.lane = lane;
        thread = Thread.ofPlatform().daemon(true)
                .name("core-" + role + "-lane-" + lane.laneId())
                .start(this::run);
        while (!started && failure == null) Thread.onSpinWait();
        rethrowFailure();
    }

    /** owner 请求的交接代次；同一代次只派发一次预分配任务。 */
    private long requestedHandoff;
    /** Lane 完成此前所有任务并释放所有权后发布的代次。 */
    private volatile long completedHandoff;
    /** owner 释放临时所有权后发布的恢复代次。 */
    private volatile long resumedHandoff;
    /** 控制阶段交接任务不修改资金，仅转移唯一写入权。 */
    private final Command handoffCommand = lane -> {
        lane.releaseOwnerForHandoff();
        completedHandoff = requestedHandoff;
    };

    void requestHandoff(long epoch) {
        requestedHandoff = epoch;
        submit(handoffCommand);
    }
    boolean handoffReady(long epoch) { return completedHandoff == epoch; }
    void resumeHandoff(long epoch) {
        resumedHandoff = epoch;
        signalWork();
    }

    long submit(Command command) {
        if (command == null) throw new IllegalArgumentException("settlement command is required");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        long next = producerSequence.value;
        long consumed = consumerSequence.value;
        if (next - consumed >= commands.length) {
            throw new RejectedExecutionException("settlement lane queue is full");
        }
        commands[(int) next & indexMask] = command;
        producerSequence.value = next + 1;
        // The consumer announces parking BEFORE rechecking producerSequence. These volatile
        // accesses ensure either it sees this publication or we observe its wake-up request.
        // Do not replace this handshake with an empty-queue check on consumerSequence.
        signalWork();
        return next + 1;
    }

    /** 同一次休眠只唤醒一次；CAS失败说明已通知或Lane已恢复，不能省略消费者的重读。 */
    private void signalWork() {
        if (waitStrategy == WaitStrategy.BLOCKING && parkRequested
                && PARK_REQUESTED.compareAndSet(this, true, false)) LockSupport.unpark(thread);
    }

    int depth() {
        return Math.toIntExact(producerSequence.value - consumerSequence.value);
    }

    boolean hasCapacity() {
        return producerSequence.value - consumerSequence.value < commands.length;
    }

    Throwable failure() {
        return failure;
    }

    void assertHealthy() {
        rethrowFailure();
    }

    private void run() {
        long next = consumerSequence.value;
        int idleSpins = 0;
        try {
            lane.bindOwner();
            started = true;
            long reboundHandoff = 0;
            while (running || next < producerSequence.value) {
                if (completedHandoff > reboundHandoff) {
                    if (resumedHandoff < completedHandoff) {
                        if (!running) break;
                        switch (waitStrategy) {
                            case BUSY_SPIN -> Thread.onSpinWait();
                            case YIELDING -> Thread.yield();
                            case BLOCKING -> {
                                parkRequested = true;
                                try {
                                    if (running && resumedHandoff < completedHandoff) LockSupport.park(this);
                                } finally { parkRequested = false; }
                            }
                        }
                        continue;
                    }
                    lane.bindOwner();
                    reboundHandoff = completedHandoff;
                }
                if (next < producerSequence.value) {
                    int index = (int) next & indexMask;
                    Command command = commands[index];
                    if (command == null) throw new IllegalStateException("settlement lane publication gap");
                    // The command reference is now local, so the ring slot can be released before
                    // execution publishes its terminal notification. This makes queue depth and
                    // capacity describe queued work only; observing terminal completion can no
                    // longer race the worker's trailing cursor bookkeeping.
                    commands[index] = null;
                    next++;
                    consumerSequence.value = next;
                    command.execute(lane);
                    idleSpins = 0;
                    continue;
                }
                switch (waitStrategy) {
                    case BUSY_SPIN -> Thread.onSpinWait();
                    case YIELDING -> Thread.yield();
                    case BLOCKING -> {
                        if (idleSpins < spinLimit) {
                            idleSpins++;
                            Thread.onSpinWait();
                        } else {
                            parkRequested = true;
                            try {
                                if (running && next >= producerSequence.value) LockSupport.park(this);
                            } finally {
                                parkRequested = false;
                            }
                        }
                    }
                }
            }
        } catch (Throwable laneFailure) {
            failure = laneFailure;
            running = false;
            started = true;
        } finally {
            lane.releaseOwnerForHandoff();
        }
    }

    private void rethrowFailure() {
        Throwable laneFailure = failure;
        if (laneFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (laneFailure instanceof Error error) throw error;
        if (laneFailure != null) throw new IllegalStateException("account lane failed", laneFailure);
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                thread.join(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new IllegalStateException("settlement lane did not stop");
        rethrowFailure();
    }

    private static WaitStrategy configuredWaitStrategy() {
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.BLOCKING.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "settlement wait strategy must be BUSY_SPIN, YIELDING or BLOCKING", exception);
        }
    }

    private enum WaitStrategy { BUSY_SPIN, YIELDING, BLOCKING }

    private static final class PaddedSequence {
        @SuppressWarnings("unused")
        private long p01, p02, p03, p04, p05, p06, p07;
        private volatile long value;
        @SuppressWarnings("unused")
        private long p11, p12, p13, p14, p15, p16, p17;
    }
}
