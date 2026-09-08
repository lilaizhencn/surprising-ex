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
public final class RealtimeRouter implements AutoCloseable {
    private final ArrayBlockingQueue<RealtimeFrame> inbound = new ArrayBlockingQueue<>(8192);
    private final RealtimeRouterProperties config;
    private final ValkeyRouteDirectory routes;
    private final ValkeyReadViewStore views;
    private final ValkeySnapshotRequests requests;
    private final SnapshotAssembler snapshots = new SnapshotAssembler();
    private final SnapshotAssembler commits = new SnapshotAssembler();
    private final java.util.concurrent.atomic.AtomicLong queuedBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<ProductLine, ExclusivePublication> controls =
            new EnumMap<>(ProductLine.class);
    private final Map<ProductLine, Long> requestOffsets = new EnumMap<>(ProductLine.class);
    private final Map<ProductLine, Long> bookOffsets = new EnumMap<>(ProductLine.class);
    private final Map<ProductLine, String> epochs = new EnumMap<>(ProductLine.class);
    private final Set<ProductLine> dirtySources = EnumSet.noneOf(ProductLine.class);
    private final Map<ProductLine, Long> prefixes = new EnumMap<>(ProductLine.class);
    private final Map<String, Request> outstanding = new HashMap<>();
    private final LongAdder dropped = new LongAdder(), failures = new LongAdder();
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
        metrics.gauge("realtime.router.failures", this, RealtimeRouter::failures);
        metrics.gauge("realtime.router.queued.bytes", queuedBytes);
        metrics.gauge("realtime.router.queued.frames", inbound, java.util.Collection::size);
        metrics.gauge("realtime.receiver.failures", receiver, AeronRealtimeReceiver::failures);
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
                        f -> {
                            long bytes = f.payloadLength() + 448;
                            if (queuedBytes.addAndGet(bytes) > 16 * 1024 * 1024
                                    || !inbound.offer(f)) {
                                queuedBytes.addAndGet(-bytes);
                                dropped.increment();
                            }
                        });
        worker = Thread.ofPlatform().name("realtime-router").start(this::run);
    }

    private void run() {
        while (running) {
            try (var aeron =
                    Aeron.connect(
                            new Aeron.Context()
                                    .aeronDirectoryName(config.directory())
                                    .driverTimeoutMs(1000)
                                    .errorHandler(failure -> failures.increment()))) {
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
                while (running && !aeron.isClosed()) {
                    long now = System.currentTimeMillis();
                    if (now >= nextRefresh) {
                        refresh(aeron, now);
                        nextRefresh = now + 200;
                    }
                    RealtimeFrame frame = inbound.poll(10, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        queuedBytes.addAndGet(-frame.payloadLength() - 448);
                        try {
                            route(aeron, frame, now);
                        } catch (RuntimeException failure) {
                            dirtySources.add(frame.productLine());
                            failures.increment();
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                failures.increment();
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000_000L);
            } finally {
                nodes.values().forEach(n -> n.publication.close());
                nodes.clear();
                controls.values().forEach(ExclusivePublication::close);
                controls.clear();
            }
        }
    }

    private void route(Aeron aeron, RealtimeFrame f, long now) {
        if (dirtySources.remove(f.productLine())) resetSource(f.productLine());
        if (f.snapshotId() != 0) {
            String key = f.productLine() + ":" + f.userId();
            Request requested = outstanding.get(key);
            if (requested == null || requested.id != f.snapshotId() || now - requested.at > 5000)
                return;
            if (f.kind() == RealtimeFrame.Kind.SNAPSHOT_UNAVAILABLE) {
                views.unavailable(f.productLine(), f.userId());
                send(aeron, f, now);
                outstanding.remove(key);
                return;
            }
            List<RealtimeFrame> complete = snapshots.accept(f, now);
            if (!complete.isEmpty()) {
                if (views.install(complete, now, epochs.getOrDefault(f.productLine(), "0")))
                    for (var part : complete) send(aeron, part, now);
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
                if (last == null || previous != last) resetSource(f.productLine());
                prefixes.put(f.productLine(), end);
            }
            var users = new HashMap<Long, List<RealtimeFrame>>();
            for (var event : batch)
                if (event.userId() > 0 && event.kind() != RealtimeFrame.Kind.EXECUTION)
                    users.computeIfAbsent(event.userId(), u -> new ArrayList<>()).add(event);
            for (var events : users.values())
                views.applyBatch(events, ValkeyReadViewStore.exportSequence(batch.getLast()));
            for (var event : batch)
                if (event.kind() != RealtimeFrame.Kind.COMMIT_BEGIN
                        && event.kind() != RealtimeFrame.Kind.COMMIT_END) send(aeron, event, now);
        } else send(aeron, f, now);
    }

    private void send(Aeron aeron, RealtimeFrame f, long now) {
        byte[] bytes = RealtimeFrameCodec.encode(f);
        for (var entry : routes.targets(RealtimeRoute.of(f), now).entrySet()) {
            Node node = nodes.get(entry.getKey());
            if (node == null) {
                if (nodes.size() >= 64) {
                    dropped.increment();
                    continue;
                }
                node =
                        new Node(
                                aeron.addExclusivePublication(
                                        new ChannelUriStringBuilder(entry.getValue())
                                                .termLength(8 * 1024 * 1024)
                                                .build(),
                                        config.nodeStream()),
                                now);
                nodes.put(entry.getKey(), node);
            }
            node.lastUsed = now;
            long result = node.publication.offer(new UnsafeBuffer(bytes));
            if (result < 0) dropped.increment();
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                node.publication.close();
                nodes.remove(entry.getKey());
            }
        }
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
                            n.publication.close();
                            return true;
                        });
        outstanding.values().removeIf(r -> now - r.at > 5000);
        for (var entry : controls.entrySet()) {
            long bookOffset = bookOffsets.getOrDefault(entry.getKey(), 0L);
            var symbols = requests.books(entry.getKey(), now, bookOffset, 4);
            bookOffsets.put(entry.getKey(), symbols.size() < 4 ? 0L : bookOffset + 4);
            for (String symbol : symbols) {
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
                                symbol,
                                "",
                                new byte[0]);
                entry.getValue().offer(new UnsafeBuffer(RealtimeFrameCodec.encode(book)));
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
        return dropped.sum();
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
        long lastUsed;

        Node(ExclusivePublication p, long now) {
            publication = p;
            lastUsed = now;
        }
    }

    private record Request(long id, long at) {}
}
