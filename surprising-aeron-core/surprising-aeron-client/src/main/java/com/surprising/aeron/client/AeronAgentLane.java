package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import io.aeron.Publication;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/** Aeron 客户端池单会话通道：维护邮箱、请求槽池、在途超时表与重连退避状态。 */
final class AeronAgentLane {
    final AeronClientPool pool;
    final ArrayBlockingQueue<AeronPoolRequest> mailbox;
    final ConcurrentLinkedQueue<AeronPoolRequest> availableRequests;
    final int maxInFlight;
    final long sourceId;
    long nextSequence;
    final AtomicLong nextCorrelation = new AtomicLong();
    final LinkedHashMap<Long, AeronPoolRequest> pending = new LinkedHashMap<>();
    final SequencedCollection<AeronPoolRequest> pendingInDeadlineOrder = pending.sequencedValues();
    final BiConsumer<Long, CoreResponse> responseConsumer = (id, response) -> {
        AeronPoolRequest request = pending.remove(id);
        if (request != null) request.terminal(response);
    };
    final Set<Long> claimedCorrelations = ConcurrentHashMap.newKeySet();
    volatile AeronClientPool.Session session;
    long reconnectAtNanos;
    long reconnectMillis = AeronClientPool.MIN_RECONNECT_MILLIS;
    long keepAliveAtNanos;
    AeronPoolRequest deferredAdminOffer;

    AeronAgentLane(AeronClientPool pool, int mailboxCapacity, int maxInFlight, long sourceId) {
        this.pool = pool;
        this.mailbox = new ArrayBlockingQueue<>(mailboxCapacity);
        this.availableRequests = new ConcurrentLinkedQueue<>();
        for (int index = 0; index < mailboxCapacity + maxInFlight; index++) {
            availableRequests.add(new AeronPoolRequest(this));
        }
        this.maxInFlight = maxInFlight;
        this.sourceId = sourceId;
    }

    boolean sessionConnected() {
        AeronClientPool.Session current = session;
        return current != null && current.connected();
    }

    AeronPoolRequest commandOutcomeRequest(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return commandRequest(type, commandId, userId, payload, AeronPoolRequest.Mode.COMMAND_OUTCOME, null);
    }

    AeronPoolRequest commandResponseRequest(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return commandRequest(type, commandId, userId, payload, AeronPoolRequest.Mode.COMMAND_RESPONSE, null);
    }

    AeronPoolRequest oneWayFutureRequest(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return commandRequest(type, commandId, userId, payload, AeronPoolRequest.Mode.ONE_WAY_FUTURE, null);
    }

    AeronPoolRequest oneWayCallbackRequest(CoreMessageType type, UUID commandId, long userId, byte[] payload,
                                           AeronClientPool.CommandOrderCheckCallback callback) {
        return commandRequest(type, commandId, userId, payload, AeronPoolRequest.Mode.ONE_WAY_CALLBACK, callback);
    }

    private AeronPoolRequest commandRequest(CoreMessageType type, UUID commandId, long userId, byte[] payload,
                                            AeronPoolRequest.Mode mode, AeronClientPool.CommandOrderCheckCallback callback) {
        AeronPoolRequest request = availableRequests.poll();
        if (request == null) {
            return null;
        }
        long correlationId = nextCorrelation.incrementAndGet();
        if (mode.oneWay) {
            correlationId = -correlationId;
        }
        request.reset(null, commandId, mode, callback, false);
        request.correlationId = correlationId;
        request.commandType = type;
        request.commandUserId = userId;
        request.commandSubmittedMillis = Instant.now().toEpochMilli();
        request.commandPayload = payload.length == 0 ? payload : Arrays.copyOf(payload, payload.length);
        return request;
    }

    AeronPoolRequest queryRequest(CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        AeronPoolRequest request = availableRequests.poll();
        if (request == null) {
            return null;
        }
        long correlationId = nextCorrelation.incrementAndGet();
        CoreMessage message = new CoreMessage(CoreMessageHeader.query(type, queryId, pool.productLine,
                CommandSource.GATEWAY, sourceId, 0, userId, Instant.now().toEpochMilli(),
                correlationId), payload);
        request.reset(message, queryId, AeronPoolRequest.Mode.QUERY, null, false);
        return request;
    }

    AeronPoolRequest preparedRequest(CoreMessage message, AeronPoolRequest.Mode mode) {
        AeronPoolRequest request = availableRequests.poll();
        if (request != null) {
            request.reset(message, message.header().commandId(), mode, null, true);
        }
        return request;
    }

    boolean enqueue(AeronPoolRequest request) {
        if (request.preparedCorrelation) {
            long correlationId = request.correlationId;
            if (!claimedCorrelations.add(correlationId)) {
                request.fail(new IllegalStateException(
                        "duplicate in-flight Aeron correlationId=" + correlationId));
                return true;
            }
            request.correlationClaimed = true;
        }
        RuntimeException failure = pool.dispatcherFailure.get();
        if (failure != null) {
            request.fail(failure);
            return true;
        }
        if (pool.closed.get() || pool.dispatcherStopped.get()) {
            request.notAccepted(CoreCommandOutcome.notAccepted(Publication.CLOSED));
            return true;
        }
        request.startQueueTimeout(pool.responseTimeout.toNanos());
        AeronClientPool.ClientTransportBoundary.record("queued", request.operationId);
        if (!mailbox.offer(request)) {
            request.releaseCorrelation();
            return false;
        }
        if (pool.egressDispatcher.parked) {
            LockSupport.unpark(pool.egressDispatcher.thread);
        }
        failure = pool.dispatcherFailure.get();
        if ((pool.closed.get() || pool.dispatcherStopped.get() || failure != null) && mailbox.remove(request)) {
            if (failure == null) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.CLOSED));
            } else {
                request.fail(failure);
            }
        }
        return true;
    }

    void rejectUnqueued(AeronPoolRequest request, CoreCommandOutcome.NotAccepted rejection) {
        request.notAccepted(rejection);
    }

    void recycleUnqueued(AeronPoolRequest request) {
        request.recycle();
    }

    void recycle(AeronPoolRequest request) {
        request.releaseCorrelation();
        request.clear();
        if (!availableRequests.offer(request)) {
            pool.dispatcherFailure.compareAndSet(null,
                    new IllegalStateException("Aeron request slot returned twice"));
        }
    }

    void connected() {
        reconnectAtNanos = 0;
        reconnectMillis = AeronClientPool.MIN_RECONNECT_MILLIS;
        keepAliveAtNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(AeronClientPool.KEEP_ALIVE_INTERVAL_MILLIS);
    }

    void reconnectLater() {
        reconnectAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(reconnectMillis);
        reconnectMillis = Math.min(AeronClientPool.MAX_RECONNECT_MILLIS, Math.multiplyExact(reconnectMillis, 2));
        keepAliveAtNanos = 0;
    }

    void releaseCorrelation(long correlationId) {
        claimedCorrelations.remove(correlationId);
    }
}
