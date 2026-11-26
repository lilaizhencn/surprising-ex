package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Aeron 客户端单线程出站与回包调度循环：负责建连、心跳、投递、超时与关闭收尾。 */
final class AeronEgressDispatcher implements Runnable {
    private final AeronClientPool pool;
    private final AeronAgentLane[] lanes;
    final Thread thread;
    volatile boolean parked;

    AeronEgressDispatcher(AeronClientPool pool, boolean startAgents) {
        this.pool = pool;
        this.lanes = new AeronAgentLane[pool.commandAgents.length + 1];
        System.arraycopy(pool.commandAgents, 0, lanes, 0, pool.commandAgents.length);
        lanes[lanes.length - 1] = pool.reservedControlAgent;
        this.thread = new Thread(this, pool.clientName + "-egress-dispatcher");
        this.thread.setDaemon(true);
        if (!startAgents) {
            rejectQueuedAsClosed();
        }
    }

    void start() {
        thread.start();
    }

    @Override
    public void run() {
        try {
            openSessions();
            long idleNanos = 100_000L;
            while (!pool.closed.get()) {
                boolean worked = false;
                boolean hasRequests = false;
                for (AeronAgentLane lane : lanes) {
                    worked |= openSession(lane);
                }
                for (AeronAgentLane lane : lanes) {
                    worked |= pollSession(lane);
                }
                for (AeronAgentLane lane : lanes) {
                    worked |= keepAliveSession(lane);
                }
                for (AeronAgentLane lane : lanes) {
                    worked |= admitQueued(lane);
                    expireQueued(lane);
                    expireAdmitted(lane);
                    hasRequests |= !lane.pending.isEmpty() || !lane.mailbox.isEmpty()
                            || lane.deferredAdminOffer != null;
                }
                idleNanos = worked || hasRequests ? 100_000L : Math.min(idleNanos * 2, 1_000_000L);
                if (!worked) {
                    long now = System.nanoTime();
                    long waitNanos = idleNanos;
                    for (AeronAgentLane lane : lanes) {
                        long deadline = lane.session == null ? lane.reconnectAtNanos : lane.keepAliveAtNanos;
                        waitNanos = Math.min(waitNanos, Math.max(1L, deadline - now));
                    }
                    parked = true;
                    try {
                        if (!hasDispatchableRequests()) {
                            LockSupport.parkNanos(this, waitNanos);
                        }
                    } finally {
                        parked = false;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        if (!pool.closed.get()) {
                            pool.dispatcherFailure.compareAndSet(null,
                                    new IllegalStateException("Aeron dispatcher interrupted"));
                        }
                        break;
                    }
                }
            }
        } catch (RuntimeException exception) {
            pool.dispatcherFailure.compareAndSet(null, exception);
        } finally {
            pool.dispatcherStopped.set(true);
            rejectQueuedAsClosed();
            completeAdmittedAsUnknown();
            closeSessions();
            closeMediaDriver();
        }
    }

    private boolean hasDispatchableRequests() {
        for (AeronAgentLane lane : lanes) {
            AeronPoolRequest next = lane.mailbox.peek();
            if (next != null && lane.deferredAdminOffer == null
                    && lane.session != null && lane.session.connected()
                    && (next.oneWay() || lane.pending.size() < lane.maxInFlight)) return true;
        }
        return false;
    }

    private void openSessions() {
        for (AeronAgentLane lane : lanes) {
            if (pool.closed.get()) {
                return;
            }
            openSession(lane);
        }
    }

    private boolean openSession(AeronAgentLane lane) {
        if (pool.closed.get() || lane.session != null || System.nanoTime() < lane.reconnectAtNanos) {
            return false;
        }
        try {
            lane.session = Objects.requireNonNull(pool.sessionFactory.open(), "sessionFactory returned null");
            return true;
        } catch (RuntimeException exception) {
            lane.session = null;
            lane.reconnectLater();
            return true;
        }
    }

