package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class AeronClientPool implements AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AeronClientPool.class);

    static final long DISPATCHER_CLOSE_TIMEOUT_MILLIS = 5_000L;
    static final long MIN_RECONNECT_MILLIS = 25L;
    static final long MAX_RECONNECT_MILLIS = 1_000L;
    static final long KEEP_ALIVE_INTERVAL_MILLIS = 1_000L;
    static final long KEEP_ALIVE_RETRY_MILLIS = 25L;

    public enum TryCommandResult {
        SENT,
        BUSY,
        NOT_READY,
        BACK_PRESSURED,
        UNAVAILABLE,
        CLOSED
    }

    @FunctionalInterface
    public interface CommandOrderCheckCallback {
        void onOrderCheck(UUID commandId, TryCommandResult result);
    }

    interface Session extends AutoCloseable {
        long offer(CoreMessage message);
        int pollEgress(int fragmentLimit);
        void drainResponses(java.util.function.BiConsumer<Long, CoreResponse> consumer);
        RuntimeException sessionFailure();
        default boolean connected() {
            return true;
        }
        boolean keepAlive();
        @Override
        void close();
    }

    @FunctionalInterface
    interface SessionFactory {
        Session open();
    }

    final String clientName;
    final ProductLine productLine;
    private final List<String> hostnames;
    private final String egressHostname;
    final Duration responseTimeout;
    private final String sourceIdentity;
    private final String sourceEpoch;
    final AeronClientCapacity capacity;
    final SessionFactory sessionFactory;
    final AeronAgentLane[] commandAgents;
    final AeronAgentLane reservedControlAgent;
    final AeronEgressDispatcher egressDispatcher;
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicBoolean dispatcherStopped = new AtomicBoolean();
    final AtomicReference<MediaDriver> mediaDriver = new AtomicReference<>();
    final boolean ownsMediaDriver;
    final AtomicReference<RuntimeException> dispatcherFailure = new AtomicReference<>();
    final AtomicLong adminActionRetries = new AtomicLong();

    public long adminActionRetries() { return adminActionRetries.get(); }

    public AeronClientPool(
            String clientName,
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            int clientConnections) {
        this(clientName, productLine, hostnames, egressHostname, responseTimeout, clientConnections, clientName);
    }

    public AeronClientPool(
            String clientName,
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            int clientConnections,
            String sourceIdentity) {
        this(clientName, productLine, hostnames, egressHostname, responseTimeout, clientConnections,
                sourceIdentity, UUID.randomUUID().toString());
    }

    /** The caller owns this driver and must keep it alive until all borrowing pools close. */
    public AeronClientPool(
            String clientName, ProductLine productLine, List<String> hostnames, String egressHostname,
            Duration responseTimeout, int clientConnections, String sourceIdentity, MediaDriver driver) {
        this(clientName, productLine, hostnames, egressHostname, responseTimeout, sourceIdentity,
                UUID.randomUUID().toString(), AeronClientCapacity.defaults().withCommandSessions(clientConnections),
                null, true, Objects.requireNonNull(driver, "driver"));
    }

    public AeronClientPool(
            String clientName,
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            int clientConnections,
            String sourceIdentity,
            String sourceEpoch) {
        this(clientName, productLine, hostnames, egressHostname, responseTimeout, sourceIdentity, sourceEpoch,
                AeronClientCapacity.defaults().withCommandSessions(clientConnections), null, true);
    }

    public AeronClientPool(
            String clientName,
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            String sourceIdentity,
            String sourceEpoch,
            AeronClientCapacity capacity) {
        this(clientName, productLine, hostnames, egressHostname, responseTimeout,
                sourceIdentity, sourceEpoch, capacity, null, true);
    }

    AeronClientPool(
            String clientName,
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            String sourceIdentity,
            String sourceEpoch,
            AeronClientCapacity capacity,
            SessionFactory sessionFactory,
            boolean startAgents) {
        this(clientName, productLine, hostnames, egressHostname, responseTimeout, sourceIdentity, sourceEpoch,
                capacity, sessionFactory, startAgents, null);
    }

    AeronClientPool(
            String clientName, ProductLine productLine, List<String> hostnames, String egressHostname,
            Duration responseTimeout, String sourceIdentity, String sourceEpoch, AeronClientCapacity capacity,
            SessionFactory sessionFactory, boolean startAgents, MediaDriver borrowedDriver) {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("clientName is required");
        }
        this.clientName = clientName.trim();
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        com.surprising.aeron.protocol.ProductLineClusterLayout.requireHostnames(hostnames);
        this.hostnames = List.copyOf(hostnames);
        if (egressHostname == null || egressHostname.isBlank()) {
            throw new IllegalArgumentException("egressHostname is required");
        }
        this.egressHostname = egressHostname.trim();
        if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
        this.responseTimeout = responseTimeout;
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new IllegalArgumentException("sourceIdentity is required");
        }
        this.sourceIdentity = sourceIdentity.trim();
        if (sourceEpoch == null || sourceEpoch.isBlank()) {
            throw new IllegalArgumentException("sourceEpoch is required");
        }
        this.sourceEpoch = sourceEpoch.trim();
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.ownsMediaDriver = borrowedDriver == null;
        this.mediaDriver.set(borrowedDriver);
        this.sessionFactory = sessionFactory == null ? this::openSession : sessionFactory;
        if (sessionFactory == null) {
            sharedMediaDriver();
        }
        this.commandAgents = new AeronAgentLane[capacity.commandSessions()];
        for (int index = 0; index < commandAgents.length; index++) {
            commandAgents[index] = new AeronAgentLane(this, capacity.commandMailboxCapacity(),
                    capacity.maxCommandInFlightPerSession(),
                    stableLong(this.sourceIdentity + ':' + productLine + ':' + this.sourceEpoch + ':' + index));
        }
        this.reservedControlAgent = new AeronAgentLane(this, capacity.queryMailboxCapacity(),
                capacity.maxReservedInFlight(), stableLong(this.sourceIdentity + ':' + productLine + ':'
                        + this.sourceEpoch + ":control"));
        this.egressDispatcher = new AeronEgressDispatcher(this, startAgents);
        if (startAgents) {
            egressDispatcher.start();
        }
    }

    public CoreCommandOutcome commandOutcome(
            CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return commandOutcomeAsync(type, commandId, userId, payload).join();
    }

    public CompletableFuture<CoreCommandOutcome> commandOutcomeAsync(
            CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        requireKind(type, WireMessageKind.COMMAND, "command");
        Objects.requireNonNull(commandId, "commandId");
        byte[] safePayload = requirePayload(payload);
        if (closed.get()) {
            return CompletableFuture.completedFuture(CoreCommandOutcome.notAccepted(Publication.CLOSED));
        }
        AeronAgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        AeronPoolRequest request = agent.commandOutcomeRequest(type, commandId, userId, safePayload);
        if (request == null) {
            return CompletableFuture.completedFuture(CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        CompletableFuture<CoreCommandOutcome> future = request.commandFuture;
        if (!agent.enqueue(request)) {
            agent.rejectUnqueued(request, CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        return future;
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return requireTerminal(commandOutcome(type, commandId, userId, payload));
    }

    public CompletableFuture<CoreResponse> commandAsync(
            CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        requireKind(type, WireMessageKind.COMMAND, "command");
        Objects.requireNonNull(commandId, "commandId");
        byte[] safePayload = requirePayload(payload);
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new CoreCommandOutcome.NotAcceptedException(CoreCommandOutcome.notAccepted(Publication.CLOSED)));
        }
        AeronAgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        AeronPoolRequest request = agent.commandResponseRequest(type, commandId, userId, safePayload);
        if (request == null) {
            return CompletableFuture.failedFuture(new CoreCommandOutcome.NotAcceptedException(
                    CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED)));
        }
        CompletableFuture<CoreResponse> future = request.responseFuture;
        if (!agent.enqueue(request)) {
            agent.rejectUnqueued(request, CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        return future;
    }

    public TryCommandResult tryCommandOnce(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        requireKind(type, WireMessageKind.COMMAND, "command");
        Objects.requireNonNull(commandId, "commandId");
        byte[] safePayload = requirePayload(payload);
        if (closed.get()) {
            return TryCommandResult.CLOSED;
        }
        AeronAgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        AeronPoolRequest request = agent.oneWayFutureRequest(type, commandId, userId, safePayload);
        if (request == null) {
            return TryCommandResult.BACK_PRESSURED;
        }
        CompletableFuture<Long> orderCheckFuture = request.orderCheckFuture;
        long requestGeneration = request.generation();
        if (!agent.enqueue(request)) {
            agent.recycleUnqueued(request);
            return TryCommandResult.BACK_PRESSURED;
        }
        long offerResult;
        try {
            offerResult = orderCheckFuture.get(responseTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            if (request.cancelBeforeOffer(requestGeneration)) {
                return agent.sessionConnected() ? TryCommandResult.BUSY : TryCommandResult.NOT_READY;
            }
            offerResult = orderCheckFuture.join();
        } catch (InterruptedException exception) {
            boolean cancelled = request.cancelBeforeOffer(requestGeneration);
            Thread.currentThread().interrupt();
            if (cancelled) {
                return TryCommandResult.BUSY;
            }
            offerResult = orderCheckFuture.join();
        } catch (java.util.concurrent.ExecutionException exception) {
            return TryCommandResult.UNAVAILABLE;
        }
        return mapTryCommandResult(offerResult);
    }

    public boolean commandOneWay(
            CoreMessageType type,
            UUID commandId,
            long userId,
            byte[] payload,
            CommandOrderCheckCallback callback) {
        requireKind(type, WireMessageKind.COMMAND, "command");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(callback, "callback");
        byte[] safePayload = requirePayload(payload);
        if (closed.get()) {
            callback.onOrderCheck(commandId, TryCommandResult.CLOSED);
            return true;
        }
        AeronAgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        AeronPoolRequest request = agent.oneWayCallbackRequest(type, commandId, userId, safePayload, callback);
        if (request == null) {
            return false;
        }
        if (!agent.enqueue(request)) {
            agent.recycleUnqueued(request);
            return false;
        }
        return true;
    }

    public int commandBatchOneWay(
            CoreMessageType type,
            UUID[] commandIds,
            long[] userIds,
            byte[][] payloads,
            int offset,
            int length,
            CommandOrderCheckCallback callback) {
        Objects.requireNonNull(commandIds, "commandIds");
        Objects.requireNonNull(userIds, "userIds");
        Objects.requireNonNull(payloads, "payloads");
        if (offset < 0 || length < 0 || offset > commandIds.length - length
                || offset > userIds.length - length || offset > payloads.length - length) {
            throw new IndexOutOfBoundsException("invalid command batch range");
        }
        int accepted = 0;
        for (int index = offset; index < offset + length; index++) {
            if (!commandOneWay(type, commandIds[index], userIds[index], payloads[index], callback)) {
                break;
            }
            accepted++;
        }
        return accepted;
    }

    public CompletableFuture<CoreResponse> controlQueryAsync(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CLOSED"));
        }
        requireReservedControl(type);
        return enqueueControlQuery(type, queryId, userId, payload);
    }

    public CompletableFuture<CoreResponse> lifecycleControlQueryAsync(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CLOSED"));
        }
        if (type != CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            throw new IllegalArgumentException("lifecycle control query type is required: " + type);
        }
        return enqueueControlQuery(type, queryId, userId, payload);
    }

    public CoreResponse lifecycleControlQuery(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        return lifecycleControlQueryAsync(type, queryId, userId, payload).join();
    }

    public CoreResponse lifecycleOpenOrders(long userId, String instrumentId, int limit) {
        return lifecycleControlQuery(CoreMessageType.USER_OPEN_ORDERS_QUERY, UUID.randomUUID(), userId,
                com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOpenOrdersQuery(
                        new com.surprising.aeron.protocol.CoreOpenOrdersQuery(instrumentId, 0L, limit)));
    }

    private CompletableFuture<CoreResponse> enqueueControlQuery(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        Objects.requireNonNull(queryId, "queryId");
        byte[] safePayload = requirePayload(payload);
        AeronPoolRequest request = reservedControlAgent.queryRequest(type, queryId, userId, safePayload);
        if (request == null) {
            return CompletableFuture.failedFuture(new CoreCommandOutcome.NotAcceptedException(
                    CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED)));
        }
        CompletableFuture<CoreResponse> future = request.responseFuture;
        if (!reservedControlAgent.enqueue(request)) {
            reservedControlAgent.rejectUnqueued(request, CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        return future;
    }

    private CompletableFuture<CoreResponse> enqueueOrdinaryQuery(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        Objects.requireNonNull(queryId, "queryId");
        byte[] safePayload = requirePayload(payload);
        AeronAgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        AeronPoolRequest request = agent.queryRequest(type, queryId, userId, safePayload);
        if (request == null) {
            return CompletableFuture.failedFuture(new CoreCommandOutcome.NotAcceptedException(
                    CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED)));
        }
        CompletableFuture<CoreResponse> future = request.responseFuture;
        if (!agent.enqueue(request)) {
            agent.rejectUnqueued(request, CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        return future;
    }

    public CoreResponse query(CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        if (closed.get()) {
            throw new IllegalStateException("Aeron client pool is closed");
        }
        return queryAsync(type, queryId, userId, payload).join();
    }

    public CompletableFuture<CoreResponse> queryAsync(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Aeron client pool is closed"));
        }
        return switch (CoreQueryClass.classify(type)) {
            case RESERVED_CONTROL -> controlQueryAsync(type, queryId, userId, payload);
            case ORDINARY_READ -> enqueueOrdinaryQuery(type, queryId, userId, payload);
        };
    }

    public CoreResponse submitPrepared(CoreMessage message) {
        if (closed.get()) {
            throw new IllegalStateException("Aeron client pool is closed");
        }
        Objects.requireNonNull(message, "message");
        if (message.header().productLine() != productLine) {
            throw new IllegalArgumentException("client pool and prepared message product line differ");
        }
        AeronPoolRequest request;
        AeronAgentLane lane;
        boolean commandRequest;
        if (message.header().kind() == WireMessageKind.COMMAND) {
            commandRequest = true;
            lane = commandAgents[Math.floorMod(Long.hashCode(message.header().sourceId()), commandAgents.length)];
            request = lane.preparedRequest(message, AeronPoolRequest.Mode.COMMAND_OUTCOME);
        } else if (message.header().kind() == WireMessageKind.QUERY) {
            commandRequest = false;
            requireReservedControl(message.header().messageType());
            lane = reservedControlAgent;
            request = lane.preparedRequest(message, AeronPoolRequest.Mode.QUERY);
        } else {
            throw new IllegalArgumentException("prepared message must be a command or reserved control query");
        }
        if (request == null) {
            throw new CoreCommandOutcome.NotAcceptedException(
                    CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        CompletableFuture<CoreCommandOutcome> commandFuture = request.commandFuture;
        CompletableFuture<CoreResponse> responseFuture = request.responseFuture;
        long requestGeneration = request.generation();
        UUID operationId = message.header().commandId();
        if (!lane.enqueue(request)) {
            lane.rejectUnqueued(request, CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        return commandRequest
                ? requireTerminal(awaitPrepared(request, requestGeneration, operationId, commandFuture))
                : awaitPrepared(request, requestGeneration, operationId, responseFuture);
    }

    public CoreResponse commandResult(UUID commandId, long userId) {
        Objects.requireNonNull(commandId, "commandId");
        return query(CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), userId,
                com.surprising.aeron.protocol.CoreStateQueryCodec.encodeCommandResultQuery(commandId));
    }

    int commandMailboxCapacity() {
        return capacity.commandMailboxCapacity();
    }

    int controlMailboxCapacity() {
        return capacity.queryMailboxCapacity();
    }

    boolean dispatcherStopped() {
        return dispatcherStopped.get();
    }

    @Override
    public synchronized void close() {
        boolean firstClose = closed.compareAndSet(false, true);
        if (!firstClose && !egressDispatcher.isAlive() && mediaDriver.get() == null) {
            return;
        }
        RuntimeException failure = egressDispatcher.stop();
        if (failure != null) {
            throw failure;
        }
    }

    private Session openSession() {
        return SurprisingAeronClient.connectAsync(productLine, hostnames, egressHostname, responseTimeout,
                sharedMediaDriver());
    }

    private MediaDriver sharedMediaDriver() {
        MediaDriver current = mediaDriver.get();
        if (current != null) {
            return current;
        }
        synchronized (mediaDriver) {
            current = mediaDriver.get();
            if (current == null) {
                String directoryName = Path.of(System.getProperty("java.io.tmpdir"),
                        "surprising-aeron-" + stableLong(
                                clientName + ':' + productLine + ':' + ProcessHandle.current().pid())).toString();
                current = SurprisingAeronClient.newMediaDriver(directoryName);
                mediaDriver.set(current);
            }
            return current;
        }
    }

    private static CoreResponse requireTerminal(CoreCommandOutcome outcome) {
        if (outcome instanceof CoreCommandOutcome.Terminal(CoreResponse response)) {
            return response;
        }
        if (outcome instanceof CoreCommandOutcome.ResultUnknown(UUID originalCommandId)) {
            throw new ResultUnknownException(originalCommandId,
                    "Aeron command was admitted but its result is unknown; query with the same commandId="
                            + originalCommandId);
        }
        throw new CoreCommandOutcome.NotAcceptedException((CoreCommandOutcome.NotAccepted) outcome);
    }

    private static <T> T awaitPrepared(
            AeronPoolRequest request, long requestGeneration, UUID operationId, CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            boolean cancelled = request.cancelBeforeOffer(requestGeneration);
            Thread.currentThread().interrupt();
            if (cancelled) {
                throw new IllegalStateException("Interrupted before prepared Aeron request admission", exception);
            }
            throw new ResultUnknownException(operationId,
                    "Interrupted after prepared Aeron request admission; result is unknown");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Prepared Aeron request failed", exception.getCause());
        }
    }

    static TryCommandResult mapTryCommandResult(long offerResult) {
        if (offerResult > 0) {
            return TryCommandResult.SENT;
        }
        CoreCommandOutcome.NotAccepted rejection = CoreCommandOutcome.notAccepted(offerResult);
        return switch (rejection.reason()) {
            case CLIENT_BACKPRESSURED -> TryCommandResult.BACK_PRESSURED;
            case NOT_CONNECTED -> TryCommandResult.NOT_READY;
            case CLOSED -> TryCommandResult.CLOSED;
            case ADMIN_ACTION, MAX_POSITION_EXCEEDED, UNKNOWN -> TryCommandResult.UNAVAILABLE;
        };
    }

    private static void requireReservedControl(CoreMessageType type) {
        if (CoreQueryClass.classify(type) != CoreQueryClass.RESERVED_CONTROL) {
            throw new IllegalArgumentException("ordinary Core reads cannot use reserved control capacity: " + type);
        }
    }

    private static void requireKind(CoreMessageType type, WireMessageKind kind, String label) {
        if (type == null || type.kind() != kind) {
            throw new IllegalArgumentException(label + " message type is required");
        }
    }

    private static byte[] requirePayload(byte[] payload) {
        return Objects.requireNonNull(payload, "payload");
    }

    private static long stableLong(String value) {
        UUID uuid = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        long result = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        return result == 0 ? 1 : result;
    }

    /** Optional sparse transport trace; no request state or cross-JVM clock assumptions. */
    @jdk.jfr.Name("surprising.ClientTransportBoundary")
    @jdk.jfr.StackTrace(false)
    static final class ClientTransportBoundary extends jdk.jfr.Event {
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(ClientTransportBoundary.class);
        public String stage;
        public long commandIdHigh, commandIdLow, observedNanos;

        static void record(String stage, UUID id) {
            if ((id.hashCode() & 63) != 0 || !TYPE.isEnabled()) return;
            long observed = System.nanoTime();
            var event = new ClientTransportBoundary();
            event.stage = stage;
            event.commandIdHigh = id.getMostSignificantBits();
            event.commandIdLow = id.getLeastSignificantBits();
            event.observedNanos = observed;
            event.commit();
        }
    }
}
