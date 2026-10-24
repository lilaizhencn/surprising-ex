package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.*;
import com.surprising.product.api.utils.ProductTopicNames;
import com.surprising.realtime.provider.export.config.TradeExportProperties;
import com.surprising.realtime.provider.export.service.CommittedAlgoProjectionService;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import com.surprising.realtime.provider.export.service.CommittedPortfolioHistoryService;
import com.surprising.realtime.provider.export.service.CommittedTradingStatisticsService;
import com.surprising.trading.api.model.PublicTradeEvent;
import io.aeron.*;
import io.aeron.archive.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.codecs.MessageHeaderEncoder;
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder;
import io.aeron.cluster.service.ClusterCounters;
import io.aeron.driver.MediaDriver;
import java.net.DatagramSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.kafka.clients.consumer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class CommittedTradeExportIntegrationTest {
    @TempDir Path temp;
    private final CommittedOrderProjectionService orders = org.mockito.Mockito.mock(CommittedOrderProjectionService.class);
    {
        org.mockito.Mockito.when(orders.algos()).thenReturn(org.mockito.Mockito.mock(CommittedAlgoProjectionService.class));
        var statistics=org.mockito.Mockito.mock(CommittedTradingStatisticsService.class);
        org.mockito.Mockito.when(statistics.initialized(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.when(orders.statistics()).thenReturn(statistics);
        var portfolio = org.mockito.Mockito.mock(CommittedPortfolioHistoryService.class);
        org.mockito.Mockito.when(portfolio.initialized(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.when(portfolio.start(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new com.surprising.realtime.provider.export.model.PortfolioProjectionStart(0, 0));
        org.mockito.Mockito.when(orders.portfolio()).thenReturn(portfolio);
    }

    @Test
    void publishesOnlyCommittedTradesAndReplaysSameIdentityAfterCrashWindow() throws Exception {
        var projectionUnavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        var projected = java.util.Collections.synchronizedList(new java.util.ArrayList<RealtimeFrame>());
        org.mockito.Mockito.doAnswer(call -> {
            if (projectionUnavailable.getAndSet(false)) throw new IllegalStateException("projection unavailable");
            projected.addAll(call.<List<RealtimeFrame>>getArgument(1));
            return null;
        }).when(orders).persist(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
        String topic = ProductTopicNames.of(ProductLine.SPOT).matchTradesTopic();
        var broker =
                new EmbeddedKafkaKraftBroker(1, 1, topic)
                        .brokerProperties(
                                Map.of(
                                        "transaction.state.log.replication.factor",
                                        "1",
                                        "transaction.state.log.min.isr",
                                        "1",
                                        "offsets.topic.replication.factor",
                                        "1"));
        broker.afterPropertiesSet();
        String directory = temp.resolve("driver").toString();
        int port;
        try (var socket = new DatagramSocket(0)) {
            port = socket.getLocalPort();
        }
        String control = "aeron:udp?endpoint=127.0.0.1:" + port;
        Path clusterDir = temp.resolve("cluster"), checkpoint = temp.resolve("export/checkpoint");
        Files.createDirectories(clusterDir);
        try (var driver =
                        MediaDriver.launch(
                                new MediaDriver.Context()
                                        .aeronDirectoryName(directory)
                                        .dirDeleteOnShutdown(true));
                var archiveService =
                        Archive.launch(
                                new Archive.Context()
                                        .aeronDirectoryName(directory)
                                        .archiveDir(temp.resolve("archive").toFile())
                                        .controlChannel(control)
                                        .replicationChannel("aeron:udp?endpoint=127.0.0.1:0")
                                        .threadingMode(ArchiveThreadingMode.SHARED));
                var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory));
                var archive =
                        AeronArchive.connect(
                                new AeronArchive.Context()
                                        .aeron(aeron)
                                        .ownsAeronClient(false)
                                        .controlRequestChannel(control)
                                        .controlResponseChannel("aeron:ipc"));
                var counter =
                        ClusterCounters.allocate(
                                aeron,
                                new UnsafeBuffer(new byte[1024]),
                                "test commit",
                                AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID,
                                0)) {
            archive.startRecording("aeron:ipc", 1001, SourceLocation.LOCAL);
            try (var publication = aeron.addExclusivePublication("aeron:ipc?mtu=128", 1001)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int recordingCounter;
                while ((recordingCounter =
                                RecordingPos.findCounterIdBySession(
                                        aeron.countersReader(), publication.sessionId()))
                        < 0) {
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException("recording did not start");
                    Thread.sleep(10);
                }
                long recordingId =
                        RecordingPos.getRecordingId(aeron.countersReader(), recordingCounter);
                try (var log = new RecordingLog(clusterDir.toFile(), true)) {
                    log.appendTerm(recordingId, 1, 0, 1700000000000L);
                    log.force(2);
                }
                long position = offer(publication, instrument());
                position =
                        offer(
                                publication,
                                command(
                                        CoreMessageType.ADJUST_BALANCE,
                                        1,
                                        1001,
                                        TradingCommandCodec.encodeBalanceAdjustment(
                                                new BalanceAdjustmentCommand("USDT", 10000))));
                position =
                        offer(
                                publication,
                                command(
                                        CoreMessageType.ADJUST_BALANCE,
                                        2,
                                        1002,
                                        TradingCommandCodec.encodeBalanceAdjustment(
                                                new BalanceAdjustmentCommand("BTC", 10))));
                position =
                        offer(
                                publication,
                                command(
                                        CoreMessageType.PLACE_ORDER,
                                        3,
                                        1002,
                                        TradingCommandCodec.encodePlaceOrder(
                                                order(1, CoreOrderSide.SELL))));
                long beforeTrade = position;
                long afterTrade =
                        offer(
                                publication,
                                command(
                                        CoreMessageType.PLACE_ORDER,
                                        4,
                                        1001,
                                        TradingCommandCodec.encodePlaceOrder(
                                                order(2, CoreOrderSide.BUY))));
                while (aeron.countersReader().getCounterValue(recordingCounter) < afterTrade) {
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException("recording lag");
                    Thread.sleep(10);
                }
                counter.set(beforeTrade);
                assertThatThrownBy(() -> runExport(clusterDir, directory, control,
                        broker.getBrokersAsString(), checkpoint, beforeTrade))
                        .isInstanceOf(IllegalStateException.class).hasMessage("projection unavailable");
                assertThat(Files.exists(checkpoint)).isFalse();
                runExport(
                        clusterDir,
                        directory,
                        control,
                        broker.getBrokersAsString(),
                        checkpoint,
                        beforeTrade);
                byte[] beforeCheckpoint = Files.readAllBytes(checkpoint);
                assertThat(TradeExportCheckpoint.read(checkpoint, ProductLine.SPOT).tradeSequence())
                        .isZero();
                assertThat(afterTrade - beforeTrade).isGreaterThan(128);
                counter.set(
                        beforeTrade
                                + 128); // Only the first fragment of the taker command is
                                        // committed.
                // Stop after observing an incomplete committed command, then resume from the last full message.
                var cancel = new java.util.concurrent.atomic.AtomicBoolean(true);
                var observations = new java.util.concurrent.atomic.AtomicInteger();
                var partialConfig = new TradeExportProperties(clusterDir, directory, control, checkpoint, 0, null, null, null);
                new CommittedTradeExporter(partialConfig, ProductLine.SPOT, broker.getBrokersAsString(), orders)
                        .run(cancel, ready -> {
                            if (ready && observations.incrementAndGet() == 2) cancel.set(false);
                        }, Long.MAX_VALUE);
                assertThat(TradeExportCheckpoint.read(checkpoint, ProductLine.SPOT).logPosition()).isEqualTo(beforeTrade);
                assertThat(TradeExportCheckpoint.read(checkpoint, ProductLine.SPOT).tradeSequence()).isZero();
                var export =
                        java.util.concurrent.CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        runExport(
                                                clusterDir,
                                                directory,
                                                control,
                                                broker.getBrokersAsString(),
                                                checkpoint,
                                                afterTrade);
                                    } catch (Exception e) {
                                        throw new java.util.concurrent.CompletionException(e);
                                    }
                                });
                try {
                    Thread.sleep(1500);
                    assertThat(export.isDone()).isFalse();
                    assertThat(
                                    TradeExportCheckpoint.read(checkpoint, ProductLine.SPOT)
                                            .tradeSequence())
                            .isZero();
                } finally {
                    counter.set(afterTrade);
                }
                export.get(15, TimeUnit.SECONDS);
                assertThat(TradeExportCheckpoint.read(checkpoint, ProductLine.SPOT).tradeSequence())
                        .isEqualTo(1);
                // Spring-owned thread can resume the same checkpoint and stop without a JVM shutdown hook.
                var config = new TradeExportProperties(clusterDir, directory, control, checkpoint, 0, null, null, null);
                var candles = new com.surprising.candlestick.provider.config.CandlestickProperties();
                candles.getKafka().setProductLine(ProductLine.SPOT);
                candles.getKafka().setBootstrapServers(broker.getBrokersAsString());
                var service = new TradeExportService(config, candles, orders);
                try {
                    service.start();
                    long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (!service.health().getStatus().equals(org.springframework.boot.health.contributor.Status.UP)
                            && System.nanoTime() < readyDeadline) Thread.sleep(10);
                    assertThat(service.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.UP);
                    assertThatThrownBy(() -> runExport(clusterDir, directory, control,
                            broker.getBrokersAsString(), checkpoint, afterTrade))
                            .isInstanceOf(java.nio.channels.OverlappingFileLockException.class);

                } finally { service.stop(); }
                assertThat(service.isRunning()).isFalse();
                assertThat(TradeExportCheckpoint.read(checkpoint, ProductLine.SPOT).logPosition()).isEqualTo(afterTrade);
                // Simulate Kafka commit succeeding just before the local checkpoint rename.
                Files.write(checkpoint, beforeCheckpoint);
                runExport(
                        clusterDir,
                        directory,
                        control,
                        broker.getBrokersAsString(),
                        checkpoint,
                        afterTrade);
                Properties cp = new Properties();
                cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
                cp.put(
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        "org.apache.kafka.common.serialization.StringDeserializer");
                cp.put(
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        "org.apache.kafka.common.serialization.StringDeserializer");
                cp.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
                cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                cp.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
                try (var consumer = new KafkaConsumer<String, String>(cp)) {
                    consumer.subscribe(List.of(topic));
                    var events = new ArrayList<PublicTradeEvent>();
                    var mapper = new ObjectMapper();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (events.size() < 2 && System.nanoTime() < until)
                        for (var record : consumer.poll(Duration.ofMillis(100)))
                            events.add(mapper.readValue(record.value(), PublicTradeEvent.class));
                    assertThat(events).hasSize(2);
                    assertThat(events.getFirst()).isEqualTo(events.getLast());
                    assertThat(events.getFirst().sequence()).isEqualTo(1);
                    assertThat(events.getFirst().priceTicks()).isEqualTo(100);
                    assertThat(events.getFirst().quantitySteps()).isEqualTo(3);
                }
                assertThat(projected.stream().filter(f -> f.kind() == RealtimeFrame.Kind.ORDER)
                        .map(f -> CoreStateQueryCodec.decodeOrderState(f.payload()))
                        .filter(o -> "FILLED".equals(o.status())).map(CoreOrderStateView::orderId))
                        .contains(1L, 2L);
                byte[] committedCheckpoint = Files.readAllBytes(checkpoint);
                var repaired = new ArrayList<com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult>();
                org.mockito.Mockito.doAnswer(call -> {
                    repaired.addAll(call.<List<com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult>>getArgument(1));
                    return null;
                }).when(orders).repairCommandResults(org.mockito.ArgumentMatchers.eq(ProductLine.SPOT), org.mockito.ArgumentMatchers.anyList());
                long liveWrites = org.mockito.Mockito.mockingDetails(orders).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().equals("persist")).count();
                var repairCheckpoint = temp.resolve("export/receipts.command-repair");
                var repairConfig = new TradeExportProperties(clusterDir, directory, control, repairCheckpoint, 0, null, null, null);
                var repair = new CommittedTradeExporter(repairConfig, ProductLine.SPOT, null, orders);
                repair.repairCommandResults(afterTrade);
                assertThat(repaired).anySatisfy(result -> {
                    assertThat(result.commandId()).isEqualTo(new UUID(77, 4));
                    assertThat(result.userId()).isEqualTo(1001);
                    assertThat(result.clientOrderId()).isEqualTo("replay-2");
                    assertThat(result.code()).isEqualTo(CoreResultCode.NONE);
                });
                assertThat(TradeExportCheckpoint.read(repairCheckpoint, ProductLine.SPOT).logPosition()).isEqualTo(afterTrade);
                assertThat(Files.readAllBytes(checkpoint)).isEqualTo(committedCheckpoint);
                assertThat(org.mockito.Mockito.mockingDetails(orders).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().equals("persist")).count()).isEqualTo(liveWrites);
                int repairedCount = repaired.size();
                repair.repairCommandResults(afterTrade);
                assertThat(repaired).hasSize(repairedCount);
                assertThatThrownBy(() -> repair.repairCommandResults(Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
                var invalid = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,
                        UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY,
                        77, 5, 1001, 1700000000000L, 1),
                        TradingCommandCodec.encodePlaceOrder(order(3, CoreOrderSide.BUY)));
                offer(publication, invalid);
                long invalidEnd = offer(publication, command(CoreMessageType.ADJUST_BALANCE, 6, 1001,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))));
                long recordedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (aeron.countersReader().getCounterValue(recordingCounter) < invalidEnd) {
                    if (System.nanoTime() > recordedDeadline) throw new IllegalStateException("recording lag");
                    Thread.sleep(10);
                }
                counter.set(invalidEnd);
                assertThatThrownBy(() -> runExport(clusterDir, directory, control,
                        broker.getBrokersAsString(), checkpoint, invalidEnd))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("cross-product replay message");
                assertThat(Files.readAllBytes(checkpoint)).isEqualTo(committedCheckpoint);
            }
        } finally {
            broker.destroy();

        }
    }

    private void runExport(
            Path cluster, String directory, String control, String kafka, Path checkpoint, long end)
            throws Exception {
        var config = new TradeExportProperties(cluster, directory, control, checkpoint, 0, null, null, null);
        new CommittedTradeExporter(config, ProductLine.SPOT, kafka, orders)
                .run(new java.util.concurrent.atomic.AtomicBoolean(true), ready -> {}, end);
    }

    private static long offer(ExclusivePublication publication, CoreMessage command)
            throws Exception {
        byte[] body = CoreMessageCodec.encode(command);
        int offset = MessageHeaderEncoder.ENCODED_LENGTH + SessionMessageHeaderEncoder.BLOCK_LENGTH;
        var buffer = new UnsafeBuffer(new byte[offset + body.length]);
        new SessionMessageHeaderEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .leadershipTermId(1)
                .clusterSessionId(7)
                .timestamp(1700000000000L);
        buffer.putBytes(offset, body);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5), result;
        while ((result = publication.offer(buffer)) < 0) {
            if (System.nanoTime() > until)
                throw new IllegalStateException("log publication unavailable");
            Thread.sleep(1);
        }
        return result;
    }

    private static PlaceOrderCommand order(long id, CoreOrderSide side) {
        return new PlaceOrderCommand(
                id,
                "1",
                side,
                100,
                3,
                false,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                CoreOrderType.LIMIT,
                CoreTimeInForce.GTC,
                false,
                "replay-" + id);
    }

    private static CoreMessage instrument() {
        return new CoreMessage(
                CoreMessageHeader.command(
                        CoreMessageType.REGISTER_INSTRUMENT,
                        new UUID(88, 1),
                        ProductLine.SPOT,
                        CommandSource.OPERATIONS,
                        88,
                        1,
                        0,
                        1700000000000L,
                        1),
                TradingCommandCodec.encodeRegisterInstrument(
                        new RegisterInstrumentCommand(
                                "1", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0), ProductLine.SPOT));
    }

    private static CoreMessage command(CoreMessageType type, long seq, long user, byte[] body) {
        return new CoreMessage(
                CoreMessageHeader.command(
                        type,
                        new UUID(77, seq),
                        ProductLine.SPOT,
                        CommandSource.GATEWAY,
                        77,
                        seq,
                        user,
                        1700000000000L,
                        seq),
                body);
    }
}
