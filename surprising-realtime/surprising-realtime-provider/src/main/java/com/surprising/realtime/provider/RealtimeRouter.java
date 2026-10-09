package com.surprising.realtime.provider;

import com.surprising.aeron.client.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.*;

import io.aeron.*;

import jakarta.annotation.PreDestroy;

import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** All Valkey and per-node publication work is isolated from the trading process. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "surprising.realtime.router.enabled", havingValue = "true", matchIfMissing = true)
public final class RealtimeRouter implements AutoCloseable {
    private static final org.slf4j.Logger traceLog = org.slf4j.LoggerFactory.getLogger(RealtimeRouter.class);
    private static final System.Logger LOG = System.getLogger(RealtimeRouter.class.getName());
    private final ArrayBlockingQueue<RealtimeFrame> inbound = new ArrayBlockingQueue<>(8192);
    private final RealtimeRouterProperties config;
    private final ValkeyRouteDirectory routes;
    private final ValkeyReadViewStore views;
    private final ValkeySnapshotRequests requests;
    private final SnapshotAssembler snapshots = new SnapshotAssembler();
    private final SnapshotAssembler commits = new SnapshotAssembler();
    private final java.util.concurrent.atomic.AtomicLong queuedBytes =
            new java.util.concurrent.atomic.AtomicLong();
    // Encoded transport retries belong only to the router thread, never to the trading Core.
    // Global bounds also cover disconnected nodes; successful sends retain no frame copy.
    private final Map<String, Node> nodes = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong pendingBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong pendingFrames = new java.util.concurrent.atomic.AtomicLong();
    private final LongAdder transportExpired = new LongAdder(), transportCapacityDropped = new LongAdder();
    private static final long SEND_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final Map<ProductLine, ExclusivePublication> controls =
            new EnumMap<>(ProductLine.class);
    private final Map<ProductLine, Long> requestOffsets = new EnumMap<>(ProductLine.class);
    private final Map<ProductLine, Long> bookOffsets = new EnumMap<>(ProductLine.class);
    private final Map<ProductLine, String> epochs = new EnumMap<>(ProductLine.class);
    private final Set<ProductLine> dirtySources = EnumSet.noneOf(ProductLine.class);
    private final Map<ProductLine, Long> prefixes = new EnumMap<>(ProductLine.class);
    private final Map<String, Request> outstanding = new HashMap<>();
    // Router-thread-owned query leases, not another book: released by a reply or after transport loss.
    private final Map<String, Long> pendingBooks = new HashMap<>();
    private final LongAdder inputDropped = new LongAdder(), transportDropped = new LongAdder(),
            failures = new LongAdder();
    private final AeronRealtimeReceiver receiver;
    private final Thread worker;
    private volatile boolean running = true;

    @org.springframework.beans.factory.annotation.Autowired
    public RealtimeRouter(
            RealtimeRouterProperties config,
            StringRedisTemplate redis,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        this(config, redis);
        metrics.gauge("realtime.router.dropped", this, RealtimeRouter::dropped);
        metrics.gauge("realtime.router.input.dropped", inputDropped, LongAdder::sum);
        metrics.gauge("realtime.router.transport.dropped", transportDropped, LongAdder::sum);
        metrics.gauge("realtime.router.failures", this, RealtimeRouter::failures);
        metrics.gauge("realtime.router.queued.bytes", queuedBytes);
        metrics.gauge("realtime.router.queued.frames", inbound, java.util.Collection::size);
        metrics.gauge("realtime.receiver.failures", receiver, AeronRealtimeReceiver::failures);
        metrics.gauge("realtime.router.transport.pending.bytes", pendingBytes);
        metrics.gauge("realtime.router.transport.pending.frames", pendingFrames);
        metrics.gauge("realtime.router.transport.expired", transportExpired, LongAdder::sum);
        metrics.gauge("realtime.router.transport.capacity.dropped", transportCapacityDropped, LongAdder::sum);
    }

