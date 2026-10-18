package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreRoute;
import com.surprising.aeron.protocol.ProductLineClusterLayout;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.Publication;
import io.aeron.logbuffer.Header;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

public final class SurprisingAeronClient implements AeronClientPool.Session, EgressListener {

    private static final int AERON_EGRESS_POLL_LIMIT = 10;
    private static final AtomicLong MEDIA_DRIVER_SEQUENCE = new AtomicLong();

    private final ProductLine productLine;
    private final Duration responseTimeout;
    private final MediaDriver mediaDriver;
    private final boolean closeMediaDriver;
    private final ClusterOperations cluster;
    /** Pool sessions are confined to their existing dispatcher; standalone sessions serialize shared callers. */
    private final Thread ioOwner;
    private final java.util.concurrent.locks.ReentrantLock clientLock = new java.util.concurrent.locks.ReentrantLock();
    private final ScheduledExecutorService keepAliveExecutor;
    private final IdleStrategy idleStrategy = new BackoffIdleStrategy();
    private final Map<Long, CoreResponse> responses = new ConcurrentHashMap<>();
    private UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[CoreProtocol.HEADER_LENGTH]);
    private volatile RuntimeException sessionFailure;
    private boolean closed;

    private SurprisingAeronClient(
            ProductLine productLine,
            Duration responseTimeout,
            MediaDriver mediaDriver,
            boolean closeMediaDriver,
            AeronCluster cluster, Thread ioOwner) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        this.mediaDriver = Objects.requireNonNull(mediaDriver, "mediaDriver");
        this.closeMediaDriver = closeMediaDriver;
        this.cluster = new AeronClusterOperations(Objects.requireNonNull(cluster, "cluster"));
        this.ioOwner = ioOwner;
        this.keepAliveExecutor = closeMediaDriver ? startKeepAlive() : null;
    }

    SurprisingAeronClient(
            ProductLine productLine,
            Duration responseTimeout,
            ClusterOperations cluster) {
        this(productLine, responseTimeout, cluster, null);
    }

    SurprisingAeronClient(ProductLine productLine, Duration responseTimeout,
                          ClusterOperations cluster, Thread ioOwner) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        this.mediaDriver = null;
        this.closeMediaDriver = false;
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.ioOwner = ioOwner;
        this.keepAliveExecutor = null;
    }

    public static SurprisingAeronClient connect(ProductLine productLine, List<String> hostnames) {
        return connect(productLine, hostnames, "localhost", Duration.ofSeconds(5));
    }

    public static SurprisingAeronClient connect(
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout) {
        if (responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
        MediaDriver mediaDriver = newMediaDriver();
        AsyncConnection connection = null;
        try {
            // Keep connect and cleanup on one owner. A timeout must close the Aeron client
            // before its driver; cancelling a separate connector races these lifetimes.
            connection = new AsyncConnection(productLine, hostnames, egressHostname,
                    responseTimeout, mediaDriver, true);
            long deadline = System.nanoTime() + responseTimeout.toNanos();
            IdleStrategy connectIdle = new BackoffIdleStrategy();
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("interrupted connecting to Aeron Cluster");
                }
                SurprisingAeronClient connected = connection.poll();
                if (connected != null) return connected;
                connectIdle.idle();
            }
            throw new io.aeron.exceptions.TimeoutException(
                    "timed out connecting to Aeron Cluster productLine=" + productLine);
        } catch (RuntimeException | Error failure) {
            try {
                if (connection != null) connection.close();
                else mediaDriver.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static AsyncConnection connectAsync(
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            MediaDriver mediaDriver) {
        if (responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
        return new AsyncConnection(productLine, hostnames, egressHostname, responseTimeout, mediaDriver);
    }

    private static AeronCluster.Context clusterContext(
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            MediaDriver mediaDriver,
            EgressListener egressListener) {
        return new AeronCluster.Context()
                .clientName("surprising-" + productLine.name().toLowerCase())
                .messageTimeoutNs(responseTimeout.toNanos())
                .newLeaderTimeoutNs(responseTimeout.toNanos())
                .egressListener(egressListener)
                .egressChannel("aeron:udp?endpoint=" + egressHostname + ":0")
                .aeronDirectoryName(mediaDriver.aeronDirectoryName())
                .ingressChannel("aeron:udp")
                .ingressEndpoints(ProductLineClusterLayout.ingressEndpoints(productLine, hostnames));
    }

    static MediaDriver newMediaDriver() {
        return newMediaDriver("surprising-aeron-client-" + ProcessHandle.current().pid()
                + '-' + MEDIA_DRIVER_SEQUENCE.incrementAndGet());
    }

    /** Caller owns the driver; uses the same client threading configuration as standalone pools. */
    public static MediaDriver newMediaDriver(String aeronDirectoryName) {
        MediaDriver.Context context = new MediaDriver.Context()
                .threadingMode(clientThreadingMode())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);
        if (aeronDirectoryName != null && !aeronDirectoryName.isBlank()) {
            context.aeronDirectoryName(aeronDirectoryName);
        }
        return MediaDriver.launchEmbedded(context);
    }

    static Duration pooledConnectionTimeout() {
        Duration timeout = Duration.ofMillis(Long.getLong("surprising.aeron.client.connect-timeout-ms", 30_000L));
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Aeron connect timeout must be positive");
        return timeout;
    }

    static ThreadingMode clientThreadingMode() {
        String configured = System.getProperty("surprising.aeron.client.threading-mode");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("AERON_CLIENT_THREADING_MODE", "SHARED");
        }
        try {
            return ThreadingMode.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "surprising.aeron.client.threading-mode must be a valid Aeron ThreadingMode: " + configured,
                    exception);
        }
    }

    public CoreResponse submit(CoreMessage message) {
        long deadline = System.nanoTime() + responseTimeout.toNanos();
        idleStrategy.reset();
        long offerResult = offer(message);
        if (offerResult <= 0) {
            CoreCommandOutcome.NotAccepted rejected = CoreCommandOutcome.notAccepted(offerResult);
            throw new IllegalStateException("Aeron command was not accepted: " + rejected.reason()
                    + " (offer=" + rejected.rawOfferResult() + ')');
        }
        while (System.nanoTime() < deadline) {
            pollAndCheckSession();
            CoreResponse response = takeResponse(message.header().correlationId());
            if (response != null) {
                return response;
            }
            idleStrategy.idle();
        }
        throw resultUnknown(message, "timed out waiting for committed command result");
    }

    public long trySubmit(CoreMessage message) {
        return offer(message);
    }

    @Override
    public long offer(CoreMessage message) {
        if (ioOwner != null) { requireIoOwner(); return offerToCluster(message); }
        clientLock.lock();
        try {
            return offerToCluster(message);
        } finally {
            clientLock.unlock();
        }
    }

    private long offerToCluster(CoreMessage message) {
        ensureOpen();
        if (message.header().productLine() != productLine) {
            throw new IllegalArgumentException("client and message product line differ");
        }
        int encodedLength = CoreMessageCodec.encodedLength(message);
        if (ingressBuffer.capacity() < encodedLength) {
            ingressBuffer = new UnsafeBuffer(new byte[grownCapacity(ingressBuffer.capacity(), encodedLength)]);
        }
        CoreMessageCodec.encode(message, ingressBuffer.byteArray());
        return cluster.offer(ingressBuffer, 0, encodedLength);
    }

    private static int grownCapacity(int currentCapacity, int requiredCapacity) {
        int capacity = Math.max(currentCapacity, CoreProtocol.HEADER_LENGTH);
        while (capacity < requiredCapacity) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        return capacity;
    }

    @Override
    public int pollEgress(int fragmentLimit) {
        if (ioOwner != null) { requireIoOwner(); return pollClusterEgress(fragmentLimit); }
        clientLock.lock();
        try {
            return pollClusterEgress(fragmentLimit);
        } finally {
            clientLock.unlock();
        }
    }

    private int pollClusterEgress(int fragmentLimit) {
        ensureOpen();
        if (fragmentLimit <= 0) {
            throw new IllegalArgumentException("fragmentLimit must be positive");
        }
        int workCount = 0;
        int remainingFragments = fragmentLimit;
        while (remainingFragments >= AERON_EGRESS_POLL_LIMIT) {
            int polled = cluster.pollEgress();
            if (polled < 0) {
                throw new IllegalStateException("Aeron egress poll returned invalid fragment count: " + polled);
            }
            workCount += polled;
            remainingFragments -= AERON_EGRESS_POLL_LIMIT;
            if (polled < AERON_EGRESS_POLL_LIMIT) {
                break;
            }
        }
        return workCount;
    }

    static int pollEgressBounded(int fragmentLimit, IntSupplier poller) {
        if (fragmentLimit <= 0) {
            throw new IllegalArgumentException("fragmentLimit must be positive");
        }
        Objects.requireNonNull(poller, "poller");
        int workCount = 0;
        int remainingFragments = fragmentLimit;
        while (remainingFragments >= AERON_EGRESS_POLL_LIMIT) {
            int polled = poller.getAsInt();
            if (polled < 0) {
                throw new IllegalStateException("Aeron egress poll returned invalid fragment count: " + polled);
            }
            workCount += polled;
            remainingFragments -= AERON_EGRESS_POLL_LIMIT;
            if (polled < AERON_EGRESS_POLL_LIMIT) {
                break;
            }
        }
        return workCount;
    }

    @Override
    public CoreResponse takeResponse(long correlationId) {
        return responses.remove(correlationId);
    }

    @Override
    public int drainResponses(java.util.function.BiConsumer<Long, CoreResponse> consumer) {
        if (responses.isEmpty()) {
            return 0;
        }
        int count = 0;
        var iterator = responses.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long correlationId = entry.getKey();
            CoreResponse response = entry.getValue();
            iterator.remove();
            consumer.accept(correlationId, response);
            count++;
        }
        return count;
    }

    @Override
    public RuntimeException sessionFailure() {
        return sessionFailure;
    }

    @Override
    public boolean keepAlive() {
        if (ioOwner != null) { requireIoOwner(); return keepClusterAlive(); }
        clientLock.lock();
        try {
            return keepClusterAlive();
        } finally {
            clientLock.unlock();
        }
    }

    private boolean keepClusterAlive() {
        ensureOpen();
        return cluster.sendKeepAlive();
    }

    @Override
    public void onMessage(
            long clusterSessionId,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {
        try {
            if (buffer == null || length < CoreProtocol.HEADER_LENGTH) {
                throw new ProtocolException("message shorter than fixed header");
            }
            int magic = buffer.getInt(offset, ByteOrder.LITTLE_ENDIAN);
            if (magic != CoreProtocol.MAGIC) {
                throw new ProtocolException("invalid protocol magic");
            }
            int schemaVersion = Short.toUnsignedInt(buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN));
            if (schemaVersion != CoreProtocol.SCHEMA_VERSION) {
                throw new ProtocolException("unsupported schema version: " + schemaVersion);
            }
            WireMessageKind kind = WireMessageKind.fromWireCode(Byte.toUnsignedInt(buffer.getByte(offset + 6)));
            ProductLine messageProductLine = ProductLineWireCode.decode(Byte.toUnsignedInt(buffer.getByte(offset + 7)));
            CoreMessageType messageType = CoreMessageType.fromWireCode(
                    Short.toUnsignedInt(buffer.getShort(offset + 8, ByteOrder.LITTLE_ENDIAN)));
            if (messageType.kind() != kind) {
                throw new IllegalArgumentException("messageType does not belong to kind: " + messageType);
            }
            CommandSource.fromWireCode(Byte.toUnsignedInt(buffer.getByte(offset + 10)));
            int shardCode = Byte.toUnsignedInt(buffer.getByte(offset + 11));
            int headerLength = Short.toUnsignedInt(buffer.getShort(offset + 12, ByteOrder.LITTLE_ENDIAN));
            int headerRouteVersion = Short.toUnsignedInt(buffer.getShort(offset + 14, ByteOrder.LITTLE_ENDIAN));
            if (headerLength != CoreProtocol.HEADER_LENGTH) {
                throw new ProtocolException("invalid header length: " + headerLength);
            }
            CoreRoute.fromWireCodes(shardCode, headerRouteVersion);
            long sourceSequence = buffer.getLong(offset + 40, ByteOrder.LITTLE_ENDIAN);
            if (sourceSequence < 0) {
                throw new IllegalArgumentException("sourceSequence must not be negative");
            }
            long submittedAtEpochMillis = buffer.getLong(offset + 56, ByteOrder.LITTLE_ENDIAN);
            if (submittedAtEpochMillis < 0) {
                throw new IllegalArgumentException("submittedAtEpochMillis must not be negative");
            }
            long correlationId = buffer.getLong(offset + 64, ByteOrder.LITTLE_ENDIAN);
            int payloadLength = buffer.getInt(offset + 72, ByteOrder.LITTLE_ENDIAN);
            if (payloadLength < 0 || payloadLength > CoreMessageCodec.MAX_PAYLOAD_LENGTH
                    || headerLength + payloadLength != length) {
                throw new ProtocolException("invalid payload length: " + payloadLength);
            }
            if (messageProductLine != productLine) {
                sessionFailure = new IllegalStateException("received response from another product line");
                return;
            }
            if (correlationId < 0) {
                return;
            }
            int payloadOffset = offset + headerLength;
            if (payloadLength < CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH) {
                throw new ProtocolException("response payload is shorter than fixed response");
            }
            ResponseStatus status = ResponseStatus.fromWireCode(
                    buffer.getInt(payloadOffset, ByteOrder.LITTLE_ENDIAN));
            ResponseStatus commandStatus = ResponseStatus.fromWireCode(
                    buffer.getInt(payloadOffset + 4, ByteOrder.LITTLE_ENDIAN));
            CoreResultCode resultCode = CoreResultCode.fromWireCode(
                    buffer.getInt(payloadOffset + 8, ByteOrder.LITTLE_ENDIAN));
            int routeVersion = buffer.getInt(payloadOffset + 12, ByteOrder.LITTLE_ENDIAN);
            long committedCoreSequence = buffer.getLong(payloadOffset + 16, ByteOrder.LITTLE_ENDIAN);
            long appliedCommandCount = buffer.getLong(payloadOffset + 24, ByteOrder.LITTLE_ENDIAN);
            int dataLength = buffer.getInt(payloadOffset + 32, ByteOrder.LITTLE_ENDIAN);
            if (dataLength < 0 || dataLength != payloadLength - CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH) {
                throw new ProtocolException("invalid response data length: " + dataLength);
            }
            byte[] data = dataLength == 0 ? CoreResponse.EMPTY_DATA : new byte[dataLength];
            if (dataLength > 0) {
                buffer.getBytes(payloadOffset + CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH, data);
            }
            responses.put(correlationId, CoreResponse.decoded(status, commandStatus, resultCode,
                    routeVersion, committedCoreSequence, appliedCommandCount, data));
        } catch (RuntimeException exception) {
            sessionFailure = new IllegalStateException("failed to decode Aeron Cluster egress response", exception);
        }
    }

    @Override
    public void onSessionEvent(
            long correlationId,
            long clusterSessionId,
            long leadershipTermId,
            int leaderMemberId,
            EventCode code,
            String detail) {
        if (code != EventCode.OK) {
            sessionFailure = new IllegalStateException("Aeron session event " + code + ": " + detail);
        }
    }

    @Override
    public void onNewLeader(
            long clusterSessionId,
            long leadershipTermId,
            int leaderMemberId,
            String ingressEndpoints) {
        sessionFailure = null;
    }

    @Override
    public void close() {
        if (ioOwner != null) { requireIoOwner(); closeCluster(); return; }
        clientLock.lock();
        try {
            closeCluster();
        } finally {
            clientLock.unlock();
        }
    }

    private void closeCluster() {
        if (closed) return;
        closed = true;
        try {
            if (keepAliveExecutor != null) {
                keepAliveExecutor.shutdownNow();
            }
            cluster.close();
        } finally {
            if (closeMediaDriver) {
                mediaDriver.close();
            }
        }
    }

    private ScheduledExecutorService startKeepAlive() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "surprising-aeron-client-keepalive-" + productLine.topicSegment());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                keepAlive();
            } catch (RuntimeException ignored) {
            }
        }, 1, 1, TimeUnit.SECONDS);
        return executor;
    }

    private void pollAndCheckSession() {
        pollEgress(AERON_EGRESS_POLL_LIMIT);
        if (sessionFailure != null) {
            throw sessionFailure;
        }
    }

    private ResultUnknownException resultUnknown(CoreMessage message, String detail) {
        return new ResultUnknownException(message.header().commandId(), detail
                + "; retry or query with the same commandId=" + message.header().commandId());
    }

    private void requireIoOwner() {
        if (Thread.currentThread() != ioOwner)
            throw new IllegalStateException("pooled Aeron client I/O belongs to its dispatcher");
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Aeron client is closed");
    }

    interface ClusterOperations {
        long offer(DirectBuffer buffer, int offset, int length);

        int pollEgress();

        boolean sendKeepAlive();

        void close();
    }

    private record AeronClusterOperations(AeronCluster cluster) implements ClusterOperations {
        @Override
        public long offer(DirectBuffer buffer, int offset, int length) {
            return cluster.offer(buffer, offset, length);
        }

        @Override
        public int pollEgress() {
            return cluster.pollEgress();
        }

        @Override
        public boolean sendKeepAlive() {
            return cluster.sendKeepAlive();
        }

        @Override
        public void close() {
            cluster.close();
        }
    }

    static final class AsyncConnection implements AeronClientPool.Session, EgressListener {

        private final ProductLine productLine;
        private final Duration responseTimeout;
        private final MediaDriver mediaDriver;
        private final AeronCluster.AsyncConnect connection;
        private final boolean closeMediaDriver;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile SurprisingAeronClient client;

        private AsyncConnection(
                ProductLine productLine,
                List<String> hostnames,
                String egressHostname,
                Duration responseTimeout,
                MediaDriver mediaDriver) {
            this(productLine, hostnames, egressHostname, responseTimeout, mediaDriver, false);
        }

        private AsyncConnection(
                ProductLine productLine,
                List<String> hostnames,
                String egressHostname,
                Duration responseTimeout,
                MediaDriver mediaDriver,
                boolean closeMediaDriver) {
            this.productLine = Objects.requireNonNull(productLine, "productLine");
            this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
            this.mediaDriver = Objects.requireNonNull(mediaDriver, "mediaDriver");
            this.closeMediaDriver = closeMediaDriver;
            // Pool connection setup includes driver buffer allocation and the cluster handshake.
            // Do not recreate those resources at every short business-response deadline.
            this.connection = AeronCluster.asyncConnect(clusterContext(productLine, hostnames, egressHostname,
                    pooledConnectionTimeout(), mediaDriver, this));
        }

        SurprisingAeronClient poll() {
            SurprisingAeronClient current = client;
            if (current != null) {
                return current;
            }
            AeronCluster connected = connection.poll();
            if (connected == null) {
                return null;
            }
            current = new SurprisingAeronClient(productLine, responseTimeout, mediaDriver,
                    closeMediaDriver, connected, closeMediaDriver ? null : Thread.currentThread());
            client = current;
            return current;
        }

        @Override
        public long offer(CoreMessage message) {
            SurprisingAeronClient current = poll();
            return current == null ? Publication.NOT_CONNECTED : current.offer(message);
        }

        @Override
        public int pollEgress(int fragmentLimit) {
            SurprisingAeronClient current = poll();
            return current == null ? 0 : current.pollEgress(fragmentLimit);
        }

        @Override
        public CoreResponse takeResponse(long correlationId) {
            SurprisingAeronClient current = client;
            return current == null ? null : current.takeResponse(correlationId);
        }

        @Override
        public int drainResponses(java.util.function.BiConsumer<Long, CoreResponse> consumer) {
            SurprisingAeronClient current = client;
            return current == null ? 0 : current.drainResponses(consumer);
        }

        @Override
        public RuntimeException sessionFailure() {
            SurprisingAeronClient current = client;
            return current == null ? null : current.sessionFailure();
        }

        @Override
        public boolean connected() {
            return client != null;
        }

        @Override
        public boolean keepAlive() {
            SurprisingAeronClient current = poll();
            return current != null && current.keepAlive();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            SurprisingAeronClient current = client;
            try {
                if (current == null) {
                    connection.close();
                } else {
                    current.close();
                }
            } finally {
                if (closeMediaDriver) mediaDriver.close();
            }
        }

        @Override
        public void onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer, int offset, int length,
                              Header header) {
            SurprisingAeronClient current = client;
            if (current != null) {
                current.onMessage(clusterSessionId, timestamp, buffer, offset, length, header);
            }
        }

        @Override
        public void onSessionEvent(long correlationId, long clusterSessionId, long leadershipTermId,
                                   int leaderMemberId, EventCode code, String detail) {
            SurprisingAeronClient current = client;
            if (current != null) {
                current.onSessionEvent(correlationId, clusterSessionId, leadershipTermId, leaderMemberId,
                        code, detail);
            }
        }

        @Override
        public void onNewLeader(long clusterSessionId, long leadershipTermId, int leaderMemberId,
                                String ingressEndpoints) {
            SurprisingAeronClient current = client;
            if (current != null) {
                current.onNewLeader(clusterSessionId, leadershipTermId, leaderMemberId, ingressEndpoints);
            }
        }
    }
}
