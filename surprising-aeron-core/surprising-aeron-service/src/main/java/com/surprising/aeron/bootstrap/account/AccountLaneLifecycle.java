package com.surprising.aeron.bootstrap.account;

import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 账户执行资源的唯一生命周期：创建/回滚/关闭工作线程，并按原栅栏协议交接账户所有权。 */
public final class AccountLaneLifecycle implements AutoCloseable {
    private final AccountLaneState[] lanes;
    private final SettlementLaneWorker[] workers;
    private final LaneTopology topology;
    private final CoreRuntimeConfiguration configuration;
    private final Runnable assertOwner;
    private final Runnable completionSignal;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean started;
    private boolean ownerHasAccess = true;
    private volatile long handoffEpoch;
    private boolean handoffRequested;
    private long handoffDeadline;
    private SystemLedgerLane ledger;
    private Consumer<Thread> ledgerOwner;

    public AccountLaneLifecycle(AccountLaneState[] lanes, LaneTopology topology,
            CoreRuntimeConfiguration configuration, Runnable assertOwner, Runnable completionSignal) {
        this.lanes = lanes;
        this.topology = topology;
        this.configuration = configuration;
        this.assertOwner = assertOwner;
        this.completionSignal = completionSignal;
        workers = new SettlementLaneWorker[lanes.length];
    }

    public boolean started() { return started; }
    public boolean ownerHasAccess() { return ownerHasAccess; }
    public long handoffEpoch() { return handoffEpoch; }
    public SystemLedgerLane ledger() { return ledger; }

    /** 只借出指定 Lane 的工作入口，不暴露可替换的资源数组；队列仍校验各生产者契约。 */
    public SettlementLaneWorker worker(int laneId) { return workers[laneId]; }

    public void start(TradingCpuAffinity affinity) {
        assertOwner.run();
        if (started) throw new IllegalStateException("account lanes are already started");
        try {
            for (int laneId = 0; laneId < lanes.length; laneId++) {
                workers[laneId] = new SettlementLaneWorker("account", lanes[laneId],
                        topology.accountLaneQueueCapacity(), this::recordFailure,
                        topology.matchingEngineCount(), affinity, configuration);
            }
            started = true;
            ownerHasAccess = false;
        } catch (RuntimeException | Error problem) {
            closeAfterFailure(problem);
            throw problem;
        }
    }

    public void startLedger(TreasuryRuntime treasury, int capacity, Consumer<Thread> bindBusinessOwner) {
        assertOwner.run();
        if (ledger != null) return;
        if (treasury.hasChangedValues())
            throw new IllegalStateException("Ledger activation requires committed Treasury");
        ledgerOwner = bindBusinessOwner;
        ledger = new SystemLedgerLane(treasury, capacity, this::recordFailure);
        bindBusinessOwner.accept(ledger.thread());
    }

    /** 非阻塞取得所有账户 Lane；完成标记是事实来源，轮次仅表示本次交接。 */
    public boolean tryAcquireOwnerAccess() {
        assertOwner.run();
        assertHealthy();
        if (ownerHasAccess) return true;
        if (!started) { ownerHasAccess = true; return true; }
        if (!handoffRequested) {
            handoffEpoch = Math.incrementExact(handoffEpoch);
            handoffRequested = true;
            handoffDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (SettlementLaneWorker worker : workers) worker.requestHandoff(handoffEpoch);
        }
        for (SettlementLaneWorker worker : workers) {
            if (!worker.handoffReady(handoffEpoch)) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - handoffDeadline >= 0)
                    throw new IllegalStateException("Account Lane ownership handoff interrupted or timed out");
                return false;
            }
        }
        for (AccountLaneState lane : lanes) lane.bindOwner();
        ownerHasAccess = true;
        return true;
    }

    public void releaseOwnerAccess() {
        assertOwner.run();
        if (ownerHasAccess && started)
            for (AccountLaneState lane : lanes) lane.releaseOwnerForHandoff();
        ownerHasAccess = false;
        if (handoffRequested) {
            for (SettlementLaneWorker worker : workers) worker.resumeHandoff(handoffEpoch);
            handoffRequested = false;
        }
    }

    public void assertHealthy() {
        Throwable problem = failure.get();
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem instanceof Error error) throw error;
        if (problem != null) throw new IllegalStateException("account lane failed", problem);
    }

    private void recordFailure(Throwable problem) {
        failure.compareAndSet(null, problem);
        completionSignal.run();
    }

    private void closeAfterFailure(Throwable problem) {
        try { close(); }
        catch (RuntimeException | Error cleanup) { if (cleanup != problem) problem.addSuppressed(cleanup); }
    }

    public boolean workersStopped() {
        for (SettlementLaneWorker worker : workers)
            if (worker != null && worker.thread().isAlive()) return false;
        return ledger == null || !ledger.thread().isAlive();
    }

    @Override
    public void close() {
        Throwable problem = null;
        try { releaseOwnerAccess(); }
        catch (RuntimeException | Error releaseFailure) { problem = releaseFailure; }
        started = false;
        for (SettlementLaneWorker worker : workers) {
            try { if (worker != null) worker.close(); }
            catch (RuntimeException | Error cleanup) {
                if (problem == null) problem = cleanup;
                else if (cleanup != problem) problem.addSuppressed(cleanup);
            }
        }
        try { if (ledger != null) ledger.close(); }
        catch (RuntimeException | Error cleanup) {
            if (problem == null) problem = cleanup;
            else if (cleanup != problem) problem.addSuppressed(cleanup);
        } finally {
            if (ledger != null && !ledger.thread().isAlive()) {
                ledgerOwner.accept(null);
                ledger = null;
            }
        }
        if (problem instanceof Error error) throw error;
        if (problem != null) throw new IllegalStateException("Core workers stopped with a failure", problem);
    }
}