    public RealtimeRouter(RealtimeRouterProperties config, StringRedisTemplate redis) {
        this.config = config;
        routes = new ValkeyRouteDirectory(redis);
        views = new ValkeyReadViewStore(redis);
        requests = new ValkeySnapshotRequests(redis);
        receiver =
                new AeronRealtimeReceiver(
                        config.directory(),
                        config.channel(),
                        config.stream(),
                        this::offer);
        worker = Thread.ofPlatform().name("realtime-router").start(this::run);
    }

    /** Thread handoff shared by Core frames and in-process candle updates; never blocks the producer. */
    public boolean offer(RealtimeFrame frame) {
        long bytes = frame.payloadLength() + 448;
        if (queuedBytes.addAndGet(bytes) > 16 * 1024 * 1024 || !inbound.offer(frame)) {
            queuedBytes.addAndGet(-bytes);
            inputDropped.increment();
            traceLog.warn("router.input.dropped traceId={} productLine={} kind={} entityId={}",
                    traceId(frame), frame.productLine(), frame.kind(), frame.entityId());
            return false;
        }
        return true;
    }

    private void run() {
        while (running) {
            try (var aeron =
                    Aeron.connect(
                            new Aeron.Context()
                                    .aeronDirectoryName(config.directory())
                                    .errorHandler(failure -> {
                                        failures.increment();
                                        LOG.log(System.Logger.Level.WARNING, "Realtime router Aeron transport error", failure);
                                    }))) {
                config.controlChannels()
                        .forEach(
                                (p, c) -> {
                                    var control = aeron.addExclusivePublication(c, 2102);
                                    controls.put(p, control);
                                    config.controlDestinations()
                                            .getOrDefault(p, List.of())
                                            .forEach(control::addDestination);
                                    resetSource(p);
                                });
                long nextRefresh = 0;
                // Only this router thread owns the drain; release frame references after each batch.
                var frames = new ArrayList<RealtimeFrame>(256);
                while (running && !aeron.isClosed()) {
                    long now = System.currentTimeMillis();
                    if (now >= nextRefresh) {
                        refresh(aeron, now);
                        nextRefresh = now + 100;
                    }
                    retryPending();
                    RealtimeFrame frame = inbound.poll(10, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        frames.add(frame);
                        inbound.drainTo(frames, 255);
                        try { routeBatch(aeron, frames, now); }
                        finally { frames.clear(); }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                failures.increment();
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000_000L);
            } finally {
                nodes.values().forEach(this::closeNode);
                nodes.clear();
                controls.values().forEach(ExclusivePublication::close);
                controls.clear();
                pendingBooks.clear();
            }
        }
    }

    /** Router thread owns this drain and its route lookup; neither survives the batch. */
    private void routeBatch(Aeron aeron, List<RealtimeFrame> frames, long now) {
        try {
            var routeKeys = new HashSet<RealtimeRoute>();
            for (var frame : frames)
                if (frame.kind() != RealtimeFrame.Kind.COMMIT_BEGIN
                        && frame.kind() != RealtimeFrame.Kind.COMMIT_END)
                    routeKeys.add(RealtimeRoute.of(frame));
            var targets = new HashMap<>(routes.targets(routeKeys, now));
            var ready = new ArrayList<List<RealtimeFrame>>();
            for (var frame : frames) {
                try {
                    route(aeron, frame, now, targets, ready);
                } catch (RuntimeException failure) {
                    dirtySources.add(frame.productLine());
                    failures.increment();
                    traceLog.warn("router.failed traceId={} productLine={} kind={} entityId={} error={}",
                            traceId(frame), frame.productLine(), frame.kind(), frame.entityId(), failure.getClass().getSimpleName());
                    LOG.log(System.Logger.Level.WARNING, "Realtime frame routing failed", failure);
                }
            }
            publishReady(aeron, ready, now, targets);
        } catch (RuntimeException failure) {
            frames.forEach(frame -> dirtySources.add(frame.productLine()));
            failures.increment();
            inputDropped.add(frames.size());
            for (var frame : frames) traceLog.warn("router.batch.failed traceId={} productLine={} kind={} error={}",
                    traceId(frame), frame.productLine(), frame.kind(), failure.getClass().getSimpleName());
            LOG.log(System.Logger.Level.WARNING, "Realtime route lookup failed", failure);
        } finally {
            for (var frame : frames) queuedBytes.addAndGet(-frame.payloadLength() - 448);
        }
    }

    private void route(Aeron aeron, RealtimeFrame f, long now,
            Map<RealtimeRoute, Map<String, String>> targets, List<List<RealtimeFrame>> ready) {
        if (f.kind() == RealtimeFrame.Kind.BOOK)
            pendingBooks.remove(f.productLine() + ":" + f.instrumentId());
        if (dirtySources.remove(f.productLine())) {
            publishReady(aeron, ready, now, targets);
            resetSource(f.productLine());
        }
        if (f.snapshotId() != 0) {
            // A recovery snapshot is an ordering fence, never pipelined across ordinary commits.
            publishReady(aeron, ready, now, targets);
            String key = f.productLine() + ":" + f.userId();
            Request requested = outstanding.get(key);
            if (requested == null || requested.id != f.snapshotId() || now - requested.at > 5000)
                return;
            if (f.kind() == RealtimeFrame.Kind.SNAPSHOT_UNAVAILABLE) {
                views.unavailable(f.productLine(), f.userId());
                send(aeron, f, now, targets);
                outstanding.remove(key);
                return;
            }
            List<RealtimeFrame> complete = snapshots.accept(f, now);
            if (!complete.isEmpty()) {
                if (views.install(complete, now, epochs.getOrDefault(f.productLine(), "0")))
                    for (var part : complete) send(aeron, part, now, targets);
                outstanding.remove(key);
            }
            return;
        }
        if (f.kind() == RealtimeFrame.Kind.COMMIT_BEGIN
                || f.kind() == RealtimeFrame.Kind.COMMIT_END
                || f.userId() > 0
                || f.kind() == RealtimeFrame.Kind.TRADE
                || f.kind() == RealtimeFrame.Kind.ORDER
                || f.kind() == RealtimeFrame.Kind.BOOK) {
            var batch = commits.accept(f, now);
            if (batch.isEmpty()) return;
            boolean book = batch.size() == 3 && batch.get(1).kind() == RealtimeFrame.Kind.BOOK;
            if (!book) {
                long previous = ValkeyReadViewStore.exportSequence(batch.getFirst());
                Long last = prefixes.get(f.productLine());
                long end = ValkeyReadViewStore.exportSequence(batch.getLast());
                if (last != null && end <= last) return;
                if (last == null || previous != last) {
                    publishReady(aeron, ready, now, targets);
                    resetSource(f.productLine());
                }
                prefixes.put(f.productLine(), end);
            }
            ready.add(batch);
        } else ready.add(List.of(f));
    }

    private void publishReady(Aeron aeron, List<List<RealtimeFrame>> ready, long now,
            Map<RealtimeRoute, Map<String, String>> targets) {
        if (ready.isEmpty()) return;
        try {
            views.applyCommits(ready);
            for (var batch : ready)
                for (var event : batch) {
                    if (event.kind() == RealtimeFrame.Kind.COMMIT_BEGIN
                            || event.kind() == RealtimeFrame.Kind.COMMIT_END) continue;
                    send(aeron, event, now, targets);
                }
        } catch (RuntimeException failure) {
            ready.forEach(batch -> dirtySources.add(batch.getFirst().productLine()));
            throw failure;
        } finally {
            ready.clear();
        }
    }

    private void send(Aeron aeron, RealtimeFrame f, long now,
            Map<RealtimeRoute, Map<String, String>> targets) {
        // A commit may begin in the preceding drain; resolve an unseen route freshly as needed.
        sendToNodes(aeron, f, now, targets.computeIfAbsent(RealtimeRoute.of(f),
                route -> routes.targets(route, System.currentTimeMillis())));
    }

    private void sendToNodes(Aeron aeron, RealtimeFrame f, long now, Map<String, String> targets) {
        if (targets.isEmpty()) return;
        byte[] bytes = RealtimeFrameCodec.encode(f);
        var buffer = new UnsafeBuffer(bytes);
        for (var entry : targets.entrySet()) {
            Node node = nodes.get(entry.getKey());
            if (node != null && !node.endpoint.equals(entry.getValue())) {
                closeNode(node);
                nodes.remove(entry.getKey());
                node = null;
            }
            if (node == null) {
                if (nodes.size() >= 64) {
                    transportDropped.increment();
                    dirtySources.add(f.productLine());
                    continue;
                }
                node = new Node(aeron.addExclusivePublication(
                        new ChannelUriStringBuilder(entry.getValue()).termLength(8 * 1024 * 1024).build(),
                        config.nodeStream()), entry.getValue(), now);
                nodes.put(entry.getKey(), node);
            }
            node.lastUsed = now;
            // A later update must not overtake an earlier update waiting for this same node.
            if (!node.pending.isEmpty()) {
                enqueue(node, bytes, f);
                continue;
            }
            long result = node.publication.offer(buffer);
            if (result > 0) {
                traceLog.info("router.send.end traceId={} productLine={} kind={} entityId={} node={} result=OFFERED",
                        traceId(f), f.productLine(), f.kind(), f.entityId(), entry.getKey());
                continue;
            }
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                transportDropped.increment();
                dirtySources.add(f.productLine());
                closeNode(node);
                nodes.remove(entry.getKey());
            } else enqueue(node, bytes, f);
        }
    }

