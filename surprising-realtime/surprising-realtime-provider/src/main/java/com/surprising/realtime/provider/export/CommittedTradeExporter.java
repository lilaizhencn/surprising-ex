package com.surprising.realtime.provider.export;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.orchestration.CommittedTradeReplay;
import com.surprising.product.api.ProductLine;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.service.ClusterCounters;

import org.apache.kafka.clients.producer.*;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Single-threaded committed Archive replay; all replay state belongs to the calling export worker. */
@Slf4j
final class CommittedTradeExporter {
    private final TradeExportProperties config;
    private final ProductLine product;
    private final String bootstrapServers;
    private final CommittedOrderProjectionRepository orders;

    CommittedTradeExporter(TradeExportProperties config, ProductLine product, String bootstrapServers,
            CommittedOrderProjectionRepository orders) {
        this.config = config;
        this.product = product;
        this.bootstrapServers = bootstrapServers;
        this.orders = orders;
    }

    void run(java.util.concurrent.atomic.AtomicBoolean running, java.util.function.Consumer<Boolean> connected, long stopAfter) throws Exception {
        run(running, connected, stopAfter, false);
    }

    /** Offline projection repair: separate checkpoint, finite boundary, no Kafka or order writes. */
    void repairFunding(long stopAfter) throws Exception {
        if (!product.isFundingProduct() || stopAfter <= 0 || stopAfter == Long.MAX_VALUE
                || !config.checkpoint().getFileName().toString().endsWith(".funding-repair"))
            throw new IllegalArgumentException("funding repair requires a perpetual product, finite end and separate .funding-repair checkpoint");
        run(new java.util.concurrent.atomic.AtomicBoolean(true), ready -> {}, stopAfter, true);
    }

