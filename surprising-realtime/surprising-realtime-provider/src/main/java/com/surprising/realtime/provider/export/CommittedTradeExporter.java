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

    CommittedTradeExporter(TradeExportProperties config, ProductLine product, String bootstrapServers) {
        this.config = config;
        this.product = product;
        this.bootstrapServers = bootstrapServers;
    }

    void run(java.util.concurrent.atomic.AtomicBoolean running, java.util.function.Consumer<Boolean> connected, long stopAfter) throws Exception {
        Path checkpointPath = config.checkpoint().toAbsolutePath();
        Files.createDirectories(checkpointPath.getParent());
        int clusterId = config.clusterId() == null
                ? com.surprising.aeron.protocol.ProductLineClusterLayout.clusterId(product) : config.clusterId();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
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
                            new ReliableTradeKafkaSink(
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
                    var recordingLog = new RecordingLog(config.clusterDirectory().toFile(), false)) {
                long cursor = checkpoint.logPosition();
                long lastPartialCommit = -1;
                long[] nextCheckpoint = {System.nanoTime() + checkpointInterval};
                exportLoop: while (running.get()
                        && !Thread.currentThread().isInterrupted()
                        && cursor < stopAfter) {
                    int counter =
                            ClusterCounters.find(
                                    aeron.countersReader(),
                                    AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID,
                                    clusterId);
                    if (counter < 0) {
                        connected.accept(false);
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                        continue;
                    }
                    connected.accept(true);
                    long committed =
                            Math.min(aeron.countersReader().getCounterValue(counter), stopAfter);
                    if (committed <= cursor || committed == lastPartialCommit) {
                        if (System.nanoTime() >= nextCheckpoint[0]) {
                            new TradeExportCheckpoint(
                                            product,
                                            cursor,
                                            sink.tradeSequence(),
                                            replay.snapshot())
                                    .write(checkpointPath);
                            nextCheckpoint[0] = System.nanoTime() + checkpointInterval;
                        }
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                        continue;
                    }
                    recordingLog.reload();
                    long start = cursor;
                    var terms =
                            recordingLog.entries().stream()
                                    .filter(
                                            e ->
                                                    e.isValid
                                                            && e.type
                                                                    == RecordingLog.ENTRY_TYPE_TERM)
                                    .sorted(
                                            Comparator.comparingLong(
                                                            (RecordingLog.Entry e) ->
                                                                    e.termBaseLogPosition)
                                                    .thenComparingLong(e -> e.leadershipTermId))
                                    .toList();
                    var term =
                            terms.stream()
                                    .filter(e -> e.termBaseLogPosition <= start)
                                    .reduce((a, b) -> b)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "archive history before the export"
                                                                + " checkpoint is unavailable"));
                    long end =
                            terms.stream()
                                    .mapToLong(e -> e.termBaseLogPosition)
                                    .filter(p -> p > start)
                                    .min()
                                    .orElse(committed);
                    end = Math.min(end, committed);
                    long stopped = archive.getStopPosition(term.recordingId);
                    if (stopped >= 0) end = Math.min(end, stopped);
                    if (archive.getStartPosition(term.recordingId) > cursor || end <= cursor)
                        throw new IllegalStateException(
                                "archive gap at trade export checkpoint " + cursor);
                    long safeEnd = end;
                    var trades = new ArrayList<RealtimeFrame>();
                    int[] messages = {0};
                    long[] delivered = {cursor};
                    boolean[] partialMessage = {false};
                    var messageHeader = new MessageHeaderDecoder();
                    var sessionHeader = new SessionMessageHeaderDecoder();
                    var assembler =
                            new FragmentAssembler(
                                    (buffer, offset, length, header) -> {
                                        messageHeader.wrap(buffer, offset);
                                        if (messageHeader.templateId()
                                                == SessionMessageHeaderDecoder.TEMPLATE_ID) {
                                            sessionHeader.wrap(
                                                    buffer,
                                                    offset + MessageHeaderDecoder.ENCODED_LENGTH,
                                                    messageHeader.blockLength(),
                                                    messageHeader.version());
                                            int bodyOffset =
                                                    offset
                                                            + MessageHeaderDecoder.ENCODED_LENGTH
                                                            + messageHeader.blockLength();
                                            int bodyLength = length - (bodyOffset - offset);
                                            if (bodyLength <= 0)
                                                throw new IllegalArgumentException(
                                                        "empty archived Core command");
                                            byte[] bytes = new byte[bodyLength];
                                            buffer.getBytes(bodyOffset, bytes);
                                            trades.addAll(
                                                    replay.apply(
                                                            bytes,
                                                            sessionHeader.timestamp(),
                                                            header.position()));
                                            messages[0]++;
                                            if (messages[0] >= 256 || trades.size() >= 4096) {
                                                sink.publish(trades);
                                                trades.clear();
                                                messages[0] = 0;
                                            }
                                        }
                                        delivered[0] = header.position();
                                        if (System.nanoTime() >= nextCheckpoint[0]) {
                                            sink.publish(trades);
                                            trades.clear();
                                            messages[0] = 0;
                                            try {
                                                new TradeExportCheckpoint(
                                                                product,
                                                                delivered[0],
                                                                sink.tradeSequence(),
                                                                replay.snapshot())
                                                        .write(checkpointPath);
                                            } catch (java.io.IOException failure) {
                                                throw new java.io.UncheckedIOException(failure);
                                            }
                                            nextCheckpoint[0] =
                                                    System.nanoTime() + checkpointInterval;
                                        }
                                    });
                    try (var subscription =
                            archive.replay(
                                    term.recordingId,
                                    cursor,
                                    safeEnd - cursor,
                                    "aeron:ipc",
                                    22001)) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        while (subscription.imageCount() == 0) {
                            if (!running.get()) break exportLoop;
                            if (System.nanoTime() > deadline)
                                throw new IllegalStateException("archive replay image unavailable");
                            LockSupport.parkNanos(100_000);
                        }
                        Image image = subscription.imageAtIndex(0);
                        while (running.get() && image.position() < safeEnd && !image.isClosed()) {
                            int work =
                                    image.poll(
                                            (buffer, offset, length, header) -> {
                                                assembler.onFragment(
                                                        buffer, offset, length, header);
                                                partialMessage[0] =
                                                        (header.flags()
                                                                        & io.aeron.protocol
                                                                                .DataHeaderFlyweight
                                                                                .END_FLAG)
                                                                == 0;
                                            },
                                            16);
                            if (work == 0) {
                                if (System.nanoTime() > deadline)
                                    throw new IllegalStateException("archive replay stalled");
                                LockSupport.parkNanos(100_000);
                            } else deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        }
                        if (running.get() && image.position() != safeEnd)
                            throw new IllegalStateException("incomplete committed archive replay");
                        sink.publish(trades);
                        // A commit counter can stop at a fragment boundary. Resume from the last
                        // complete Cluster message, never from the middle of a fragmented command.
                        cursor = partialMessage[0] || image.position() != safeEnd ? delivered[0] : safeEnd;
                        lastPartialCommit = partialMessage[0] ? committed : -1;
                        if (partialMessage[0])
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    }
                    if (System.nanoTime() >= nextCheckpoint[0]) {
                        new TradeExportCheckpoint(
                                        product, cursor, sink.tradeSequence(), replay.snapshot())
                                .write(checkpointPath);
                        log.info("trade-export product={} committedPosition={} exportedPosition={} trades={}",
                                product, committed, cursor, sink.tradeSequence());
                        nextCheckpoint[0] = System.nanoTime() + checkpointInterval;
                    }
                }
                new TradeExportCheckpoint(product, cursor, sink.tradeSequence(), replay.snapshot())
                        .write(checkpointPath);
            }
        }
    }
}
