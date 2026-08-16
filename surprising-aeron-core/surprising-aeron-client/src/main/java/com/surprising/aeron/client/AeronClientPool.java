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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class AeronClientPool implements AutoCloseable {

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
    private final EgressDispatcher egressDispatcher = new EgressDispatcher();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<MediaDriver> mediaDriver = new AtomicReference<>();

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
        this.commandAgents = new AgentLane[capacity.commandSessions()];
        for (int index = 0; index < commandAgents.length; index++) {
            commandAgents[index] = new AgentLane("command-" + (index + 1),
                    capacity.commandMailboxCapacity(), capacity.maxCommandInFlightPerSession(),
                    stableLong(this.sourceIdentity + ':' + productLine + ':' + this.sourceEpoch + ':' + index));
        }
        this.reservedControlAgent = new AgentLane("control", capacity.queryMailboxCapacity(),
                capacity.maxReservedInFlight(), stableLong(this.sourceIdentity + ':' + productLine + ':'
                        + this.sourceEpoch + ":control"));
        if (startAgents) {
            for (AgentLane agent : commandAgents) {
                agent.start();
            }
            reservedControlAgent.start();
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
        Request request = agent.commandRequest(type, commandId, userId, safePayload);
        if (!agent.enqueue(request)) {
            request.commandFuture.complete(CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED));
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
        CompletableFuture<CoreCommandOutcome> future = commandOutcomeAsync(type, commandId, userId, payload);
        CoreCommandOutcome immediate = future.getNow(null);
        if (immediate == null || immediate instanceof CoreCommandOutcome.Terminal
                || immediate instanceof CoreCommandOutcome.ResultUnknown) {
            return TryCommandResult.SENT;
        }
        CoreCommandOutcome.NotAccepted rejected = (CoreCommandOutcome.NotAccepted) immediate;
        return switch (rejected.reason()) {
            case CLIENT_BACKPRESSURED -> TryCommandResult.BACK_PRESSURED;
            case NOT_CONNECTED -> TryCommandResult.NOT_READY;
            case CLOSED -> TryCommandResult.CLOSED;
            case ADMIN_ACTION, MAX_POSITION_EXCEEDED, UNKNOWN -> TryCommandResult.UNAVAILABLE;
        };
    }

    public CompletableFuture<CoreResponse> controlQueryAsync(
            CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        requireReservedControl(type);
        Objects.requireNonNull(queryId, "queryId");
        byte[] safePayload = requirePayload(payload);
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CLOSED"));
        }
        Request request = reservedControlAgent.queryRequest(type, queryId, userId, safePayload);
        if (!reservedControlAgent.enqueue(request)) {
            request.queryFuture.completeExceptionally(new CoreCommandOutcome.NotAcceptedException(
                    CoreCommandOutcome.notAccepted(Publication.BACK_PRESSURED)));
        }
        return request.queryFuture;
    }

    public CoreResponse query(CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        return controlQueryAsync(type, queryId, userId, payload).join();
    }

    public CoreResponse commandResult(UUID commandId, long userId) {
        Objects.requireNonNull(commandId, "commandId");
        return query(CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), userId,
                com.surprising.aeron.protocol.CoreStateQueryCodec.encodeCommandResultQuery(commandId));
    }

    int agentThreadCount() {
        return commandAgents.length + 1;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<RuntimeException> failures = new ArrayList<>();
        for (AgentLane agent : commandAgents) {
            agent.stop(failures);
        }
        reservedControlAgent.stop(failures);
        MediaDriver driver;
        synchronized (mediaDriver) {
            driver = mediaDriver.getAndSet(null);
        }
        if (driver != null) {
            try {
                driver.close();
            } catch (RuntimeException exception) {
                failures.add(exception);
            }
        }
        if (!failures.isEmpty()) {
            RuntimeException failure = failures.removeFirst();
            failures.forEach(failure::addSuppressed);
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
        CoreCommandOutcome.NotAccepted rejected = (CoreCommandOutcome.NotAccepted) outcome;
        throw new CoreCommandOutcome.NotAcceptedException(rejected);
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

    private final class AgentLane implements Runnable {
        private final ArrayBlockingQueue<Request> mailbox;
        private final int maxInFlight;
        private final long sourceId;
        private final AtomicLong nextSequence = new AtomicLong();
        private final AtomicLong nextCorrelation = new AtomicLong();
        private final Map<Long, Request> pending = new LinkedHashMap<>();
        private final Thread thread;
        private volatile Session session;

        private AgentLane(String laneName, int mailboxCapacity, int maxInFlight, long sourceId) {
            this.mailbox = new ArrayBlockingQueue<>(mailboxCapacity);
            this.maxInFlight = maxInFlight;
            this.sourceId = sourceId;
            this.thread = new Thread(this, clientName + '-' + laneName + "-agent");
            this.thread.setDaemon(true);
        }

        private Request commandRequest(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
            long correlationId = nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                    CommandSource.GATEWAY, sourceId, nextSequence.incrementAndGet(), userId,
                    Instant.now().toEpochMilli(), correlationId), payload);
            return Request.command(message, commandId);
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

        private void start() {
            thread.start();
        }

        @Override
        public void run() {
            openInitialSession();
            while (!closed.get()) {
                boolean worked = pollSession();
                while (pending.size() < maxInFlight) {
                    Request request = mailbox.poll();
                    if (request == null) {
                        break;
                    }
                    worked = true;
                    admit(request);
                }
                expireAdmitted();
                if (!worked) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1);
                    } catch (InterruptedException exception) {
                        if (!closed.get()) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            rejectQueuedAsClosed();
            completeAdmittedAsUnknown();
            closeSession();
        }

        private void openInitialSession() {
            try {
                session = Objects.requireNonNull(sessionFactory.open(), "sessionFactory returned null");
            } catch (RuntimeException exception) {
                session = null;
            }
        }

        private boolean pollSession() {
            Session current = session;
            if (current == null) {
                return false;
            }
            try {
                int fragments = current.pollEgress(capacity.egressFragmentLimit());
                egressDispatcher.dispatch(current, pending);
                RuntimeException failure = current.sessionFailure();
                if (failure != null) {
                    completeAdmittedAsUnknown();
                    closeSession();
                }
                return fragments > 0;
            } catch (RuntimeException exception) {
                completeAdmittedAsUnknown();
                closeSession();
                return true;
            }
        }

        private void admit(Request request) {
            Session current = session;
            if (current == null) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.NOT_CONNECTED));
                return;
            }
            long offerResult;
            try {
                offerResult = current.offer(request.message);
            } catch (RuntimeException exception) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Long.MIN_VALUE));
                closeSession();
                return;
            }
            if (offerResult > 0) {
                request.deadlineNanos = System.nanoTime() + responseTimeout.toNanos();
                pending.put(request.message.header().correlationId(), request);
            } else {
                request.notAccepted(CoreCommandOutcome.notAccepted(offerResult));
                if (offerResult == Publication.CLOSED || offerResult == Publication.MAX_POSITION_EXCEEDED) {
                    closeSession();
                }
            }
        }

        private void expireAdmitted() {
            long now = System.nanoTime();
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Request request = iterator.next().getValue();
                if (now >= request.deadlineNanos) {
                    iterator.remove();
                    request.resultUnknown();
                }
            }
        }

        private void rejectQueuedAsClosed() {
            Request request;
            while ((request = mailbox.poll()) != null) {
                request.notAccepted(CoreCommandOutcome.notAccepted(Publication.CLOSED));
            }
        }

        private void completeAdmittedAsUnknown() {
            pending.values().forEach(Request::resultUnknown);
            pending.clear();
        }

        private void stop(List<RuntimeException> failures) {
            thread.interrupt();
            if (thread.isAlive()) {
                try {
                    thread.join(Math.max(1L, responseTimeout.toMillis()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failures.add(new IllegalStateException("Interrupted while stopping Aeron agent", exception));
                }
                if (thread.isAlive()) {
                    failures.add(new IllegalStateException("Aeron agent did not terminate: " + thread.getName()));
                }
            } else {
                rejectQueuedAsClosed();
                completeAdmittedAsUnknown();
                closeSession();
            }
        }

        private void closeSession() {
            Session current = session;
            session = null;
            if (current != null) {
                current.close();
            }
        }
    }

    private static final class EgressDispatcher {
        private void dispatch(Session session, Map<Long, Request> pending) {
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
    }

    private static final class Request {
        private final CoreMessage message;
        private final UUID operationId;
        private final CompletableFuture<CoreCommandOutcome> commandFuture;
        private final CompletableFuture<CoreResponse> queryFuture;
        private long deadlineNanos;

        private Request(CoreMessage message, UUID operationId,
                        CompletableFuture<CoreCommandOutcome> commandFuture,
                        CompletableFuture<CoreResponse> queryFuture) {
            this.message = message;
            this.operationId = operationId;
            this.commandFuture = commandFuture;
            this.queryFuture = queryFuture;
        }

        private static Request command(CoreMessage message, UUID commandId) {
            return new Request(message, commandId, new CompletableFuture<>(), null);
        }

        private static Request query(CoreMessage message, UUID queryId) {
            return new Request(message, queryId, null, new CompletableFuture<>());
        }

        private void terminal(CoreResponse response) {
            if (commandFuture != null) {
                commandFuture.complete(new CoreCommandOutcome.Terminal(response));
            } else {
                queryFuture.complete(response);
            }
        }

        private void notAccepted(CoreCommandOutcome.NotAccepted rejection) {
            if (commandFuture != null) {
                commandFuture.complete(rejection);
            } else {
                queryFuture.completeExceptionally(new CoreCommandOutcome.NotAcceptedException(rejection));
            }
        }

        private void resultUnknown() {
            if (commandFuture != null) {
                commandFuture.complete(new CoreCommandOutcome.ResultUnknown(operationId));
            } else {
                queryFuture.completeExceptionally(new ResultUnknownException(operationId,
                        "Aeron control query was admitted but its result is unknown"));
            }
        }
    }
}
