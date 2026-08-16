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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class AeronClientPool implements AutoCloseable {

    private static final long DISPATCHER_CLOSE_TIMEOUT_MILLIS = 5_000L;

    public enum TryCommandResult {
        SENT,
        BUSY,
        NOT_READY,
        BACK_PRESSURED,
        UNAVAILABLE,
        CLOSED
    }

    interface Session extends AutoCloseable {
        long offer(CoreMessage message);
        int pollEgress(int fragmentLimit);
        CoreResponse takeResponse(long correlationId);
        RuntimeException sessionFailure();
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
        Request request = agent.commandRequest(type, commandId, userId, safePayload, false);
        if (!agent.enqueue(request)) {
            request.notAccepted(CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        return request.commandFuture;
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return requireTerminal(commandOutcome(type, commandId, userId, payload));
    }

    public CompletableFuture<CoreResponse> commandAsync(
            CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return commandOutcomeAsync(type, commandId, userId, payload).thenApply(AeronClientPool::requireTerminal);
    }

    public TryCommandResult tryCommandOnce(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        requireKind(type, WireMessageKind.COMMAND, "command");
        Objects.requireNonNull(commandId, "commandId");
        byte[] safePayload = requirePayload(payload);
        if (closed.get()) {
            return TryCommandResult.CLOSED;
        }
        AgentLane agent = commandAgents[Math.floorMod(Long.hashCode(userId), commandAgents.length)];
        Request request = agent.commandRequest(type, commandId, userId, safePayload, true);
        if (!agent.enqueue(request)) {
            return TryCommandResult.BACK_PRESSURED;
        }
        long offerResult;
        try {
            offerResult = request.admissionFuture.get(responseTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            if (request.cancelBeforeOffer()) {
                return TryCommandResult.BUSY;
            }
            offerResult = request.admissionFuture.join();
        } catch (InterruptedException exception) {
            boolean cancelled = request.cancelBeforeOffer();
            Thread.currentThread().interrupt();
            if (cancelled) {
                return TryCommandResult.BUSY;
            }
            offerResult = request.admissionFuture.join();
        } catch (java.util.concurrent.ExecutionException exception) {
            return TryCommandResult.UNAVAILABLE;
        }
        return mapTryCommandResult(offerResult);
    }

    public CompletableFuture<CoreResponse> controlQueryAsync(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CLOSED"));
        }
        requireReservedControl(type);
        Objects.requireNonNull(queryId, "queryId");
        byte[] safePayload = requirePayload(payload);
        Request request = reservedControlAgent.queryRequest(type, queryId, userId, safePayload);
        if (!reservedControlAgent.enqueue(request)) {
            request.notAccepted(CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
        }
        return request.queryFuture;
    }

    public CoreResponse query(CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        if (closed.get()) {
            throw new IllegalStateException("Aeron client pool is closed");
        }
        return controlQueryAsync(type, queryId, userId, payload).join();
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = egressDispatcher.stop();
        MediaDriver driver;
        synchronized (mediaDriver) {
            driver = mediaDriver.getAndSet(null);
        }
        if (driver != null) {
            try {
                driver.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
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
                String directoryName = "surprising-aeron-" + stableLong(
                        clientName + ':' + productLine + ':' + ProcessHandle.current().pid());
                current = SurprisingAeronClient.newMediaDriver(directoryName);
                mediaDriver.set(current);
            }
            return current;
        }
    }

    private static CoreResponse requireTerminal(CoreCommandOutcome outcome) {
        if (outcome instanceof CoreCommandOutcome.Terminal terminal) {
            return terminal.response();
        }
        if (outcome instanceof CoreCommandOutcome.ResultUnknown unknown) {
            throw new ResultUnknownException(unknown.originalCommandId(),
                    "Aeron command was admitted but its result is unknown; query with the same commandId="
                            + unknown.originalCommandId());
        }
        throw new CoreCommandOutcome.NotAcceptedException((CoreCommandOutcome.NotAccepted) outcome);
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
        return Objects.requireNonNull(payload, "payload").clone();
    }

    private static long stableLong(String value) {
        UUID uuid = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        long result = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        return result == 0 ? 1 : result;
    }

    private final class AgentLane {
        private final ArrayBlockingQueue<Request> mailbox;
        private final int maxInFlight;
        private final long sourceId;
        private final AtomicLong nextSequence = new AtomicLong();
        private final AtomicLong nextCorrelation = new AtomicLong();
        private final Map<Long, Request> pending = new LinkedHashMap<>();
        private Session session;

        private AgentLane(int mailboxCapacity, int maxInFlight, long sourceId) {
            this.mailbox = new ArrayBlockingQueue<>(mailboxCapacity);
            this.maxInFlight = maxInFlight;
            this.sourceId = sourceId;
        }

        private Request commandRequest(
                CoreMessageType type, UUID commandId, long userId, byte[] payload, boolean oneWay) {
            long correlationId = nextCorrelation.incrementAndGet();
            if (oneWay) {
                correlationId = -correlationId;
            }
            CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                    CommandSource.GATEWAY, sourceId, nextSequence.incrementAndGet(), userId,
                    Instant.now().toEpochMilli(), correlationId), payload);
            return Request.command(message, commandId, oneWay);
        }

        private Request queryRequest(CoreMessageType type, UUID queryId, long userId, byte[] payload) {
            long correlationId = nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.query(type, queryId, productLine,
                    CommandSource.GATEWAY, sourceId, 0, userId, Instant.now().toEpochMilli(), correlationId), payload);
            return Request.query(message, queryId);
        }

        private boolean enqueue(Request request) {
            return mailbox.offer(request);
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
                        worked |= pollSession(lane);
                    }
                    for (AgentLane lane : lanes) {
                        worked |= admitQueued(lane);
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
                rejectQueuedAsClosed();
                completeAdmittedAsUnknown();
                closeSessions();
            }
        }

        private void openSessions() {
            for (AgentLane lane : lanes) {
                if (closed.get()) {
                    return;
                }
                try {
                    lane.session = Objects.requireNonNull(sessionFactory.open(), "sessionFactory returned null");
                } catch (RuntimeException exception) {
                    lane.session = null;
                }
                admitQueued(lane);
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
                return fragments > 0;
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
                if (next == null || (!next.oneWay && lane.pending.size() >= lane.maxInFlight)) {
                    return worked;
                }
                Request request = lane.mailbox.poll();
                if (request == null) {
                    return worked;
                }
                worked = true;
                if (!request.beginOffer()) {
                    continue;
                }
                admit(lane, request);
            }
        }

        private void admit(AgentLane lane, Request request) {
            Session current = lane.session;
            if (current == null) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.NOT_CONNECTED));
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
            request.admissionFuture.complete(offerResult);
            if (offerResult > 0) {
                if (!request.oneWay) {
                    request.deadlineNanos = System.nanoTime() + responseTimeout.toNanos();
                    lane.pending.put(request.message.header().correlationId(), request);
                }
            } else {
                request.notAccepted(CoreCommandOutcome.notAccepted(offerResult));
                if (offerResult == Publication.CLOSED || offerResult == Publication.MAX_POSITION_EXCEEDED) {
                    closeSession(lane);
                }
            }
        }

        private void expireAdmitted(AgentLane lane) {
            long now = System.nanoTime();
            var iterator = lane.pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Request request = iterator.next().getValue();
                if (now >= request.deadlineNanos) {
                    iterator.remove();
                    request.resultUnknown();
                }
            }
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
            if (current != null) {
                current.close();
            }
        }

        private RuntimeException stop() {
            if (thread.isAlive()) {
                try {
                    thread.join(DISPATCHER_CLOSE_TIMEOUT_MILLIS);
                    if (thread.isAlive()) {
                        thread.interrupt();
                        thread.join(1_000L);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    dispatcherFailure.compareAndSet(null,
                            new IllegalStateException("Interrupted while stopping Aeron dispatcher", exception));
                }
                if (thread.isAlive()) {
                    dispatcherFailure.compareAndSet(null,
                            new IllegalStateException("Aeron dispatcher did not terminate: " + thread.getName()));
                }
            } else {
                rejectQueuedAsClosed();
                completeAdmittedAsUnknown();
                closeSessions();
            }
            return dispatcherFailure.get();
        }
    }

    private static final class Request {
        private final CoreMessage message;
        private final UUID operationId;
        private final boolean oneWay;
        private final CompletableFuture<CoreCommandOutcome> commandFuture;
        private final CompletableFuture<CoreResponse> queryFuture;
        private final CompletableFuture<Long> admissionFuture = new CompletableFuture<>();
        private boolean offering;
        private boolean cancelled;
        private long deadlineNanos;

        private Request(CoreMessage message, UUID operationId, boolean oneWay,
                        CompletableFuture<CoreCommandOutcome> commandFuture,
                        CompletableFuture<CoreResponse> queryFuture) {
            this.message = message;
            this.operationId = operationId;
            this.oneWay = oneWay;
            this.commandFuture = commandFuture;
            this.queryFuture = queryFuture;
        }

        private static Request command(CoreMessage message, UUID commandId, boolean oneWay) {
            return new Request(message, commandId, oneWay, oneWay ? null : new CompletableFuture<>(), null);
        }

        private static Request query(CoreMessage message, UUID queryId) {
            return new Request(message, queryId, false, null, new CompletableFuture<>());
        }

        private synchronized boolean beginOffer() {
            if (cancelled) {
                return false;
            }
            offering = true;
            return true;
        }

        private synchronized boolean cancelBeforeOffer() {
            if (offering) {
                return false;
            }
            cancelled = true;
            return true;
        }

        private void terminal(CoreResponse response) {
            if (commandFuture != null) {
                commandFuture.complete(new CoreCommandOutcome.Terminal(response));
            } else {
                queryFuture.complete(response);
            }
        }

        private void notAccepted(CoreCommandOutcome.NotAccepted rejection) {
            admissionFuture.complete(rejection.rawOfferResult());
            if (commandFuture != null) {
                commandFuture.complete(rejection);
            } else if (queryFuture != null) {
                queryFuture.completeExceptionally(new CoreCommandOutcome.NotAcceptedException(rejection));
            }
        }

        private void resultUnknown() {
            if (commandFuture != null) {
                commandFuture.complete(new CoreCommandOutcome.ResultUnknown(operationId));
            } else if (queryFuture != null) {
                queryFuture.completeExceptionally(new ResultUnknownException(operationId,
                        "Aeron control query was admitted but its result is unknown"));
            }
        }
    }
}
