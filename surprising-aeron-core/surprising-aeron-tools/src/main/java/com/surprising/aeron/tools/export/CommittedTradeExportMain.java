package com.surprising.aeron.tools.export;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.execution.CommittedTradeReplay;
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

/** Dedicated process: committed Archive -> deterministic replay -> reliable trade Kafka topic. */
public final class CommittedTradeExportMain {
    private CommittedTradeExportMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 6 || args.length > 7)
            throw new IllegalArgumentException(
                    "usage: CommittedTradeExportMain PRODUCT_LINE CLUSTER_DIR AERON_DIR"
                            + " ARCHIVE_CONTROL_CHANNEL KAFKA_BOOTSTRAP CHECKPOINT [CLUSTER_ID]");
        ProductLine product = ProductLine.requireExternalCode(args[0]);
        Path checkpointPath = Path.of(args[5]).toAbsolutePath();
        Files.createDirectories(checkpointPath.getParent());
        int clusterId = args.length == 7 ? Integer.parseInt(args[6]) : 0;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, args[4]);
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
        // Optional Kafka security settings are loaded from a file, never printed.
        String kafkaConfig = System.getProperty("surprising.trade-export.kafka-config");
        if (kafkaConfig != null)
            try (var input = Files.newInputStream(Path.of(kafkaConfig))) {
                props.load(input);
            }
        props.put(
                ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                "surprising-committed-trades-" + product.name());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        long stopAfter =
                Long.getLong("surprising.trade-export.stop-after-position", Long.MAX_VALUE);
        long checkpointInterval =
                TimeUnit.MILLISECONDS.toNanos(
                        Math.max(
                                1000,
                                Long.getLong("surprising.trade-export.checkpoint-millis", 60000L)));
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread owner = Thread.currentThread();
        Thread hook =
                new Thread(
                        () -> {
                            running.set(false);
                            LockSupport.unpark(owner);
                            try {
                                owner.join(60_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "trade-export-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
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
                    var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(args[2]));
                    var archive =
                            AeronArchive.connect(
                                    new AeronArchive.Context()
                                            .aeron(aeron)
                                            .ownsAeronClient(false)
                                            .controlRequestChannel(args[3])
                                            .controlResponseChannel("aeron:ipc"));
                    var log = new RecordingLog(Path.of(args[1]).toFile(), false)) {
                long cursor = checkpoint.logPosition();
                long lastPartialCommit = -1;
                long[] nextCheckpoint = {System.nanoTime() + checkpointInterval};
                while (running.get()
                        && !Thread.currentThread().isInterrupted()
                        && cursor < stopAfter) {
                    int counter =
                            ClusterCounters.find(
                                    aeron.countersReader(),
                                    AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID,
                                    clusterId);
                    if (counter < 0) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                        continue;
                    }
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
                    log.reload();
                    long start = cursor;
                    var terms =
                            log.entries().stream()
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
                            if (System.nanoTime() > deadline)
                                throw new IllegalStateException("archive replay image unavailable");
                            LockSupport.parkNanos(100_000);
                        }
                        Image image = subscription.imageAtIndex(0);
                        while (image.position() < safeEnd && !image.isClosed()) {
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
                        if (image.position() != safeEnd)
                            throw new IllegalStateException("incomplete committed archive replay");
                        sink.publish(trades);
                        // A commit counter can stop at a fragment boundary. Resume from the last
                        // complete Cluster message, never from the middle of a fragmented command.
                        cursor = partialMessage[0] ? delivered[0] : safeEnd;
                        lastPartialCommit = partialMessage[0] ? committed : -1;
                        if (partialMessage[0])
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    }
                    if (System.nanoTime() >= nextCheckpoint[0]) {
                        new TradeExportCheckpoint(
                                        product, cursor, sink.tradeSequence(), replay.snapshot())
                                .write(checkpointPath);
                        System.out.printf(
                                "trade-export product=%s committedPosition=%d exportedPosition=%d"
                                        + " trades=%d%n",
                                product, committed, cursor, sink.tradeSequence());
                        nextCheckpoint[0] = System.nanoTime() + checkpointInterval;
                    }
                }
                new TradeExportCheckpoint(product, cursor, sink.tradeSequence(), replay.snapshot())
                        .write(checkpointPath);
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shutdown) {
            }
        }
    }
}
