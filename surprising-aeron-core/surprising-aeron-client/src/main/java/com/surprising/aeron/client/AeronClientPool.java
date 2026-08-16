package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public final class AeronClientPool implements AutoCloseable {

    private static final int MAX_SUBMIT_ATTEMPTS = 3;

    public enum TryCommandResult {
        SENT,
        BUSY,
        NOT_READY,
        BACK_PRESSURED,
        UNAVAILABLE,
        CLOSED
    }

    private final String clientName;
    private final ProductLine productLine;
    private final List<String> hostnames;
    private final String egressHostname;
    private final Duration responseTimeout;
    private final String sourceIdentity;
    private final String sourceEpoch;
    private final ClientSlot[] clients;
    private final ExecutorService commandExecutor;
    private final ExecutorService connectionExecutor;
    private final AtomicInteger nextClient = new AtomicInteger();
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
        if (clientConnections < 1 || clientConnections > 64) {
            throw new IllegalArgumentException("clientConnections must be in [1,64]");
        }
        this.clients = new ClientSlot[clientConnections];
        AtomicInteger commandThreadSequence = new AtomicInteger();
        ThreadFactory commandThreadFactory = runnable -> {
            Thread thread = new Thread(runnable, this.clientName + "-command-"
                    + commandThreadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        AtomicInteger connectionThreadSequence = new AtomicInteger();
        ThreadFactory connectionThreadFactory = runnable -> {
            Thread thread = new Thread(runnable, this.clientName + "-connect-"
                    + connectionThreadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        int queueCapacity = Math.max(1, Math.min(256, clientConnections * 4));
        this.commandExecutor = new java.util.concurrent.ThreadPoolExecutor(
                clientConnections, clientConnections, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), commandThreadFactory,
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        this.connectionExecutor = new java.util.concurrent.ThreadPoolExecutor(
                clientConnections, clientConnections, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), connectionThreadFactory,
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        for (int index = 0; index < clients.length; index++) {
            long sourceId = stableLong(this.sourceIdentity + ':' + productLine + ':' + this.sourceEpoch + ':' + index);
            clients[index] = new ClientSlot(sourceId);
        }
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        if (type == null || type.kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("command message type is required");
        }
        ClientSlot slot = acquireCommandSlot(userId);
        try {
            long sourceSequence = slot.nextSequence.incrementAndGet();
            long correlationId = slot.nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.command(type,
                    Objects.requireNonNull(commandId, "commandId"), productLine,
                    CommandSource.GATEWAY, slot.sourceId, sourceSequence, userId,
                    Instant.now().toEpochMilli(), correlationId), requirePayload(payload));
            return submit(slot, message);
        } finally {
            slot.inFlight.set(false);
        }
    }

    public CompletableFuture<CoreResponse> commandAsync(
            CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Aeron client pool is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> command(type, commandId, userId, payload), commandExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public TryCommandResult tryCommandOnce(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        if (type == null || type.kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("command message type is required");
        }
        Objects.requireNonNull(commandId, "commandId");
        requirePayload(payload);
        if (closed.get()) {
            return TryCommandResult.CLOSED;
        }
        ClientSlot slot = tryAcquireCommandSlot(userId);
        if (slot == null) {
            return TryCommandResult.BUSY;
        }
        try {
            SurprisingAeronClient client = slot.client;
            if (client == null) {
                scheduleConnection(slot);
                return TryCommandResult.NOT_READY;
            }
            long sourceSequence = slot.nextSequence.get() + 1;
            long correlationId = -slot.nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                    CommandSource.GATEWAY, slot.sourceId, sourceSequence, userId,
                    Instant.now().toEpochMilli(), correlationId), payload);
            long offerResult;
            try {
                offerResult = client.trySubmit(message);
            } catch (RuntimeException exception) {
                closeClient(slot);
                return TryCommandResult.UNAVAILABLE;
            }
            if (offerResult >= 0) {
                slot.nextSequence.compareAndSet(sourceSequence - 1, sourceSequence);
                return TryCommandResult.SENT;
            }
            if (offerResult == Publication.CLOSED || offerResult == Publication.MAX_POSITION_EXCEEDED) {
                closeClient(slot);
                return TryCommandResult.UNAVAILABLE;
            }
            if (offerResult == Publication.NOT_CONNECTED) {
                return TryCommandResult.NOT_READY;
            }
            return TryCommandResult.BACK_PRESSURED;
        } finally {
            slot.inFlight.set(false);
        }
    }

    public CoreResponse query(CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        if (type == null || type.kind() != com.surprising.aeron.protocol.WireMessageKind.QUERY) {
            throw new IllegalArgumentException("query message type is required");
        }
        ClientSlot slot = acquireSlot();
        try {
            long correlationId = slot.nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.query(type,
                    Objects.requireNonNull(queryId, "queryId"), productLine,
                    CommandSource.GATEWAY, slot.sourceId, 0, userId,
                    Instant.now().toEpochMilli(), correlationId), requirePayload(payload));
            return submit(slot, message);
        } finally {
            slot.inFlight.set(false);
        }
    }

    public CoreResponse commandResult(UUID commandId, long userId) {
        Objects.requireNonNull(commandId, "commandId");
        return query(CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), userId,
                com.surprising.aeron.protocol.CoreStateQueryCodec.encodeCommandResultQuery(commandId));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = closePendingConnections();
        connectionExecutor.shutdown();
        commandExecutor.shutdown();
        try {
            long timeoutMillis = Math.max(1L, responseTimeout.toMillis());
            if (!connectionExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                connectionExecutor.shutdownNow();
                if (!connectionExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    if (failure == null) {
                        failure = new IllegalStateException("Aeron connection executor did not terminate");
                    }
                }
            }
            if (!commandExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                commandExecutor.shutdownNow();
                if (!commandExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    if (failure == null) {
                        failure = new IllegalStateException("Aeron command executor did not terminate");
                    }
                }
            }
        } catch (InterruptedException exception) {
            connectionExecutor.shutdownNow();
            commandExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            failure = new IllegalStateException("Interrupted while stopping Aeron command executor", exception);
        }
        for (ClientSlot slot : clients) {
            acquireSlotForClose(slot);
            try {
                try {
                    closeClient(slot);
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            } finally {
                slot.inFlight.set(false);
            }
        }
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

    private RuntimeException closePendingConnections() {
        RuntimeException failure = null;
        for (ClientSlot slot : clients) {
            synchronized (slot) {
                if (slot.connection == null) {
                    continue;
                }
                try {
                    slot.connection.close();
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        return failure;
    }

    private ClientSlot acquireSlot() {
        if (closed.get()) {
            throw new IllegalStateException("Aeron client pool is closed");
        }
        int start = nextClient.getAndIncrement();
        long deadline = System.nanoTime() + responseTimeout.toNanos();
        int spins = 0;
        for (;;) {
            for (int offset = 0; offset < clients.length; offset++) {
                ClientSlot slot = clients[Math.floorMod(start + offset, clients.length)];
                if (slot.inFlight.compareAndSet(false, true)) {
                    if (closed.get()) {
                        slot.inFlight.set(false);
                        throw new IllegalStateException("Aeron client pool is closed");
                    }
                    return slot;
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Aeron client pool is saturated");
            }
            if ((spins++ & 63) == 0) {
                LockSupport.parkNanos(1_000L);
            } else {
                Thread.onSpinWait();
            }
            start = nextClient.getAndIncrement();
            if (closed.get()) {
                throw new IllegalStateException("Aeron client pool is closed");
            }
        }
    }

    private ClientSlot acquireCommandSlot(long userId) {
        if (closed.get()) {
            throw new IllegalStateException("Aeron client pool is closed");
        }
        int index = Math.floorMod(Long.hashCode(userId), clients.length);
        ClientSlot slot = clients[index];
        long deadline = System.nanoTime() + responseTimeout.toNanos();
        int spins = 0;
        for (;;) {
            if (slot.inFlight.compareAndSet(false, true)) {
                if (closed.get()) {
                    slot.inFlight.set(false);
                    throw new IllegalStateException("Aeron client pool is closed");
                }
                return slot;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Aeron command lane is saturated for user " + userId);
            }
            if ((spins++ & 63) == 0) {
                LockSupport.parkNanos(1_000L);
            } else {
                Thread.onSpinWait();
            }
            if (closed.get()) {
                throw new IllegalStateException("Aeron client pool is closed");
            }
        }
    }

    private ClientSlot tryAcquireCommandSlot(long userId) {
        if (closed.get()) {
            return null;
        }
        ClientSlot slot = clients[Math.floorMod(Long.hashCode(userId), clients.length)];
        return slot.inFlight.compareAndSet(false, true) ? slot : null;
    }

    private void scheduleConnection(ClientSlot slot) {
        if (closed.get() || slot.client != null || !slot.connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            connectionExecutor.execute(() -> {
                SurprisingAeronClient.AsyncConnection connection = null;
                try {
                    synchronized (slot) {
                        if (!closed.get() && slot.client == null && slot.connection == null) {
                            connection = SurprisingAeronClient.connectAsync(productLine, hostnames,
                                    egressHostname, responseTimeout, sharedMediaDriver());
                            slot.connection = connection;
                        }
                    }
                    while (!closed.get() && connection != null && slot.client == null) {
                        boolean connected = false;
                        synchronized (slot) {
                            if (closed.get() || slot.connection != connection || slot.client != null) {
                                break;
                            }
                            SurprisingAeronClient client = connection.poll();
                            if (client != null) {
                                if (closed.get()) {
                                    client.close();
                                    slot.connection = null;
                                    connection = null;
                                } else {
                                    slot.client = client;
                                    slot.connection = null;
                                    connection = null;
                                }
                                connected = true;
                            }
                        }
                        if (connected || connection == null) {
                            break;
                        }
                        LockSupport.parkNanos(1_000_000L);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (connection != null) {
                        synchronized (slot) {
                            if (slot.connection == connection) {
                                slot.connection = null;
                            }
                        }
                        if (slot.client == null) {
                            try {
                                connection.close();
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                    slot.connecting.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            slot.connecting.set(false);
        }
    }

    private static void acquireSlotForClose(ClientSlot slot) {
        while (!slot.inFlight.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }
    }

    private SurprisingAeronClient client(ClientSlot slot) {
        synchronized (slot) {
            if (slot.client == null) {
                slot.client = SurprisingAeronClient.connect(productLine, hostnames, egressHostname, responseTimeout,
                        sharedMediaDriver());
            }
            return slot.client;
        }
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

    private CoreResponse submit(ClientSlot slot, CoreMessage message) {
        RuntimeException firstFailure = null;
        for (int attempt = 1; attempt <= MAX_SUBMIT_ATTEMPTS; attempt++) {
            try {
                return client(slot).submit(message);
            } catch (RuntimeException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                } else {
                    firstFailure.addSuppressed(exception);
                }
                try {
                    closeClient(slot);
                } catch (RuntimeException closeFailure) {
                    firstFailure.addSuppressed(closeFailure);
                }
            }
        }
        throw firstFailure;
    }

    private static void closeClient(ClientSlot slot) {
        if (slot.client != null) {
            try {
                slot.client.close();
            } finally {
                slot.client = null;
            }
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

    private static final class ClientSlot {
        private volatile SurprisingAeronClient client;
        private volatile SurprisingAeronClient.AsyncConnection connection;
        private final long sourceId;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private final AtomicBoolean connecting = new AtomicBoolean();
        private final AtomicLong nextSequence = new AtomicLong();
        private final AtomicLong nextCorrelation = new AtomicLong();

        private ClientSlot(long sourceId) {
            this.sourceId = sourceId;
        }
    }
}
