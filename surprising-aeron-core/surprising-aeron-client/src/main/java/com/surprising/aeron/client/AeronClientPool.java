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
import java.util.Map;
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

    private static final long DISPATCHER_CLOSE_TIMEOUT_MILLIS = 5_000L;
    private static final long MIN_RECONNECT_MILLIS = 25L;
    private static final long MAX_RECONNECT_MILLIS = 1_000L;
    private static final long KEEP_ALIVE_INTERVAL_MILLIS = 1_000L;
    private static final long KEEP_ALIVE_RETRY_MILLIS = 25L;

    public enum TryCommandResult {
        SENT,
        BUSY,
        NOT_READY,
        BACK_PRESSURED,
        UNAVAILABLE,
        CLOSED
    }

    @FunctionalInterface
    public interface CommandAdmissionCallback {
        void onAdmission(UUID commandId, TryCommandResult result);
    }

    interface Session extends AutoCloseable {
        long offer(CoreMessage message);
        int pollEgress(int fragmentLimit);
        CoreResponse takeResponse(long correlationId);
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

    private final String clientName;
    private final ProductLine productLine;
    private final List<String> hostnames;
    private final String egressHostname;
    private final Duration responseTimeout;
    private final String sourceIdentity;
    private final String sourceEpoch;
    private final AeronClientCapacity capacity;
    private final SessionFactory sessionFactory;
    private final AgentLane[] commandAgents;
    private final AgentLane reservedControlAgent;
    private final EgressDispatcher egressDispatcher;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean dispatcherStopped = new AtomicBoolean();
    private final AtomicReference<MediaDriver> mediaDriver = new AtomicReference<>();
    private final AtomicReference<RuntimeException> dispatcherFailure = new AtomicReference<>();

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
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("clientName is required");
        }
        this.clientName = clientName.trim();
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        if (hostnames == null || hostnames.size() != 3
                || hostnames.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("hostnames must contain three non-blank members");
        }
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
        this.sessionFactory = sessionFactory == null ? this::openSession : sessionFactory;
        if (sessionFactory == null) {
            sharedMediaDriver();
        }
        this.commandAgents = new AgentLane[capacity.commandSessions()];
        for (int index = 0; index < commandAgents.length; index++) {
            commandAgents[index] = new AgentLane(capacity.commandMailboxCapacity(),
                    capacity.maxCommandInFlightPerSession(),
                    stableLong(this.sourceIdentity + ':' + productLine + ':' + this.sourceEpoch + ':' + index));
        }
        this.reservedControlAgent = new AgentLane(capacity.queryMailboxCapacity(),
                capacity.maxReservedInFlight(), stableLong(this.sourceIdentity + ':' + productLine + ':'
                        + this.sourceEpoch + ":control"));
        this.egressDispatcher = new EgressDispatcher(startAgents);
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
        AgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        Request request = agent.commandOutcomeRequest(type, commandId, userId, safePayload);
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
        AgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        Request request = agent.commandResponseRequest(type, commandId, userId, safePayload);
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
        AgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        Request request = agent.oneWayFutureRequest(type, commandId, userId, safePayload);
        if (request == null) {
            return TryCommandResult.BACK_PRESSURED;
        }
        CompletableFuture<Long> admissionFuture = request.admissionFuture;
        long requestGeneration = request.generation();
        if (!agent.enqueue(request)) {
            agent.recycleUnqueued(request);
            return TryCommandResult.BACK_PRESSURED;
        }
        long offerResult;
        try {
            offerResult = admissionFuture.get(responseTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            if (request.cancelBeforeOffer(requestGeneration)) {
                return agent.sessionConnected() ? TryCommandResult.BUSY : TryCommandResult.NOT_READY;
            }
            offerResult = admissionFuture.join();
        } catch (InterruptedException exception) {
            boolean cancelled = request.cancelBeforeOffer(requestGeneration);
            Thread.currentThread().interrupt();
            if (cancelled) {
                return TryCommandResult.BUSY;
            }
            offerResult = admissionFuture.join();
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
            CommandAdmissionCallback callback) {
        requireKind(type, WireMessageKind.COMMAND, "command");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(callback, "callback");
        byte[] safePayload = requirePayload(payload);
        if (closed.get()) {
            callback.onAdmission(commandId, TryCommandResult.CLOSED);
            return true;
        }
        AgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        Request request = agent.oneWayCallbackRequest(type, commandId, userId, safePayload, callback);
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
            CommandAdmissionCallback callback) {
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

    public CoreResponse lifecycleOpenOrders(long userId, String symbol, int limit) {
        return lifecycleControlQuery(CoreMessageType.USER_OPEN_ORDERS_QUERY, UUID.randomUUID(), userId,
                com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOpenOrdersQuery(
                        new com.surprising.aeron.protocol.CoreOpenOrdersQuery(symbol, 0L, limit)));
    }

    private CompletableFuture<CoreResponse> enqueueControlQuery(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        Objects.requireNonNull(queryId, "queryId");
        byte[] safePayload = requirePayload(payload);
        Request request = reservedControlAgent.queryRequest(type, queryId, userId, safePayload);
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
        AgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        Request request = agent.queryRequest(type, queryId, userId, safePayload);
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
        Request request;
        AgentLane lane;
        boolean commandRequest;
        if (message.header().kind() == WireMessageKind.COMMAND) {
            commandRequest = true;
            lane = commandAgents[Math.floorMod(Long.hashCode(message.header().sourceId()), commandAgents.length)];
            request = lane.preparedRequest(message, RequestMode.COMMAND_OUTCOME);
        } else if (message.header().kind() == WireMessageKind.QUERY) {
            commandRequest = false;
            requireReservedControl(message.header().messageType());
            lane = reservedControlAgent;
            request = lane.preparedRequest(message, RequestMode.QUERY);
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

    int agentThreadCount() {
        return 1;
    }

    int configuredSessionCount() {
        return commandAgents.length + 1;
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
            Request request, long requestGeneration, UUID operationId, CompletableFuture<T> future) {
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

    private static TryCommandResult mapTryCommandResult(long offerResult) {
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

    private final class AgentLane {
        private final ArrayBlockingQueue<Request> mailbox;
        private final ArrayBlockingQueue<Request> availableRequests;
        private final int maxInFlight;
        private final long sourceId;
        private final AtomicLong nextSequence = new AtomicLong();
        private final AtomicLong nextCorrelation = new AtomicLong();
        private final Map<Long, Request> pending = new LinkedHashMap<>();
        private final Set<Long> claimedCorrelations = ConcurrentHashMap.newKeySet();
        private volatile Session session;
        private long reconnectAtNanos;
        private long reconnectMillis = MIN_RECONNECT_MILLIS;
        private long keepAliveAtNanos;

        private AgentLane(int mailboxCapacity, int maxInFlight, long sourceId) {
            this.mailbox = new ArrayBlockingQueue<>(mailboxCapacity);
            this.availableRequests = new ArrayBlockingQueue<>(mailboxCapacity + maxInFlight);
            for (int index = 0; index < mailboxCapacity + maxInFlight; index++) {
                availableRequests.add(new Request(this));
            }
            this.maxInFlight = maxInFlight;
            this.sourceId = sourceId;
        }

        private boolean sessionConnected() {
            Session current = session;
            return current != null && current.connected();
        }

        private Request commandOutcomeRequest(
                CoreMessageType type, UUID commandId, long userId, byte[] payload) {
            return commandRequest(type, commandId, userId, payload, RequestMode.COMMAND_OUTCOME, null);
        }

        private Request commandResponseRequest(
                CoreMessageType type, UUID commandId, long userId, byte[] payload) {
            return commandRequest(type, commandId, userId, payload, RequestMode.COMMAND_RESPONSE, null);
        }

        private Request oneWayFutureRequest(
                CoreMessageType type, UUID commandId, long userId, byte[] payload) {
            return commandRequest(type, commandId, userId, payload, RequestMode.ONE_WAY_FUTURE, null);
        }

        private Request oneWayCallbackRequest(CoreMessageType type, UUID commandId, long userId, byte[] payload,
                                              CommandAdmissionCallback callback) {
            return commandRequest(type, commandId, userId, payload, RequestMode.ONE_WAY_CALLBACK, callback);
        }

        private Request commandRequest(CoreMessageType type, UUID commandId, long userId, byte[] payload,
                                       RequestMode mode, CommandAdmissionCallback callback) {
            Request request = availableRequests.poll();
            if (request == null) {
                return null;
            }
            long correlationId = nextCorrelation.incrementAndGet();
            if (mode.oneWay) {
                correlationId = -correlationId;
            }
            CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                    CommandSource.GATEWAY, sourceId, nextSequence.incrementAndGet(), userId,
                    Instant.now().toEpochMilli(), correlationId), payload);
            request.reset(message, commandId, mode, callback);
            return request;
        }

        private Request queryRequest(CoreMessageType type, UUID queryId, long userId, byte[] payload) {
            Request request = availableRequests.poll();
            if (request == null) {
                return null;
            }
            long correlationId = nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.query(type, queryId, productLine,
                    CommandSource.GATEWAY, sourceId, 0, userId, Instant.now().toEpochMilli(), correlationId), payload);
            request.reset(message, queryId, RequestMode.QUERY, null);
            return request;
        }

        private Request preparedRequest(CoreMessage message, RequestMode mode) {
            Request request = availableRequests.poll();
            if (request != null) {
                request.reset(message, message.header().commandId(), mode, null);
            }
            return request;
        }

        private boolean enqueue(Request request) {
            long correlationId = request.message.header().correlationId();
            if (!claimedCorrelations.add(correlationId)) {
                request.fail(new IllegalStateException(
                        "duplicate in-flight Aeron correlationId=" + correlationId));
                return true;
            }
            request.correlationClaimed = true;
            RuntimeException failure = dispatcherFailure.get();
            if (failure != null) {
                request.fail(failure);
                return true;
            }
            if (closed.get() || dispatcherStopped.get()) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.CLOSED));
                return true;
            }
            request.startQueueTimeout(responseTimeout.toNanos());
            if (!mailbox.offer(request)) {
                request.releaseCorrelation();
                return false;
            }
            failure = dispatcherFailure.get();
            if ((closed.get() || dispatcherStopped.get() || failure != null) && mailbox.remove(request)) {
                if (failure == null) {
                    request.notAccepted(CoreCommandOutcome.notAccepted(Publication.CLOSED));
                } else {
                    request.fail(failure);
                }
            }
            return true;
        }

        private void rejectUnqueued(Request request, CoreCommandOutcome.NotAccepted rejection) {
            request.notAccepted(rejection);
        }

        private void recycleUnqueued(Request request) {
            request.recycle();
        }

        private void recycle(Request request) {
            request.releaseCorrelation();
            request.clear();
            if (!availableRequests.offer(request)) {
                dispatcherFailure.compareAndSet(null,
                        new IllegalStateException("Aeron request slot returned twice"));
            }
        }

        private void connected() {
            reconnectAtNanos = 0;
            reconnectMillis = MIN_RECONNECT_MILLIS;
            keepAliveAtNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(KEEP_ALIVE_INTERVAL_MILLIS);
        }

        private void reconnectLater() {
            reconnectAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(reconnectMillis);
            reconnectMillis = Math.min(MAX_RECONNECT_MILLIS, Math.multiplyExact(reconnectMillis, 2));
            keepAliveAtNanos = 0;
        }

        private void releaseCorrelation(long correlationId) {
            claimedCorrelations.remove(correlationId);
        }
    }

    private final class EgressDispatcher implements Runnable {
        private final AgentLane[] lanes;
        private final Thread thread;

        private EgressDispatcher(boolean startAgents) {
            this.lanes = new AgentLane[commandAgents.length + 1];
            System.arraycopy(commandAgents, 0, lanes, 0, commandAgents.length);
            lanes[lanes.length - 1] = reservedControlAgent;
            this.thread = new Thread(this, clientName + "-egress-dispatcher");
            this.thread.setDaemon(true);
            if (!startAgents) {
                rejectQueuedAsClosed();
            }
        }

        private void start() {
            thread.start();
        }

        @Override
        public void run() {
            try {
                openSessions();
                while (!closed.get()) {
                    boolean worked = false;
                    for (AgentLane lane : lanes) {
                        worked |= openSession(lane);
                    }
                    for (AgentLane lane : lanes) {
                        worked |= pollSession(lane);
                    }
                    for (AgentLane lane : lanes) {
                        worked |= keepAliveSession(lane);
                    }
                    for (AgentLane lane : lanes) {
                        worked |= admitQueued(lane);
                        expireQueued(lane);
                        expireAdmitted(lane);
                    }
                    if (!worked) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(1);
                        } catch (InterruptedException exception) {
                            if (!closed.get()) {
                                Thread.currentThread().interrupt();
                                dispatcherFailure.compareAndSet(null,
                                        new IllegalStateException("Aeron dispatcher interrupted", exception));
                            }
                            break;
                        }
                    }
                }
            } catch (RuntimeException exception) {
                dispatcherFailure.compareAndSet(null, exception);
            } finally {
                dispatcherStopped.set(true);
                rejectQueuedAsClosed();
                completeAdmittedAsUnknown();
                closeSessions();
                closeMediaDriver();
            }
        }

        private void openSessions() {
            for (AgentLane lane : lanes) {
                if (closed.get()) {
                    return;
                }
                openSession(lane);
            }
        }

        private boolean openSession(AgentLane lane) {
            if (closed.get() || lane.session != null || System.nanoTime() < lane.reconnectAtNanos) {
                return false;
            }
            try {
                lane.session = Objects.requireNonNull(sessionFactory.open(), "sessionFactory returned null");
                return true;
            } catch (RuntimeException exception) {
                lane.session = null;
                lane.reconnectLater();
                return true;
            }
        }

        private boolean pollSession(AgentLane lane) {
            Session current = lane.session;
            if (current == null) {
                return false;
            }
            try {
                int fragments = current.pollEgress(capacity.egressFragmentLimit());
                dispatchResponses(current, lane.pending);
                RuntimeException failure = current.sessionFailure();
                if (failure != null) {
                    lane.pending.values().forEach(Request::resultUnknown);
                    lane.pending.clear();
                    closeSession(lane);
                }
                if (failure == null && fragments > 0) {
                    lane.connected();
                }
                return fragments > 0;
            } catch (RuntimeException exception) {
                lane.pending.values().forEach(Request::resultUnknown);
                lane.pending.clear();
                closeSession(lane);
                return true;
            }
        }

        private boolean keepAliveSession(AgentLane lane) {
            Session current = lane.session;
            long now = System.nanoTime();
            if (current == null || now < lane.keepAliveAtNanos) {
                return false;
            }
            try {
                if (current.keepAlive()) {
                    lane.connected();
                    return true;
                }
                lane.keepAliveAtNanos = now + TimeUnit.MILLISECONDS.toNanos(KEEP_ALIVE_RETRY_MILLIS);
                return false;
            } catch (RuntimeException exception) {
                lane.pending.values().forEach(Request::resultUnknown);
                lane.pending.clear();
                closeSession(lane);
                return true;
            }
        }

        private boolean admitQueued(AgentLane lane) {
            boolean worked = false;
            while (true) {
                Request next = lane.mailbox.peek();
                if (next == null || lane.session == null || !lane.session.connected()
                        || (!next.oneWay() && lane.pending.size() >= lane.maxInFlight)) {
                    return worked;
                }
                Request request = lane.mailbox.poll();
                if (request == null) {
                    return worked;
                }
                worked = true;
                if (!request.beginOffer()) {
                    request.recycle();
                    continue;
                }
                admit(lane, request);
            }
        }

        private void admit(AgentLane lane, Request request) {
            Session current = lane.session;
            if (current == null) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.NOT_CONNECTED));
                lane.reconnectLater();
                return;
            }
            long offerResult;
            try {
                offerResult = current.offer(request.message);
            } catch (RuntimeException exception) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Long.MIN_VALUE));
                closeSession(lane);
                return;
            }
            if (offerResult > 0) {
                lane.connected();
                if (!request.oneWay()) {
                    request.deadlineNanos = System.nanoTime() + responseTimeout.toNanos();
                    lane.pending.put(request.message.header().correlationId(), request);
                } else {
                    request.acceptedOneWay(offerResult);
                }
            } else {
                if (offerResult == Publication.NOT_CONNECTED && request.isQuery()) {
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

        private void expireAdmitted(AgentLane lane) {
            long now = System.nanoTime();
            boolean expired = lane.pending.values().stream()
                    .anyMatch(request -> now >= request.deadlineNanos);
            if (expired) {
                lane.pending.values().forEach(Request::resultUnknown);
                lane.pending.clear();
                closeSession(lane);
            }
        }

        private void expireQueued(AgentLane lane) {
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

        private void dispatchResponses(Session session, Map<Long, Request> pending) {
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Request> entry = iterator.next();
                CoreResponse response = session.takeResponse(entry.getKey());
                if (response != null) {
                    iterator.remove();
                    entry.getValue().terminal(response);
                }
            }
        }

        private void rejectQueuedAsClosed() {
            for (AgentLane lane : lanes) {
                Request request;
                while ((request = lane.mailbox.poll()) != null) {
                    request.notAccepted(CoreCommandOutcome.notAccepted(Publication.CLOSED));
                }
            }
        }

        private void completeAdmittedAsUnknown() {
            for (AgentLane lane : lanes) {
                lane.pending.values().forEach(Request::resultUnknown);
                lane.pending.clear();
            }
        }

        private void closeSessions() {
            for (AgentLane lane : lanes) {
                try {
                    closeSession(lane);
                } catch (RuntimeException exception) {
                    RuntimeException first = dispatcherFailure.get();
                    if (first == null) {
                        dispatcherFailure.compareAndSet(null, exception);
                    } else {
                        first.addSuppressed(exception);
                    }
                }
            }
        }

        private void closeSession(AgentLane lane) {
            Session current = lane.session;
            lane.session = null;
            try {
                if (current != null) {
                    current.close();
                }
            } finally {
                if (!closed.get() && !dispatcherStopped.get()) {
                    lane.reconnectLater();
                }
            }
        }

        private RuntimeException stop() {
            boolean interrupted = false;
            if (thread.isAlive()) {
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(DISPATCHER_CLOSE_TIMEOUT_MILLIS);
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
                    dispatcherFailure.compareAndSet(null,
                            new IllegalStateException("Interrupted while stopping Aeron dispatcher"));
                }
                if (thread.isAlive()) {
                    dispatcherFailure.compareAndSet(null,
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
            return dispatcherFailure.get();
        }

        private boolean isAlive() {
            return thread.isAlive();
        }

        private void closeMediaDriver() {
            MediaDriver driver;
            synchronized (mediaDriver) {
                driver = mediaDriver.getAndSet(null);
            }
            if (driver == null) {
                return;
            }
            try {
                driver.close();
            } catch (RuntimeException exception) {
                RuntimeException first = dispatcherFailure.get();
                if (first == null) {
                    dispatcherFailure.compareAndSet(null, exception);
                } else {
                    first.addSuppressed(exception);
                }
            }
        }
    }

    private enum RequestMode {
        COMMAND_OUTCOME(false),
        COMMAND_RESPONSE(false),
        QUERY(false),
        ONE_WAY_FUTURE(true),
        ONE_WAY_CALLBACK(true);

        private final boolean oneWay;

        RequestMode(boolean oneWay) {
            this.oneWay = oneWay;
        }
    }

    private static final class Request {
        private final AgentLane owner;
        private CoreMessage message;
        private UUID operationId;
        private RequestMode mode;
        private CompletableFuture<CoreCommandOutcome> commandFuture;
        private CompletableFuture<CoreResponse> responseFuture;
        private CompletableFuture<Long> admissionFuture;
        private CommandAdmissionCallback admissionCallback;
        private boolean offering;
        private boolean cancelled;
        private boolean completed;
        private boolean correlationClaimed;
        private long generation;
        private long queueDeadlineNanos;
        private long deadlineNanos;

        private Request(AgentLane owner) {
            this.owner = owner;
        }

        private synchronized void reset(CoreMessage message, UUID operationId, RequestMode mode,
                                        CommandAdmissionCallback admissionCallback) {
            this.message = Objects.requireNonNull(message, "message");
            this.operationId = Objects.requireNonNull(operationId, "operationId");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.commandFuture = mode == RequestMode.COMMAND_OUTCOME ? new CompletableFuture<>() : null;
            this.responseFuture = mode == RequestMode.COMMAND_RESPONSE || mode == RequestMode.QUERY
                    ? new CompletableFuture<>() : null;
            this.admissionFuture = mode == RequestMode.ONE_WAY_FUTURE ? new CompletableFuture<>() : null;
            this.admissionCallback = admissionCallback;
            this.offering = false;
            this.cancelled = false;
            this.completed = false;
            this.correlationClaimed = false;
            this.queueDeadlineNanos = 0;
            this.deadlineNanos = 0;
            this.generation++;
        }

        private synchronized long generation() {
            return generation;
        }

        private boolean oneWay() {
            return mode.oneWay;
        }

        private synchronized boolean beginOffer() {
            if (cancelled) {
                return false;
            }
            offering = true;
            return true;
        }

        private synchronized void prepareForRetry() {
            offering = false;
        }

        private synchronized void startQueueTimeout(long timeoutNanos) {
            if (queueDeadlineNanos == 0) {
                queueDeadlineNanos = Math.addExact(System.nanoTime(), timeoutNanos);
            }
        }

        private synchronized boolean queueExpired(long now) {
            return !offering && (cancelled || queueDeadlineNanos != 0 && now >= queueDeadlineNanos);
        }

        private boolean isQuery() {
            return mode == RequestMode.QUERY;
        }

        private synchronized boolean cancelBeforeOffer(long expectedGeneration) {
            if (generation != expectedGeneration || offering || completed) {
                return false;
            }
            cancelled = true;
            return true;
        }

        private void acceptedOneWay(long offerResult) {
            if (!claimCompletion()) {
                return;
            }
            CompletableFuture<Long> future = admissionFuture;
            CommandAdmissionCallback callback = admissionCallback;
            UUID commandId = operationId;
            if (future != null) {
                future.complete(offerResult);
            }
            try {
                invokeCallback(callback, commandId, TryCommandResult.SENT);
            } finally {
                owner.recycle(this);
            }
        }

        private void terminal(CoreResponse response) {
            if (!claimCompletion()) {
                return;
            }
            CompletableFuture<CoreCommandOutcome> outcome = commandFuture;
            CompletableFuture<CoreResponse> result = responseFuture;
            if (outcome != null) {
                outcome.complete(new CoreCommandOutcome.Terminal(response));
            } else if (result != null) {
                result.complete(response);
            }
            owner.recycle(this);
        }

        private void notAccepted(CoreCommandOutcome.NotAccepted rejection) {
            if (!claimCompletion()) {
                return;
            }
            CompletableFuture<Long> admission = admissionFuture;
            CompletableFuture<CoreCommandOutcome> outcome = commandFuture;
            CompletableFuture<CoreResponse> result = responseFuture;
            CommandAdmissionCallback callback = admissionCallback;
            UUID commandId = operationId;
            if (admission != null) {
                admission.complete(rejection.rawOfferResult());
            }
            if (outcome != null) {
                outcome.complete(rejection);
            } else if (result != null) {
                result.completeExceptionally(new CoreCommandOutcome.NotAcceptedException(rejection));
            }
            try {
                invokeCallback(callback, commandId, mapTryCommandResult(rejection.rawOfferResult()));
            } finally {
                owner.recycle(this);
            }
        }

        private void resultUnknown() {
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

        private void fail(RuntimeException failure) {
            if (!claimCompletion()) {
                return;
            }
            CompletableFuture<Long> admission = admissionFuture;
            CompletableFuture<CoreCommandOutcome> outcome = commandFuture;
            CompletableFuture<CoreResponse> result = responseFuture;
            CommandAdmissionCallback callback = admissionCallback;
            UUID commandId = operationId;
            if (admission != null) {
                admission.completeExceptionally(failure);
            }
            if (outcome != null) {
                outcome.completeExceptionally(failure);
            } else if (result != null) {
                result.completeExceptionally(failure);
            }
            try {
                invokeCallback(callback, commandId, TryCommandResult.UNAVAILABLE);
            } finally {
                owner.recycle(this);
            }
        }

        private void recycle() {
            if (claimCompletion()) {
                owner.recycle(this);
            }
        }

        private synchronized boolean claimCompletion() {
            if (completed) {
                return false;
            }
            completed = true;
            return true;
        }

        private synchronized void clear() {
            message = null;
            operationId = null;
            mode = null;
            commandFuture = null;
            responseFuture = null;
            admissionFuture = null;
            admissionCallback = null;
            offering = false;
            cancelled = false;
            queueDeadlineNanos = 0;
            deadlineNanos = 0;
        }

        private synchronized void releaseCorrelation() {
            if (correlationClaimed && message != null) {
                correlationClaimed = false;
                owner.releaseCorrelation(message.header().correlationId());
            }
        }

        private static void invokeCallback(
                CommandAdmissionCallback callback, UUID commandId, TryCommandResult result) {
            if (callback != null) {
                try {
                    callback.onAdmission(commandId, result);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