    private void run(java.util.concurrent.atomic.AtomicBoolean running,
            java.util.function.Consumer<Boolean> connected, long stopAfter, boolean fundingOnly) throws Exception {
        Path checkpointPath = config.checkpoint().toAbsolutePath();
        Files.createDirectories(checkpointPath.getParent());
        int clusterId = config.clusterId() == null
                ? com.surprising.aeron.protocol.ProductLineClusterLayout.clusterId(product) : config.clusterId();
        Properties props = new Properties();
        if (!fundingOnly) props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(
                ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                "surprising-committed-trades-" + product.name());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "2");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        // Optional Kafka security settings are loaded from a file, never printed.
        String kafkaConfig = config.kafkaConfig();
        if (kafkaConfig != null && !kafkaConfig.isBlank())
            try (var input = Files.newInputStream(Path.of(kafkaConfig))) {
                props.load(input);
            }
        props.put(
                ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                "surprising-committed-trades-" + product.name());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        long checkpointInterval = config.checkpointInterval().toNanos();
        try (var lockChannel =
                        FileChannel.open(
                                checkpointPath.resolveSibling(
                                        checkpointPath.getFileName() + ".lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = lockChannel.tryLock()) {
            if (lock == null)
                throw new IllegalStateException("trade exporter checkpoint is already in use");
            TradeExportCheckpoint checkpoint = TradeExportCheckpoint.read(checkpointPath, product);
            try (var replay = new CommittedTradeReplay(product, checkpoint.snapshot());
                    var sink =
                            fundingOnly ? null : new ReliableTradeKafkaSink(
                                    new KafkaProducer<String, String>(props),
                                    product,
                                    checkpoint.tradeSequence());
                    var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(config.aeronDirectory()));
                    var archive =
                            AeronArchive.connect(
                                    new AeronArchive.Context()
                                            .aeron(aeron)
                                            .ownsAeronClient(false)
                                            .controlRequestChannel(config.archiveControlChannel())
                                            .controlResponseChannel("aeron:ipc"));
                    var recordingLog = new RecordingLog(config.clusterDirectory().toFile(), false);
                    var archiveReplay = new ArchiveReplay(aeron, archive, product)) {
                long cursor = checkpoint.logPosition();
                long[] nextCheckpoint = {System.nanoTime() + checkpointInterval};
                var batch = new ExportBatch(product, orders, sink);
                long[] delivered = {checkpoint.logPosition()};
                boolean[] partialMessage = {false};
                var messageHeader = new MessageHeaderDecoder();
                var sessionHeader = new SessionMessageHeaderDecoder();
                var assembler = new FragmentAssembler((buffer, offset, length, header) -> {
                    messageHeader.wrap(buffer, offset);
                    if (messageHeader.templateId() == SessionMessageHeaderDecoder.TEMPLATE_ID) {
                        sessionHeader.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                                messageHeader.blockLength(), messageHeader.version());
                        int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH + messageHeader.blockLength();
                        int bodyLength = length - (bodyOffset - offset);
                        if (bodyLength <= 0) throw new IllegalArgumentException("empty archived Core command");
                        byte[] bytes = new byte[bodyLength];
                        buffer.getBytes(bodyOffset, bytes);
                        var changes = replay.apply(bytes, sessionHeader.timestamp(), header.position());
                        if (replay.fundingPage() != null) {
                            // Flush earlier commands, then atomically persist this exact funding
                            // page. A SQL failure prevents checkpoint advancement and is retried
                            // from the old committed replay state; SQL identity makes it idempotent.
                            batch.flush(replay.exportSequence());
                            orders.persistFunding(replay.fundingPage());
                        }
                        batch.add(changes,
                                replay.exportSequence(), System.nanoTime());
                    }
                    delivered[0] = header.position();
                    if (System.nanoTime() >= nextCheckpoint[0]) {
                        batch.flush(replay.exportSequence());
                        try {
                            new TradeExportCheckpoint(product, delivered[0], (sink == null ? checkpoint.tradeSequence() : sink.tradeSequence()), replay.snapshot())
                                    .write(checkpointPath);
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                        nextCheckpoint[0] = System.nanoTime() + checkpointInterval;
                    }
                });
                exportLoop: while (running.get()
                        && !Thread.currentThread().isInterrupted()
                        && cursor < stopAfter) {
                    batch.flushIfDue(replay.exportSequence(), System.nanoTime());
                    int counter = ClusterCounters.find(aeron.countersReader(),
                            AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID, clusterId);
                    if (counter < 0) {
                        connected.accept(false);
                        LockSupport.parkNanos(batch.waitNanos(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(100)));
                        continue;
                    }
                    connected.accept(true);
                    archive.checkForErrorResponse();
                    long committed = Math.min(aeron.countersReader().getCounterValue(counter), stopAfter);
                    // The image may hold part of a fragmented command. Its position controls
                    // polling; cursor remains the last complete, durable command boundary.
                    long position = archiveReplay.position(cursor);
                    if (committed <= position) {
                        if (System.nanoTime() >= nextCheckpoint[0]) {
                            batch.flush(replay.exportSequence());
                            new TradeExportCheckpoint(product, cursor, (sink == null ? checkpoint.tradeSequence() : sink.tradeSequence()), replay.snapshot())
                                    .write(checkpointPath);
                            nextCheckpoint[0] = System.nanoTime() + checkpointInterval;
                        }
                        LockSupport.parkNanos(batch.waitNanos(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(10)));
                        continue;
                    }
                    recordingLog.reload();
                    RecordingLog.Entry term = null;
                    long safeEnd = committed;
                    // Keep the term mapping fresh on every batch: a new leader may replace
                    // an overlapping tail. Select in one pass without sorting/copying history.
                    for (var entry : recordingLog.entries()) {
                        if (!entry.isValid || entry.type != RecordingLog.ENTRY_TYPE_TERM) continue;
                        if (entry.termBaseLogPosition > position) {
                            safeEnd = Math.min(safeEnd, entry.termBaseLogPosition);
                        } else if (term == null || entry.termBaseLogPosition > term.termBaseLogPosition
                                || (entry.termBaseLogPosition == term.termBaseLogPosition
                                    && entry.leadershipTermId > term.leadershipTermId)) {
                            term = entry;
                        }
                    }
                    if (term == null) throw new IllegalStateException(
                            "archive history before the export checkpoint is unavailable");
                    safeEnd = archiveReplay.committedEnd(term.recordingId, term.leadershipTermId,
                            position, safeEnd, System.nanoTime());
                    if (archiveReplay.recordingId != term.recordingId) {
                        if (partialMessage[0])
                            throw new IllegalStateException("fragmented command crosses archive recordings");
                        assembler.clear();
                    }
                    archiveReplay.follow(term.recordingId, position, safeEnd);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (archiveReplay.subscription.imageCount() == 0) {
                        if (!running.get() || Thread.currentThread().isInterrupted()) break exportLoop;
                        if (System.nanoTime() > deadline)
                            throw new IllegalStateException("archive replay image unavailable");
                        LockSupport.parkNanos(100_000);
                    }
                    Image image = archiveReplay.subscription.imageAtIndex(0);
                    // Aeron catches callback exceptions and can advance the image. Stop on the
                    // export thread before applying or checkpointing any subsequent message.
                    RuntimeException[] replayFailure = {null};
                    while (running.get() && !Thread.currentThread().isInterrupted()
                            && image.position() < safeEnd && !image.isClosed()) {
                        int work = image.boundedPoll((buffer, offset, length, header) -> {
                            if (replayFailure[0] != null) return;
                            try {
                                assembler.onFragment(buffer, offset, length, header);
                            } catch (RuntimeException failure) {
                                replayFailure[0] = failure;
                                return;
                            }
                            partialMessage[0] = (header.flags()
                                    & io.aeron.protocol.DataHeaderFlyweight.END_FLAG) == 0;
                        }, safeEnd, 16);
                        if (replayFailure[0] != null) throw replayFailure[0];
                        if (work == 0) {
                            if (System.nanoTime() > deadline)
                                throw new IllegalStateException("archive replay stalled");
                            LockSupport.parkNanos(100_000);
                        } else deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    }
                    if (running.get() && !Thread.currentThread().isInterrupted()
                            && image.position() != safeEnd)
                        throw new IllegalStateException("incomplete committed archive replay");
                    batch.flushIfDue(replay.exportSequence(), System.nanoTime());
                    cursor = partialMessage[0] || image.position() != safeEnd ? delivered[0] : safeEnd;
                    if (System.nanoTime() >= nextCheckpoint[0]) {
                        batch.flush(replay.exportSequence());
                        new TradeExportCheckpoint(product, cursor, (sink == null ? checkpoint.tradeSequence() : sink.tradeSequence()), replay.snapshot())
                                .write(checkpointPath);
                        log.info("trade-export product={} committedPosition={} exportedPosition={} trades={}",
                                product, committed, cursor, sink == null ? "funding-only" : sink.tradeSequence());
                        nextCheckpoint[0] = System.nanoTime() + checkpointInterval;
                    }
                }
                batch.flush(replay.exportSequence());
                new TradeExportCheckpoint(product, cursor, (sink == null ? checkpoint.tradeSequence() : sink.tradeSequence()), replay.snapshot())
                        .write(checkpointPath);
            }
        }
    }