    private boolean pollSession(AeronAgentLane lane) {
        AeronClientPool.Session current = lane.session;
        if (current == null) {
            return false;
        }
        try {
            int fragments = current.pollEgress(pool.capacity.egressFragmentLimit());
            if (!lane.pending.isEmpty()) current.drainResponses(lane.responseConsumer);
            RuntimeException failure = current.sessionFailure();
            if (failure != null) {
                lane.pending.values().forEach(AeronPoolRequest::resultUnknown);
                lane.pending.clear();
                closeSession(lane);
            }
            if (failure == null && fragments > 0) {
                lane.connected();
            }
            return fragments > 0;
        } catch (RuntimeException exception) {
            lane.pending.values().forEach(AeronPoolRequest::resultUnknown);
            lane.pending.clear();
            closeSession(lane);
            return true;
        }
    }

    private boolean keepAliveSession(AeronAgentLane lane) {
        AeronClientPool.Session current = lane.session;
        long now = System.nanoTime();
        if (current == null || now < lane.keepAliveAtNanos) {
            return false;
        }
        try {
            if (current.keepAlive()) {
                lane.connected();
                return true;
            }
            lane.keepAliveAtNanos = now + TimeUnit.MILLISECONDS.toNanos(AeronClientPool.KEEP_ALIVE_RETRY_MILLIS);
            return false;
        } catch (RuntimeException exception) {
            lane.pending.values().forEach(AeronPoolRequest::resultUnknown);
            lane.pending.clear();
            closeSession(lane);
            return true;
        }
    }

