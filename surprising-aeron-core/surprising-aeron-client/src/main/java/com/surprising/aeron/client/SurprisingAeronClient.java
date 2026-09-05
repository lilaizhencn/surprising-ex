package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ProductLineClusterLayout;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.Publication;
import io.aeron.logbuffer.Header;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
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
    private final ScheduledExecutorService keepAliveExecutor;
    private final IdleStrategy idleStrategy = new BackoffIdleStrategy();
    private final Map<Long, CoreResponse> responses = new ConcurrentHashMap<>();
    private UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[CoreProtocol.HEADER_LENGTH]);
    private volatile RuntimeException sessionFailure;
    private boolean closed;

    private SurprisingAeronClient(
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
        try {
            cluster = new AeronClusterOperations(AeronCluster.connect(clusterContext(productLine, hostnames,
                    egressHostname, responseTimeout, mediaDriver, this)));
            keepAliveExecutor = closeMediaDriver ? startKeepAlive() : null;
        } catch (RuntimeException exception) {
            if (closeMediaDriver) {
                mediaDriver.close();
            }
            throw exception;
        }
    }

    private SurprisingAeronClient(
            ProductLine productLine,
            Duration responseTimeout,
            MediaDriver mediaDriver,
            boolean closeMediaDriver,
            AeronCluster cluster) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        this.mediaDriver = Objects.requireNonNull(mediaDriver, "mediaDriver");
        this.closeMediaDriver = closeMediaDriver;
        this.cluster = new AeronClusterOperations(Objects.requireNonNull(cluster, "cluster"));
        this.keepAliveExecutor = closeMediaDriver ? startKeepAlive() : null;
    }

    SurprisingAeronClient(
            ProductLine productLine,
            Duration responseTimeout,
            ClusterOperations cluster) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        this.mediaDriver = null;
        this.closeMediaDriver = false;
        this.cluster = Objects.requireNonNull(cluster, "cluster");
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
        FutureTask<SurprisingAeronClient> connection = new FutureTask<>(
                () -> new SurprisingAeronClient(productLine, hostnames, egressHostname,
                        responseTimeout, mediaDriver, true));
        Thread connector = new Thread(connection, "surprising-aeron-connect-" + productLine.topicSegment());
        connector.setDaemon(true);
        connector.start();
        try {
            return connection.get(responseTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            closeMediaDriverBounded(mediaDriver);
            connection.cancel(true);
            throw new io.aeron.exceptions.TimeoutException(
                    "timed out connecting to Aeron Cluster productLine=" + productLine);
        } catch (InterruptedException exception) {
            closeMediaDriverBounded(mediaDriver);
            connection.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted connecting to Aeron Cluster", exception);
        } catch (ExecutionException exception) {
            closeMediaDriverBounded(mediaDriver);
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("failed connecting to Aeron Cluster", cause);
        } catch (RuntimeException exception) {
            closeMediaDriverBounded(mediaDriver);
            throw exception;
        }
    }

    private static void closeMediaDriverBounded(MediaDriver mediaDriver) {
        Thread closer = new Thread(mediaDriver::close, "surprising-aeron-media-driver-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(250L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    static SurprisingAeronClient connect(
            ProductLine productLine,
            List<String> hostnames,
            String egressHostname,
            Duration responseTimeout,
            MediaDriver mediaDriver) {
        if (responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
        return new SurprisingAeronClient(productLine, hostnames, egressHostname, responseTimeout,
                mediaDriver, false);
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

    static MediaDriver newMediaDriver(String aeronDirectoryName) {
        MediaDriver.Context context = new MediaDriver.Context()
                .threadingMode(clientThreadingMode())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);
        if (aeronDirectoryName != null && !aeronDirectoryName.isBlank()) {
            context.aeronDirectoryName(aeronDirectoryName);
        }
        return MediaDriver.launchEmbedded(context);
    }

    static ThreadingMode clientThreadingMode() {
        String configured = System.getProperty("surprising.aeron.client.threading-mode");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("AERON_CLIENT_THREADING_MODE", "DEDICATED");
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
    public synchronized long offer(CoreMessage message) {
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
    public synchronized int pollEgress(int fragmentLimit) {
        ensureOpen();
        return pollEgressBounded(fragmentLimit, cluster::pollEgress);
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
    public RuntimeException sessionFailure() {
        return sessionFailure;
    }

    @Override
    public synchronized boolean keepAlive() {
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
            byte[] encoded = new byte[length];
            buffer.getBytes(offset, encoded);
            CoreMessage message = CoreMessageCodec.decode(encoded);
            if (message.header().productLine() != productLine) {
                sessionFailure = new IllegalStateException("received response from another product line");
                return;
            }
            if (message.header().correlationId() < 0) {
                return;
            }
            // The decoded message is private to this callback; response decoding establishes
            // its own ownership before the Aeron fragment or this message can be released.
            responses.put(message.header().correlationId(), CoreProtocol.decodeResponse(message.payloadUnsafe()));
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
    public synchronized void close() {
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

    private synchronized void pollAndCheckSession() {
        ensureOpen();
        cluster.pollEgress();
        if (sessionFailure != null) {
            throw sessionFailure;
        }
    }

    private ResultUnknownException resultUnknown(CoreMessage message, String detail) {
        return new ResultUnknownException(message.header().commandId(), detail
                + "; retry or query with the same commandId=" + message.header().commandId());
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
            this.connection = AeronCluster.asyncConnect(clusterContext(productLine, hostnames, egressHostname,
                    responseTimeout, mediaDriver, this));
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
                    closeMediaDriver, connected);
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
            if (current == null) {
                connection.close();
            } else {
                current.close();
            }
            if (closeMediaDriver) {
                mediaDriver.close();
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