    /** Export-worker-owned batch: bounded by commands/frames and a 5ms delivery deadline. */
    static final class ExportBatch {
        private static final long MAX_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
        private final ProductLine product;
        private final CommittedOrderProjectionRepository orders;
        private final ReliableTradeKafkaSink sink;
        private final ArrayList<RealtimeFrame> trades = new ArrayList<>();
        private final ArrayList<RealtimeFrame> orderChanges = new ArrayList<>();
        private int messages;
        private long startedAt, flushedSequence = -1;

        ExportBatch(ProductLine product, CommittedOrderProjectionRepository orders, ReliableTradeKafkaSink sink) {
            this.product = product;
            this.orders = orders;
            this.sink = sink;
        }

        void add(List<RealtimeFrame> changes, long sequence, long now) {
            if (sink == null) return; // Funding repair never republishes orders or trades.
            if (messages++ == 0) startedAt = now;
            for (var change : changes) {
                if (change.kind() == RealtimeFrame.Kind.TRADE) trades.add(change);
                else orderChanges.add(change);
            }
            if (messages >= 256 || trades.size() + orderChanges.size() >= 4096
                    || now - startedAt >= MAX_DELAY_NANOS) flush(sequence);
        }

        void flushIfDue(long sequence, long now) {
            if (messages > 0 && now - startedAt >= MAX_DELAY_NANOS) flush(sequence);
        }

