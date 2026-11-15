package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary.StateCapture.LiquidationChange;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.PortfolioIncomePeriod;
import com.surprising.realtime.provider.export.config.TradeExportProperties;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.service.ClusterCounters;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.*;

/** Single-threaded committed Archive replay; all replay state belongs to the calling export worker. */
@Slf4j
final class CommittedTradeExporter {
    enum ExportMode { LIVE, FUNDING_REPAIR, COMMAND_RESULT_REPAIR, DELIVERY_REPAIR, EXECUTION_REPAIR, LIQUIDATION_REPAIR, STATISTICS_REPAIR, PORTFOLIO_REPAIR }
    private final TradeExportProperties config;
    private final ProductLine product;
    private final String bootstrapServers;
    private final CommittedOrderProjectionService orders;
    private final Clock clock;

    CommittedTradeExporter(TradeExportProperties config, ProductLine product, String bootstrapServers,
            CommittedOrderProjectionService orders) {
        this(config, product, bootstrapServers, orders, Clock.systemUTC());
    }

    CommittedTradeExporter(TradeExportProperties config, ProductLine product, String bootstrapServers,
            CommittedOrderProjectionService orders, Clock clock) {
        this.config = config;
        this.product = product;
        this.bootstrapServers = bootstrapServers;
        this.orders = orders;
        this.clock = Objects.requireNonNull(clock);
    }

    void run(java.util.concurrent.atomic.AtomicBoolean running, java.util.function.Consumer<Boolean> connected, long stopAfter) throws Exception {
        run(running, connected, stopAfter, ExportMode.LIVE);
    }

    /** Offline projection repair: separate checkpoint, finite boundary, no Kafka or order writes. */
    void repairFunding(long stopAfter) throws Exception {
        if (!product.isFundingProduct() || stopAfter <= 0 || stopAfter == Long.MAX_VALUE
                || !config.checkpoint().getFileName().toString().endsWith(".funding-repair"))
            throw new IllegalArgumentException("funding repair requires a perpetual product, finite end and separate .funding-repair checkpoint");
        run(new java.util.concurrent.atomic.AtomicBoolean(true), ready -> {}, stopAfter, ExportMode.FUNDING_REPAIR);
    }

    /** Rebuild only processing receipts, with an independent checkpoint and no Kafka replay. */
    void repairCommandResults(long stopAfter) throws Exception {
        if (stopAfter <= 0 || stopAfter == Long.MAX_VALUE
                || !config.checkpoint().getFileName().toString().endsWith(".command-repair"))
            throw new IllegalArgumentException("command repair requires a finite end and separate .command-repair checkpoint");
        run(new java.util.concurrent.atomic.AtomicBoolean(true), ready -> {}, stopAfter, ExportMode.COMMAND_RESULT_REPAIR);
    }

    /** 仅回补交割现金账本；独立有限检查点，不写订单/回执水位或发送 Kafka。 */
    void repairDelivery(long stopAfter) throws Exception {
        if (!product.isDeliveryProduct() || stopAfter <= 0 || stopAfter == Long.MAX_VALUE
                || !config.checkpoint().getFileName().toString().endsWith(".delivery-repair"))
            throw new IllegalArgumentException("delivery repair requires a delivery product, finite end and separate .delivery-repair checkpoint");
        run(new java.util.concurrent.atomic.AtomicBoolean(true), ready -> {}, stopAfter, ExportMode.DELIVERY_REPAIR);
    }

    /** Financial history rebuild uses its own finite checkpoint, without Kafka or order writes. */
    void repairPortfolio(long stopAfter) throws Exception {
        if (stopAfter <= 0 || stopAfter == Long.MAX_VALUE
                || !config.checkpoint().getFileName().toString().endsWith(".portfolio-repair"))
            throw new IllegalArgumentException("portfolio repair requires a finite end and separate .portfolio-repair checkpoint");
        run(new java.util.concurrent.atomic.AtomicBoolean(true), ready -> {}, stopAfter, ExportMode.PORTFOLIO_REPAIR);
        if (TradeExportCheckpoint.read(config.checkpoint(), product).logPosition() < stopAfter)
            throw new IllegalStateException("portfolio reconstruction incomplete");
    }

