package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 可复用的 Aeron 客户端请求槽：通过代际+阶段单次 CAS 保护超时取消与回收。 */
final class AeronPoolRequest {
    enum Mode {
        COMMAND_OUTCOME(false),
        COMMAND_RESPONSE(false),
        QUERY(false),
        ONE_WAY_FUTURE(true),
        ONE_WAY_CALLBACK(true);

        final boolean oneWay;

        Mode(boolean oneWay) {
            this.oneWay = oneWay;
        }
    }

    private static final long PHASE_MASK = 3, QUEUED = 0, OFFERING = 1, CANCELLED = 2, COMPLETED = 3;
    private static final VarHandle LIFECYCLE;

    static {
        try {
            LIFECYCLE = MethodHandles.lookup().findVarHandle(AeronPoolRequest.class, "lifecycle", long.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    final AeronAgentLane owner;
    CoreMessage message;
    long correlationId;
    CoreMessageType commandType;
    long commandUserId;
    long commandSubmittedMillis;
    byte[] commandPayload;
    UUID operationId;
    Mode mode;
    CompletableFuture<CoreCommandOutcome> commandFuture;
    CompletableFuture<CoreResponse> responseFuture;
    CompletableFuture<Long> orderCheckFuture;
    AeronClientPool.CommandOrderCheckCallback orderCheckCallback;
    private volatile long lifecycle = COMPLETED;
    boolean preparedCorrelation;
    boolean correlationClaimed;
    long queueDeadlineNanos;
    long deadlineNanos;

    AeronPoolRequest(AeronAgentLane owner) {
        this.owner = owner;
    }

    void reset(CoreMessage message, UUID operationId, Mode mode,
               AeronClientPool.CommandOrderCheckCallback orderCheckCallback, boolean preparedCorrelation) {
        this.message = message;
        this.correlationId = message == null ? 0 : message.header().correlationId();
        this.commandType = null;
        this.commandPayload = null;
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.commandFuture = mode == Mode.COMMAND_OUTCOME ? new CompletableFuture<>() : null;
        this.responseFuture = mode == Mode.COMMAND_RESPONSE || mode == Mode.QUERY
                ? new CompletableFuture<>() : null;
        this.orderCheckFuture = mode == Mode.ONE_WAY_FUTURE ? new CompletableFuture<>() : null;
        this.orderCheckCallback = orderCheckCallback;
        this.preparedCorrelation = preparedCorrelation;
        this.correlationClaimed = false;
        this.queueDeadlineNanos = 0;
        this.deadlineNanos = 0;
        long nextGeneration = Math.incrementExact(lifecycle >>> 2);
        if (nextGeneration > (Long.MAX_VALUE >>> 1))
            throw new IllegalStateException("Aeron request generation exhausted");
        lifecycle = nextGeneration << 2;
    }

    long generation() {
        return lifecycle >>> 2;
    }

    boolean oneWay() {
        return mode.oneWay;
    }

    boolean beginOffer() {
        long current = lifecycle;
        return (current & PHASE_MASK) == QUEUED
                && LIFECYCLE.compareAndSet(this, current, current | OFFERING);
    }

    void prepareForRetry() {
        long current = lifecycle;
        if ((current & PHASE_MASK) != OFFERING
                || !LIFECYCLE.compareAndSet(this, current, current & ~PHASE_MASK))
            throw new IllegalStateException("query retry lost its request lease");
    }

    void startQueueTimeout(long timeoutNanos) {
        if (queueDeadlineNanos == 0) {
            queueDeadlineNanos = Math.addExact(System.nanoTime(), timeoutNanos);
        }
    }

    boolean queueExpired(long now) {
        long phase = lifecycle & PHASE_MASK;
        return phase != OFFERING && (phase == CANCELLED || queueDeadlineNanos != 0 && now >= queueDeadlineNanos);
    }

    boolean isQuery() {
        return mode == Mode.QUERY;
    }

    boolean cancelBeforeOffer(long expectedGeneration) {
        long current = lifecycle;
        while ((current >>> 2) == expectedGeneration) {
            long phase = current & PHASE_MASK;
            if (phase == OFFERING || phase == COMPLETED) return false;
            if (LIFECYCLE.compareAndSet(this, current, (current & ~PHASE_MASK) | CANCELLED)) return true;
            current = lifecycle;
        }
        return false;
    }

    void acceptedOneWay(long offerResult) {
        if (!claimCompletion()) {
            return;
        }
        CompletableFuture<Long> future = orderCheckFuture;
        AeronClientPool.CommandOrderCheckCallback callback = orderCheckCallback;
        UUID commandId = operationId;
        if (future != null) {
            future.complete(offerResult);
        }
        try {
            invokeCallback(callback, commandId, AeronClientPool.TryCommandResult.SENT);
        } finally {
            owner.recycle(this);
        }
    }

    void terminal(CoreResponse response) {
        if (!claimCompletion()) {
            return;
        }
        AeronClientPool.ClientTransportBoundary.record("delivered", operationId);
        CompletableFuture<CoreCommandOutcome> outcome = commandFuture;
        CompletableFuture<CoreResponse> result = responseFuture;
        if (outcome != null) {
            outcome.complete(new CoreCommandOutcome.Terminal(response));
        } else if (result != null) {
            result.complete(response);
        }
        owner.recycle(this);
    }

    void notAccepted(CoreCommandOutcome.NotAccepted rejection) {
        if (!claimCompletion()) {
            return;
        }
        CompletableFuture<Long> orderCheck = orderCheckFuture;
        CompletableFuture<CoreCommandOutcome> outcome = commandFuture;
        CompletableFuture<CoreResponse> result = responseFuture;
        AeronClientPool.CommandOrderCheckCallback callback = orderCheckCallback;
        UUID commandId = operationId;
        if (orderCheck != null) {
            orderCheck.complete(rejection.rawOfferResult());
        }
        if (outcome != null) {
            outcome.complete(rejection);
        } else if (result != null) {
            result.completeExceptionally(new CoreCommandOutcome.NotAcceptedException(rejection));
        }
        try {
            invokeCallback(callback, commandId, AeronClientPool.mapTryCommandResult(rejection.rawOfferResult()));
        } finally {
            owner.recycle(this);
        }
    }

    void resultUnknown() {
        if (!claimCompletion()) {
            return;
        }
        CompletableFuture<CoreCommandOutcome> outcome = commandFuture;
        CompletableFuture<CoreResponse> result = responseFuture;
        UUID commandId = operationId;
        if (outcome != null) {
            outcome.complete(new CoreCommandOutcome.ResultUnknown(commandId));
        } else if (result != null) {
            result.completeExceptionally(new ResultUnknownException(commandId,
                    "Aeron request was admitted but its result is unknown"));
        }
        owner.recycle(this);
    }

    void fail(RuntimeException failure) {
        if (!claimCompletion()) {
            return;
        }
        CompletableFuture<Long> orderCheck = orderCheckFuture;
        CompletableFuture<CoreCommandOutcome> outcome = commandFuture;
        CompletableFuture<CoreResponse> result = responseFuture;
        AeronClientPool.CommandOrderCheckCallback callback = orderCheckCallback;
        UUID commandId = operationId;
        if (orderCheck != null) {
            orderCheck.completeExceptionally(failure);
        }
        if (outcome != null) {
            outcome.completeExceptionally(failure);
        } else if (result != null) {
            result.completeExceptionally(failure);
        }
        try {
            invokeCallback(callback, commandId, AeronClientPool.TryCommandResult.UNAVAILABLE);
        } finally {
            owner.recycle(this);
        }
    }

    void recycle() {
        if (claimCompletion()) {
            owner.recycle(this);
        }
    }

    private boolean claimCompletion() {
        long current = lifecycle;
        while ((current & PHASE_MASK) != COMPLETED) {
            if (LIFECYCLE.compareAndSet(this, current, current | COMPLETED)) return true;
            current = lifecycle;
        }
        return false;
    }

    void clear() {
        message = null;
        commandType = null;
        commandPayload = null;
        correlationId = 0;
        operationId = null;
        mode = null;
        commandFuture = null;
        responseFuture = null;
        orderCheckFuture = null;
        orderCheckCallback = null;
        queueDeadlineNanos = 0;
        deadlineNanos = 0;
    }

    void releaseCorrelation() {
        if (correlationClaimed) {
            correlationClaimed = false;
            owner.releaseCorrelation(correlationId);
        }
    }

    private static void invokeCallback(
            AeronClientPool.CommandOrderCheckCallback callback, UUID commandId,
            AeronClientPool.TryCommandResult result) {
        if (callback != null) {
            try {
                callback.onOrderCheck(commandId, result);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