        long waitNanos(long now, long maxWaitNanos) {
            return messages == 0 ? maxWaitNanos
                    : Math.max(1, Math.min(maxWaitNanos, MAX_DELAY_NANOS - (now - startedAt)));
        }

        void flush(long sequence) {
            if (sink == null) return;
            if (sequence != flushedSequence || !orderChanges.isEmpty() || !trades.isEmpty()) {
                orders.persist(product, orderChanges, sequence);
                sink.publish(trades);
                // Neither the checkpoint nor this marker advances when either sink fails.
                flushedSequence = sequence;
            }
            orderChanges.clear();
            trades.clear();
            messages = 0;
        }
    }

    /** Owns one bounded replay and its limit counter for the lifetime of this export worker. */
    static final class ArchiveReplay implements AutoCloseable {
        private static final int STREAM_ID = 22001;
        private static final long BOUNDS_REFRESH_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
        private final Aeron aeron;
        private final AeronArchive archive;
        private final Counter limit;
        private long recordingId = Aeron.NULL_VALUE;
        private long sessionId = Aeron.NULL_VALUE;
        private Subscription subscription;
        // Export-worker-owned Archive metadata, revalidated on a new term/recording,
        // every 100ms, and whenever the cached stopped boundary is reached.
        private long boundsRecordingId = Aeron.NULL_VALUE, boundsTermId = Aeron.NULL_VALUE;
        private long recordingStart, recordingStop, boundsCheckedAt;

        long committedEnd(long recordingId, long termId, long position, long committed, long now) {
            if (boundsRecordingId != recordingId || boundsTermId != termId
                    || now - boundsCheckedAt >= BOUNDS_REFRESH_NANOS
                    || (recordingStop >= 0 && position >= recordingStop)) {
                long start = archive.getStartPosition(recordingId);
                long stop = archive.getStopPosition(recordingId);
                recordingStart = start;
                recordingStop = stop;
                boundsRecordingId = recordingId;
                boundsTermId = termId;
                boundsCheckedAt = now;
            }
            long end = recordingStop >= 0 ? Math.min(committed, recordingStop) : committed;
            if (recordingStart < 0 || recordingStart > position || end <= position)
                throw new IllegalStateException("archive gap at trade export checkpoint " + position);
            return end;
        }

        ArchiveReplay(Aeron aeron, AeronArchive archive, ProductLine product) {
            this.aeron = aeron;
            this.archive = archive;
            this.limit = aeron.addCounter(0, "trade-export replay limit " + product);
        }

        long position(long initialPosition) {
            return subscription == null || subscription.imageCount() == 0
                    ? initialPosition : subscription.imageAtIndex(0).position();
        }

        void follow(long recordingId, long position, long committed) {
            if (this.recordingId == recordingId) {
                limit.set(committed);
                return;
            }
            closeReplay();
            limit.set(committed);
            sessionId = archive.startBoundedReplay(recordingId, position, AeronArchive.NULL_LENGTH,
                    limit.id(), "aeron:ipc", STREAM_ID);
            subscription = aeron.addSubscription("aeron:ipc?session-id=" + (int) sessionId, STREAM_ID);
            this.recordingId = recordingId;
        }

        private void closeReplay() {
            try {
                if (sessionId != Aeron.NULL_VALUE) {
                    try {
                        archive.stopReplay(sessionId);
                    } catch (io.aeron.archive.client.ArchiveException failure) {
                        // A stopped recording can finish its replay before we switch recordings.
                        if (failure.errorCode() != io.aeron.archive.client.ArchiveException.UNKNOWN_REPLAY)
                            throw failure;
                    }
                }
            } finally {
                sessionId = Aeron.NULL_VALUE;
                recordingId = Aeron.NULL_VALUE;
                if (subscription != null) {
                    subscription.close();
                    subscription = null;
                }
            }
        }

        @Override
        public void close() {
            try {
                closeReplay();
            } finally {
                limit.close();
            }
        }
    }
}