    private void initializePortfolio(TradeExportCheckpoint checkpoint, java.util.concurrent.atomic.AtomicBoolean running) throws Exception {
        if (orders.portfolio().initialized(product)) return;
        Path repair = config.checkpoint().toAbsolutePath().resolveSibling(config.checkpoint().getFileName() + ".portfolio-repair");
        if (checkpoint.logPosition() > 0) {
            var repairConfig = new TradeExportProperties(config.clusterDirectory(), config.aeronDirectory(), config.archiveControlChannel(),
                    repair, config.clusterId(), config.kafkaConfig(), config.checkpointInterval(), config.retryInterval());
            new CommittedTradeExporter(repairConfig, product, null, orders, clock).run(running, ready -> {}, checkpoint.logPosition(), ExportMode.PORTFOLIO_REPAIR);
            if (TradeExportCheckpoint.read(repair, product).logPosition() < checkpoint.logPosition())
                throw new IllegalStateException("portfolio reconstruction incomplete");
        }
        if (!running.get() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("portfolio initialization stopped");
        orders.portfolio().certifyGenesis(product);
        Files.deleteIfExists(repair);
        Files.deleteIfExists(repair.resolveSibling(repair.getFileName() + ".lock"));
        Files.deleteIfExists(repair.resolveSibling(repair.getFileName() + ".next"));
    }

    private void initializeExecutions(TradeExportCheckpoint checkpoint, java.util.concurrent.atomic.AtomicBoolean running) throws Exception {
        Path repair = config.checkpoint().toAbsolutePath().resolveSibling(config.checkpoint().getFileName() + ".execution-repair");
        if (!orders.executionsInitialized(product)) {
            if (checkpoint.logPosition() > 0) {
                var repairConfig = new TradeExportProperties(config.clusterDirectory(), config.aeronDirectory(), config.archiveControlChannel(),
                        repair, config.clusterId(), config.kafkaConfig(), config.checkpointInterval(), config.retryInterval());
                new CommittedTradeExporter(repairConfig, product, null, orders).run(running, ready -> {}, checkpoint.logPosition(), ExportMode.EXECUTION_REPAIR);
                if (TradeExportCheckpoint.read(repair, product).logPosition() < checkpoint.logPosition())
                    throw new IllegalStateException("execution history reconstruction incomplete");
            }
            if (!running.get() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("execution history initialization stopped");
            orders.initializeExecutions(product, checkpoint.logPosition());
        }
        // After SQL readiness commits, no historical replay checkpoint is needed. A future
        // deliberate table rebuild starts from the full Archive, retaining the live checkpoint.
        Files.deleteIfExists(repair);
        Files.deleteIfExists(repair.resolveSibling(repair.getFileName() + ".lock"));
        Files.deleteIfExists(repair.resolveSibling(repair.getFileName() + ".next"));
    }

    private void initializeLiquidations(TradeExportCheckpoint checkpoint, java.util.concurrent.atomic.AtomicBoolean running) throws Exception {
        Path repair = config.checkpoint().toAbsolutePath().resolveSibling(config.checkpoint().getFileName() + ".liquidation-repair");
        if (!orders.liquidationsInitialized(product)) {
            // 现货不产生强平事实，无需为一个空读模型重放全部现货历史。
            if (product != ProductLine.SPOT && checkpoint.logPosition() > 0) {
                var repairConfig = new TradeExportProperties(config.clusterDirectory(), config.aeronDirectory(), config.archiveControlChannel(),
                        repair, config.clusterId(), config.kafkaConfig(), config.checkpointInterval(), config.retryInterval());
                new CommittedTradeExporter(repairConfig, product, null, orders).run(running, ready -> {}, checkpoint.logPosition(), ExportMode.LIQUIDATION_REPAIR);
                if (TradeExportCheckpoint.read(repair, product).logPosition() < checkpoint.logPosition())
                    throw new IllegalStateException("liquidation history reconstruction incomplete");
            }
            if (!running.get() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("liquidation history initialization stopped");
            orders.initializeLiquidations(product);
        }
        Files.deleteIfExists(repair);
        Files.deleteIfExists(repair.resolveSibling(repair.getFileName() + ".lock"));
        Files.deleteIfExists(repair.resolveSibling(repair.getFileName() + ".next"));
    }

    private void initializeStatistics(TradeExportCheckpoint checkpoint,java.util.concurrent.atomic.AtomicBoolean running) throws Exception {
        Path repair=config.checkpoint().toAbsolutePath().resolveSibling(config.checkpoint().getFileName()+".statistics-repair");
        if(!orders.statistics().initialized(product)) {
            if(checkpoint.logPosition()>0) {
                var repairConfig=new TradeExportProperties(config.clusterDirectory(),config.aeronDirectory(),config.archiveControlChannel(),
                        repair,config.clusterId(),config.kafkaConfig(),config.checkpointInterval(),config.retryInterval());
                new CommittedTradeExporter(repairConfig,product,null,orders).run(running,ready->{},checkpoint.logPosition(),ExportMode.STATISTICS_REPAIR);
                if(TradeExportCheckpoint.read(repair,product).logPosition()<checkpoint.logPosition())throw new IllegalStateException("statistics reconstruction incomplete");
            }
            if(!running.get()||Thread.currentThread().isInterrupted())throw new IllegalStateException("statistics initialization stopped");
            orders.statistics().initialize(product,checkpoint.logPosition());
        }
        Files.deleteIfExists(repair);Files.deleteIfExists(repair.resolveSibling(repair.getFileName()+".lock"));Files.deleteIfExists(repair.resolveSibling(repair.getFileName()+".next"));
    }

    private boolean publishStatistics(ExportMode mode,CommittedTradeReplay replay,long position,Aeron aeron) {
        if(mode!=ExportMode.LIVE)return false;
        int clusterId=config.clusterId()==null?com.surprising.aeron.protocol.ProductLineClusterLayout.clusterId(product):config.clusterId();
        int counter=ClusterCounters.find(aeron.countersReader(),AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID,clusterId);
        if(counter<0||position<aeron.countersReader().getCounterValue(counter))return false;
        long now=System.currentTimeMillis();
        orders.statistics().publish(product,replay.currentTradingActivity(now),position,now);
        return true;
    }

    private void publishPortfolioCutoffs(CommittedTradeReplay replay, CommittedTradeExportBatch batch,
                                         long position, long nextCommandTimestamp, long coveredFrom) {
        long now = clock.millis();
        long opening = PortfolioIncomePeriod.dayStart(now);
        long minute = PortfolioIncomePeriod.minuteStart(now);
        boolean published = false;
        // Opening comes first, so a coincident minute is retained as the daily baseline.
        for (long cutoff : new long[]{opening, Math.max(opening, minute - 60_000), minute}) {
            if (replay.clusterTimestamp() >= cutoff || nextCommandTimestamp < cutoff
                    || orders.portfolio().contains(product, cutoff)) continue;
            batch.flush(replay.exportSequence());
            var snapshot = replay.portfolioSnapshot(cutoff, PortfolioIncomePeriod.MAXIMUM_MARK_AGE_MILLIS);
            orders.portfolio().publish(snapshot, position, replay.exportSequence(), coveredFrom, cutoff == opening);
            published = true;
        }
        if (published) orders.portfolio().prune(product, opening, minute);
    }

    private void run(java.util.concurrent.atomic.AtomicBoolean running,
            java.util.function.Consumer<Boolean> connected, long stopAfter, ExportMode mode) throws Exception {
        Path checkpointPath = config.checkpoint().toAbsolutePath();
        if (mode == ExportMode.LIVE && (checkpointPath.getFileName().toString().endsWith(".command-repair")
                || checkpointPath.getFileName().toString().endsWith(".funding-repair")
                || checkpointPath.getFileName().toString().endsWith(".delivery-repair")
                || checkpointPath.getFileName().toString().endsWith(".execution-repair")
                || checkpointPath.getFileName().toString().endsWith(".statistics-repair")
                || checkpointPath.getFileName().toString().endsWith(".portfolio-repair")))
            throw new IllegalArgumentException("live exporter cannot use a repair checkpoint");
        Files.createDirectories(checkpointPath.getParent());
        int clusterId = config.clusterId() == null
                ? com.surprising.aeron.protocol.ProductLineClusterLayout.clusterId(product) : config.clusterId();
        Properties props = new Properties();
        if (mode == ExportMode.LIVE) props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
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
            if (mode == ExportMode.LIVE) initializeExecutions(checkpoint, running);
            if (mode == ExportMode.LIVE) initializeLiquidations(checkpoint, running);
            if (mode == ExportMode.LIVE) initializeStatistics(checkpoint, running);
            if (mode == ExportMode.LIVE) initializePortfolio(checkpoint, running);
            try (var replay = new CommittedTradeReplay(product, checkpoint.snapshot());
                    var sink =
                            mode != ExportMode.LIVE ? null : new ReliableTradeKafkaSink(
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
                    var archiveReplay = new CommittedTradeArchiveReplay(aeron, archive, product)) {
                long cursor = checkpoint.logPosition();
                if (mode == ExportMode.LIVE) {
                    orders.initializeFees(product, replay);
                    orders.initializeTriggers(product, replay, cursor);
                    orders.algos().initialize(product, replay, cursor);
                }
                boolean[] initialStatisticsPublished={false};
                long[] nextCheckpoint = {System.nanoTime() + checkpointInterval};
                var batch = new CommittedTradeExportBatch(product, orders, sink, mode);
                long portfolioCoveredFrom = mode == ExportMode.LIVE
                        ? orders.portfolio().start(product, checkpoint.logPosition(), replay.clusterTimestamp()).timestamp() : 0;
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
                        if (mode == ExportMode.LIVE || mode == ExportMode.PORTFOLIO_REPAIR)
                            publishPortfolioCutoffs(replay, batch, delivered[0], sessionHeader.timestamp(), portfolioCoveredFrom);
                        var changes = replay.apply(bytes, sessionHeader.timestamp(), header.position());
                        if (replay.fundingPage() != null && (mode == ExportMode.LIVE || mode == ExportMode.FUNDING_REPAIR)) {
                            // Flush earlier commands, then atomically persist this exact funding
                            // page. A SQL failure prevents checkpoint advancement and is retried
                            // from the old committed replay state; SQL identity makes it idempotent.
                            batch.flush(replay.exportSequence());
                            orders.persistFunding(replay.fundingPage());
                        }
                        if (replay.deliveryPage() != null && (mode == ExportMode.LIVE || mode == ExportMode.DELIVERY_REPAIR)) {
                            batch.flush(replay.exportSequence());
                            orders.persistDelivery(replay.deliveryPage());
                        }
                        if (mode == ExportMode.LIVE) batch.addFees(replay.tradeFeeObservations(changes));
                        batch.add(changes,
                                replay.commandResult(), replay.exportSequence(), System.nanoTime(), replay.algoChanges(), replay.balanceFlow(), replay.liquidationChanges());
                    }
                    delivered[0] = header.position();
                    if (System.nanoTime() >= nextCheckpoint[0]) {
                        batch.flush(replay.exportSequence());
                        try {
                            publishStatistics(mode,replay,delivered[0],aeron);
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
                    // During recovery, flushing almost every historical command wastes SQL
                    // round trips and prevents the exporter from catching the live core.
                    batch.catchUp(committed - position > 64 * 1024);
                    if (committed <= position) {
                        if(mode==ExportMode.LIVE&&!initialStatisticsPublished[0]) {
                            batch.flush(replay.exportSequence());
                            initialStatisticsPublished[0]=publishStatistics(mode,replay,cursor,aeron);
                        }
                        if (System.nanoTime() >= nextCheckpoint[0]) {
                            batch.flush(replay.exportSequence());
                            publishStatistics(mode,replay,cursor,aeron);
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
                    if (archiveReplay.recordingId() != term.recordingId) {
                        if (partialMessage[0])
                            throw new IllegalStateException("fragmented command crosses archive recordings");
                        assembler.clear();
                    }
                    archiveReplay.follow(term.recordingId, position, safeEnd);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (archiveReplay.subscription().imageCount() == 0) {
                        if (!running.get() || Thread.currentThread().isInterrupted()) break exportLoop;
                        if (System.nanoTime() > deadline)
                            throw new IllegalStateException("archive replay image unavailable");
                        LockSupport.parkNanos(100_000);
                    }
                    Image image = archiveReplay.subscription().imageAtIndex(0);
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
                        publishStatistics(mode,replay,cursor,aeron);
                        new TradeExportCheckpoint(product, cursor, (sink == null ? checkpoint.tradeSequence() : sink.tradeSequence()), replay.snapshot())
                                .write(checkpointPath);
                        log.info("trade-export product={} committedPosition={} exportedPosition={} trades={}",
                                product, committed, cursor, sink == null ? mode.name() : sink.tradeSequence());
                        nextCheckpoint[0] = System.nanoTime() + checkpointInterval;
                    }
                }
                batch.flush(replay.exportSequence());
                publishStatistics(mode,replay,cursor,aeron);
                new TradeExportCheckpoint(product, cursor, (sink == null ? checkpoint.tradeSequence() : sink.tradeSequence()), replay.snapshot())
                        .write(checkpointPath);
            }
        }
    }

}