    private void enqueue(Node node, byte[] bytes, RealtimeFrame frame) {
        ProductLine product = frame.productLine();
        if (pendingFrames.get() >= 8192 || pendingBytes.get() + bytes.length > 16 * 1024 * 1024) {
            transportDropped.increment();
            transportCapacityDropped.increment();
            traceLog.warn("router.queue.dropped traceId={} productLine={} kind={} entityId={}",
                    traceId(frame), product, frame.kind(), frame.entityId());
            dirtySources.add(product);
            return;
        }
        node.pending.addLast(new PendingFrame(new UnsafeBuffer(bytes), product, System.nanoTime(), traceId(frame)));
        pendingFrames.incrementAndGet();
        pendingBytes.addAndGet(bytes.length);
        traceLog.info("router.queued traceId={} productLine={} kind={} entityId={}",
                traceId(frame), product, frame.kind(), frame.entityId());
    }

    private void retryPending() {
        if (pendingFrames.get() == 0) return;
        long now = System.nanoTime();
        var iterator = nodes.values().iterator();
        while (iterator.hasNext()) {
            Node node = iterator.next();
            // One stalled publication must not monopolize the router or block other nodes.
            for (int i = 0; i < 32 && !node.pending.isEmpty(); i++) {
                PendingFrame frame = node.pending.getFirst();
                if (now - frame.enqueuedAt >= SEND_TIMEOUT_NANOS) {
                    discardFirst(node);
                    transportExpired.increment();
                    continue;
                }
                long result = node.publication.offer(frame.buffer);
                if (result > 0) {
                    traceLog.info("router.send.end traceId={} productLine={} result=OFFERED queueMs={}",
                            frame.traceId, frame.product, (now - frame.enqueuedAt) / 1_000_000.0);
                    releaseFirst(node);
                }
                else if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                    closeNode(node);
                    iterator.remove();
                    break;
                } else break;
            }
        }
    }

    private void releaseFirst(Node node) {
        PendingFrame frame = node.pending.removeFirst();
        pendingBytes.addAndGet(-frame.buffer.capacity());
        pendingFrames.decrementAndGet();
    }

    private void discardFirst(Node node) {
        var frame = node.pending.getFirst();
        dirtySources.add(frame.product);
        traceLog.warn("router.send.end traceId={} productLine={} result=DROPPED queueMs={}",
                frame.traceId, frame.product, (System.nanoTime() - frame.enqueuedAt) / 1_000_000.0);
        transportDropped.increment();
        releaseFirst(node);
    }

    private void closeNode(Node node) {
        while (!node.pending.isEmpty()) discardFirst(node);
        node.publication.close();
    }

    private void resetSource(ProductLine product) {
        String epoch = UUID.randomUUID().toString();
        views.sourceEpoch(product, epoch);
        epochs.put(product, epoch);
        outstanding.keySet().removeIf(k -> k.startsWith(product + ":"));
        prefixes.remove(product);
    }

    private void refresh(Aeron aeron, long now) {
        nodes.values()
                .removeIf(
                        n -> {
                            if (now - n.lastUsed <= 30_000) return false;
                            closeNode(n);
                            return true;
                        });
        outstanding.values().removeIf(r -> now - r.at > 5000);
        pendingBooks.values().removeIf(sentAt -> now - sentAt >= 2000);
        for (var entry : controls.entrySet()) {
            long bookOffset = bookOffsets.getOrDefault(entry.getKey(), 0L);
            // At most 80 book reads/sec, below Core's shared 100 snapshot reads/sec ceiling.
            // Look ahead one instrumentId so exact page multiples do not add an empty refresh cycle.
            var activeBooks = requests.books(entry.getKey(), now, bookOffset, 9);
            var symbols = activeBooks.stream().limit(8).toList();
            bookOffsets.put(entry.getKey(), activeBooks.size() <= 8 ? 0L : bookOffset + 8);
            for (String instrumentId : symbols) {
                String bookKey = entry.getKey() + ":" + instrumentId;
                Long sentAt = pendingBooks.get(bookKey);
                if (sentAt != null && now - sentAt < 2000) continue;
                var book =
                        new RealtimeFrame(
                                entry.getKey(),
                                RealtimeFrame.Kind.BOOK_REQUEST,
                                0,
                                0,
                                0,
                                now,
                                java.util.concurrent.ThreadLocalRandom.current()
                                        .nextLong(1, Long.MAX_VALUE),
                                instrumentId,
                                "",
                                new byte[0]);
                if (entry.getValue().offer(new UnsafeBuffer(RealtimeFrameCodec.encode(book))) > 0)
                    pendingBooks.put(bookKey, now);
            }
            long offset = requestOffsets.getOrDefault(entry.getKey(), 0L);
            Set<String> users = requests.active(entry.getKey(), now, offset, 16);
            requestOffsets.put(entry.getKey(), users.size() < 16 ? 0L : offset + 16);
            for (String user : users) {
                long userId = Long.parseLong(user);
                String key = entry.getKey() + ":" + userId;
                if (outstanding.containsKey(key) || outstanding.size() >= 32) continue;
                if (!requests.claim(entry.getKey(), userId)) continue;
                long id =
                        java.util.concurrent.ThreadLocalRandom.current()
                                .nextLong(1, Long.MAX_VALUE);
                var request =
                        new RealtimeFrame(
                                entry.getKey(),
                                RealtimeFrame.Kind.SNAPSHOT_REQUEST,
                                userId,
                                0,
                                0,
                                now,
                                id,
                                "",
                                "",
                                new byte[0]);
                if (entry.getValue().offer(new UnsafeBuffer(RealtimeFrameCodec.encode(request)))
                        > 0) outstanding.put(key, new Request(id, now));
                else requests.releaseClaim(entry.getKey(), userId);
            }
        }
    }

    public long dropped() {
        return inputDropped.sum() + transportDropped.sum();
    }

    public long failures() {
        return failures.sum();
    }

    @PreDestroy
    @Override
    public void close() {
        running = false;
        receiver.close();
        worker.interrupt();
        try {
            worker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Node {
        final ExclusivePublication publication;
        final String endpoint;
        final ArrayDeque<PendingFrame> pending = new ArrayDeque<>();
        long lastUsed;

        Node(ExclusivePublication p, String endpoint, long now) {
            publication = p;
            this.endpoint = endpoint;
            lastUsed = now;
        }
    }

    private static String traceId(RealtimeFrame frame) {
        return frame.traceId().isEmpty() ? "core-" + frame.productLine() + "-" + frame.sequence() : frame.traceId();
    }

    private record PendingFrame(UnsafeBuffer buffer, ProductLine product, long enqueuedAt, String traceId) {}

    private record Request(long id, long at) {}
}