    private boolean admitQueued(AeronAgentLane lane) {
        if (lane.deferredAdminOffer != null) {
            AeronPoolRequest request = lane.deferredAdminOffer;
            if (System.nanoTime() - request.queueDeadlineNanos >= 0) {
                lane.deferredAdminOffer = null;
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.ADMIN_ACTION));
                return true;
            }
            if (lane.session == null || !lane.session.connected()) return false;
            lane.deferredAdminOffer = null;
            admit(lane, request);
            return lane.deferredAdminOffer == null;
        }
        boolean worked = false;
        while (true) {
            AeronPoolRequest next = lane.mailbox.peek();
            if (next == null || lane.session == null || !lane.session.connected()
                    || (!next.oneWay() && lane.pending.size() >= lane.maxInFlight)) {
                return worked;
            }
            AeronPoolRequest request = lane.mailbox.poll();
            if (request == null) {
                return worked;
            }
            worked = true;
            if (!request.beginOffer()) {
                request.recycle();
                continue;
            }
            admit(lane, request);
            if (lane.deferredAdminOffer != null) return worked;
        }
    }

    private void admit(AeronAgentLane lane, AeronPoolRequest request) {
        AeronClientPool.Session current = lane.session;
        if (current == null) {
            request.notAccepted(CoreCommandOutcome.notAccepted(Publication.NOT_CONNECTED));
            lane.reconnectLater();
            return;
        }
        long offerResult;
        try {
            if (request.commandType != null) {
                request.message = CoreMessage.owned(CoreMessageHeader.command(request.commandType,
                        request.operationId, pool.productLine, CommandSource.GATEWAY, lane.sourceId,
                        ++lane.nextSequence, request.commandUserId, request.commandSubmittedMillis,
                        request.correlationId), request.commandPayload);
                request.commandType = null;
                request.commandPayload = null;
            }
            offerResult = current.offer(request.message);
        } catch (RuntimeException exception) {
            request.notAccepted(CoreCommandOutcome.notAccepted(Long.MIN_VALUE));
            closeSession(lane);
            return;
        }
        if (offerResult > 0) {
            AeronClientPool.ClientTransportBoundary.record("offered", request.operationId);
            lane.connected();
            if (!request.oneWay()) {
                request.deadlineNanos = System.nanoTime() + pool.responseTimeout.toNanos();
                lane.pending.put(request.message.header().correlationId(), request);
            } else {
                request.acceptedOneWay(offerResult);
            }
        } else {
            if (offerResult == Publication.ADMIN_ACTION && !request.oneWay()) {
                pool.adminActionRetries.incrementAndGet();
                lane.deferredAdminOffer = request;
                return;
            }
            if ((offerResult == Publication.NOT_CONNECTED || offerResult == Publication.CLOSED)
                    && request.isQuery()) {
                closeSession(lane);
                request.prepareForRetry();
                if (lane.mailbox.offer(request)) {
                    return;
                }
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
                return;
            }
            request.notAccepted(CoreCommandOutcome.notAccepted(offerResult));
            if (offerResult == Publication.NOT_CONNECTED
                    || offerResult == Publication.CLOSED
                    || offerResult == Publication.MAX_POSITION_EXCEEDED) {
                closeSession(lane);
            }
        }
    }

    private void expireAdmitted(AeronAgentLane lane) {
        if (lane.pendingInDeadlineOrder.isEmpty()) return;
        long now = System.nanoTime();
        boolean expired = now - lane.pendingInDeadlineOrder.getFirst().deadlineNanos >= 0;
        if (expired) {
            lane.pending.values().forEach(AeronPoolRequest::resultUnknown);
            lane.pending.clear();
            closeSession(lane);
        }
    }

    private void expireQueued(AeronAgentLane lane) {
        if (lane.mailbox.isEmpty()) return;
        long now = System.nanoTime();
        long rejection = lane.session == null || !lane.session.connected()
                ? Publication.NOT_CONNECTED
                : Publication.BACK_PRESSURED;
        lane.mailbox.removeIf(request -> {
            if (!request.queueExpired(now)) {
                return false;
            }
            request.notAccepted(CoreCommandOutcome.notAccepted(rejection));
            return true;
        });
    }

    private void rejectQueuedAsClosed() {
        for (AeronAgentLane lane : lanes) {
            if (lane.deferredAdminOffer != null) {
                AeronPoolRequest deferred = lane.deferredAdminOffer;
                lane.deferredAdminOffer = null;
                deferred.notAccepted(CoreCommandOutcome.notAccepted(Publication.CLOSED));
            }
            AeronPoolRequest request;
            while ((request = lane.mailbox.poll()) != null) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.CLOSED));
            }
        }
    }

    private void completeAdmittedAsUnknown() {
        for (AeronAgentLane lane : lanes) {
            lane.pending.values().forEach(AeronPoolRequest::resultUnknown);
            lane.pending.clear();
        }
    }

    private void closeSessions() {
        for (AeronAgentLane lane : lanes) {
            try {
                closeSession(lane);
            } catch (RuntimeException exception) {
                RuntimeException first = pool.dispatcherFailure.get();
                if (first == null) {
                    pool.dispatcherFailure.compareAndSet(null, exception);
                } else {
                    first.addSuppressed(exception);
                }
            }
        }
    }

    private void closeSession(AeronAgentLane lane) {
        AeronClientPool.Session current = lane.session;
        lane.session = null;
        try {
            if (current != null) {
                current.close();
            }
        } finally {
            if (!pool.closed.get() && !pool.dispatcherStopped.get()) {
                lane.reconnectLater();
            }
        }
    }

    RuntimeException stop() {
        boolean interrupted = false;
        if (thread.isAlive()) {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(AeronClientPool.DISPATCHER_CLOSE_TIMEOUT_MILLIS);
            while (thread.isAlive() && System.nanoTime() < deadline) {
                try {
                    long remaining = Math.max(1L, deadline - System.nanoTime());
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
                } catch (InterruptedException exception) {
                    interrupted = true;
                    thread.interrupt();
                }
            }
            if (thread.isAlive()) {
                thread.interrupt();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (thread.isAlive() && System.nanoTime() < deadline) {
                    try {
                        long remaining = Math.max(1L, deadline - System.nanoTime());
                        TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                        thread.interrupt();
                    }
                }
            }
            if (interrupted) {
                pool.dispatcherFailure.compareAndSet(null,
                        new IllegalStateException("Interrupted while stopping Aeron dispatcher"));
            }
            if (thread.isAlive()) {
                pool.dispatcherFailure.compareAndSet(null,
                        new IllegalStateException("Aeron dispatcher did not terminate: " + thread.getName()));
            }
        } else {
            rejectQueuedAsClosed();
            completeAdmittedAsUnknown();
            closeSessions();
            closeMediaDriver();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return pool.dispatcherFailure.get();
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    private void closeMediaDriver() {
        MediaDriver driver;
        synchronized (pool.mediaDriver) {
            driver = pool.mediaDriver.getAndSet(null);
        }
        if (driver == null || !pool.ownsMediaDriver) {
            return;
        }
        try {
            driver.close();
        } catch (RuntimeException exception) {
            RuntimeException first = pool.dispatcherFailure.get();
            if (first == null) {
                pool.dispatcherFailure.compareAndSet(null, exception);
            } else {
                first.addSuppressed(exception);
            }
        }
    }
}
