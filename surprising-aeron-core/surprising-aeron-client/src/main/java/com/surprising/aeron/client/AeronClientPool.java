package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AeronClientPool implements AutoCloseable {

    private static final int MAX_SUBMIT_ATTEMPTS = 3;

    private final ProductLine productLine;
    private final List<String> hostnames;
    private final String egressHostname;
    private final Duration responseTimeout;
    private final ClientSlot[] clients;
    private final ExecutorService asyncExecutor;
    private final AtomicInteger nextClient = new AtomicInteger();

    public AeronClientPool(
            String clientName,
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            int clientConnections) {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("clientName is required");
        }
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
        if (clientConnections < 1 || clientConnections > 64) {
            throw new IllegalArgumentException("clientConnections must be in [1,64]");
        }
        this.clients = new ClientSlot[clientConnections];
        this.asyncExecutor = Executors.newFixedThreadPool(Math.min(128, clientConnections * 2), runnable -> {
            Thread thread = new Thread(runnable, clientName + "-async");
            thread.setDaemon(true);
            return thread;
        });
        long processId = ProcessHandle.current().pid();
        long epoch = System.currentTimeMillis();
        for (int index = 0; index < clients.length; index++) {
            long sourceId = stableLong(clientName.trim() + ':' + productLine + ':' + processId + ':' + epoch + ':' + index);
            clients[index] = new ClientSlot(sourceId);
        }
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        if (type == null || type.kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("command message type is required");
        }
        ClientSlot slot = nextSlot();
        synchronized (slot) {
            long sourceSequence = slot.nextSequence.incrementAndGet();
            long correlationId = slot.nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.command(type,
                    Objects.requireNonNull(commandId, "commandId"), productLine,
                    CommandSource.GATEWAY, slot.sourceId, sourceSequence, userId,
                    Instant.now().toEpochMilli(), correlationId), requirePayload(payload));
            return submit(slot, message);
        }
    }

    public CompletableFuture<CoreResponse> commandAsync(CoreMessageType type, UUID commandId,
                                                         long userId, byte[] payload) {
        return CompletableFuture.supplyAsync(() -> command(type, commandId, userId, payload), asyncExecutor);
    }

    public CoreResponse query(CoreMessageType type, UUID queryId, long userId, byte[] payload) {
        if (type == null || type.kind() != com.surprising.aeron.protocol.WireMessageKind.QUERY) {
            throw new IllegalArgumentException("query message type is required");
        }
        ClientSlot slot = nextSlot();
        synchronized (slot) {
            long correlationId = slot.nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.query(type,
                    Objects.requireNonNull(queryId, "queryId"), productLine,
                    CommandSource.GATEWAY, slot.sourceId, 0, userId,
                    Instant.now().toEpochMilli(), correlationId), requirePayload(payload));
            return submit(slot, message);
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        asyncExecutor.shutdownNow();
        for (ClientSlot slot : clients) {
            synchronized (slot) {
                try {
                    closeClient(slot);
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private ClientSlot nextSlot() {
        return clients[Math.floorMod(nextClient.getAndIncrement(), clients.length)];
    }

    private SurprisingAeronClient client(ClientSlot slot) {
        if (slot.client == null) {
            slot.client = SurprisingAeronClient.connect(productLine, hostnames, egressHostname, responseTimeout);
        }
        return slot.client;
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
        private SurprisingAeronClient client;
        private final long sourceId;
        private final AtomicLong nextSequence = new AtomicLong();
        private final AtomicLong nextCorrelation = new AtomicLong();

        private ClientSlot(long sourceId) {
            this.sourceId = sourceId;
        }
    }
}
