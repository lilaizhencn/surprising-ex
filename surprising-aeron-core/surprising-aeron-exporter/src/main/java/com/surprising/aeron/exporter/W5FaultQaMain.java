package com.surprising.aeron.exporter;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

public final class W5FaultQaMain {

    private static final String PRODUCT_LINE_NAME = "LINEAR_PERPETUAL";
    private static final String WRAPPER_SCRIPT =
            "marker=\"$0\"; child=\"\"; terminate(){ [[ -z \"$child\" ]] || kill \"$child\" 2>/dev/null || true; "
                    + "[[ -z \"$child\" ]] || wait \"$child\" 2>/dev/null || true; exit 0; }; "
                    + "trap terminate TERM INT; \"$@\" & child=$!; wait \"$child\"";
    private static final Pattern EVENT_ID = Pattern.compile("\\\"eventId\\\":\\\"([^\\\"]+)\\\"");

    private W5FaultQaMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "" : args[0];
        switch (mode) {
            case "export-projection" -> new Driver().runExportProjection();
            case "isolation" -> new Driver().runIsolation();
            case "controlled-exporter" -> runControlledExporter();
            default -> throw new IllegalArgumentException(
                    "usage: export-projection | isolation | controlled-exporter");
        }
    }

    private static void runControlledExporter() throws Exception {
        ProductLine productLine = ExporterConfiguration.productLine();
        require(productLine == ProductLine.LINEAR_PERPETUAL,
                "controlled W5 exporter only accepts LINEAR_PERPETUAL");
        String runId = required("RUN_ID");
        Path runDirectory = runtimeRunDirectory(runId);
        Path marker = Path.of(required("W5_EXPORT_BARRIER_PATH"));
        W5PublishBarrier barrier = W5PublishBarrier.create(runDirectory, marker);
        try (KafkaCoreExportSink kafka = new KafkaCoreExportSink(ExporterConfiguration.kafkaProducerProperties());
             SurprisingAeronClient core = SurprisingAeronClient.connect(productLine,
                     ExporterConfiguration.aeronHosts(), ExporterConfiguration.aeronEgressHost(),
                     ExporterConfiguration.aeronTimeout())) {
            ReliableCoreExporter exporter = new ReliableCoreExporter(productLine, core::submit,
                    barrier.blockingSink(kafka), ExporterConfiguration.batchSize());
            barrier.markReady();
            System.out.printf("CONTROLLED_EXPORTER_BARRIER=READY runId=%s marker=%s%n", runId, marker);
            System.out.flush();
            CoreExportStatus pending = awaitControlledPending(exporter, Duration.ofSeconds(30));
            System.out.printf("CONTROLLED_EXPORTER_PENDING=PASS pending=%d acknowledged=%d next=%d%n",
                    pending.pendingCount(), pending.acknowledgedSequence(), pending.nextSequence());
            exporter.exportOnce();
            throw new IllegalStateException("controlled exporter returned past publish-before-ACK barrier");
        }
    }

    private static CoreExportStatus awaitControlledPending(ReliableCoreExporter exporter, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            CoreExportStatus status = exporter.status();
            if (status.pendingCount() > 0) {
                return status;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("controlled exporter timed out waiting for a pending Core event");
    }

    private static Path runtimeRunDirectory(String runId) {
        Path runtimeRoot = Path.of(env("RUNTIME_ROOT",
                Path.of(System.getProperty("java.io.tmpdir"), "surprising-w3-w5-runtime").toString()));
        return runtimeRoot.toAbsolutePath().normalize().resolve("runs").resolve(runId);
    }

    private static final class Driver {

        private final ProductLine productLine;
        private final ProductTopicNames topics;
        private final String kafkaBootstrap;
        private final String databaseUrl;
        private final String databaseUser;
        private final String databasePassword;
        private final String gatewayUrl;
        private final List<String> coreHosts;
        private final String coreEgressHost;
        private final String runId;
        private final Path repoRoot;
        private final Path runtimeRoot;
        private final Path runDir;
        private final Path pidDir;
        private final Path logDir;
        private final String composeProject;
        private final long sourceId;
        private long sourceSequence;
        private long correlation;
        private int projectionFailureCount;
        private int projectionGapRejectionCount;
        private int exporterFailureCount;
        private int exporterRetryCount;

        private Driver() {
            productLine = ProductLine.requireExternalCode(env("PRODUCT_LINE", PRODUCT_LINE_NAME));
            if (productLine != ProductLine.LINEAR_PERPETUAL) {
                throw new IllegalArgumentException("W5 only accepts LINEAR_PERPETUAL");
            }
            topics = ProductTopicNames.of(productLine);
            kafkaBootstrap = env("KAFKA_BOOTSTRAP_SERVERS", required("KAFKA_BOOTSTRAP_SERVERS"));
            databaseUrl = env("DATABASE_URL", required("DATABASE_URL"));
            databaseUser = env("DATABASE_USER", required("DATABASE_USER"));
            databasePassword = env("DATABASE_PASSWORD", required("DATABASE_PASSWORD"));
            gatewayUrl = env("GATEWAY_URL", "http://127.0.0.1:9094");
            coreHosts = splitHosts(env("AERON_HOSTNAMES", "127.0.0.1,127.0.0.1,127.0.0.1"));
            coreEgressHost = env("AERON_EGRESS_HOSTNAME", "127.0.0.1");
            runId = required("RUN_ID");
            repoRoot = Path.of(env("REPO_ROOT", Path.of(".").toAbsolutePath().normalize().toString()));
            runtimeRoot = Path.of(env("RUNTIME_ROOT",
                    Path.of(System.getProperty("java.io.tmpdir"), "surprising-w3-w5-runtime").toString()))
                    .toAbsolutePath().normalize();
            runDir = runtimeRunDirectory(runId);
            pidDir = runDir.resolve("pids");
            logDir = runDir.resolve("logs");
            composeProject = "surprising-w3w5-" + runId.toLowerCase().replace('_', '-');
            sourceId = 91_000_000L + Math.floorMod(runId.hashCode(), 900_000L);
            sourceSequence = 0L;
            correlation = 0L;
        }

        private void runExportProjection() throws Exception {
            printHeader("w5-export-projection");
            requireRuntimeFiles();
            try (KafkaObserver observer = new KafkaObserver(kafkaBootstrap, topics.coreEventsTopic(), runId + "-observer");
                 SurprisingAeronClient core = connectCore()) {
                waitForCore(core);
                PgSnapshot before = requirePg("before");
                long initialStateHash = stateHash(core);
                CoreExportStatus initialStatus = exportStatus(core);
                require(initialStatus.pendingCount() == 0, "initial Core export backlog is not empty");
                printCore("initial", initialStatus, initialStateHash);

                stopOwnedProcess("exporter");
                Path crashBarrierPath = W5PublishBarrier.markerPath(runDir);
                W5PublishBarrier crashBarrier = W5PublishBarrier.observe(runDir, crashBarrierPath);
                startControlledExporter(crashBarrierPath);
                crashBarrier.await(W5PublishBarrier.State.READY, Duration.ofSeconds(20));
                require(processAlive("controlled-exporter"), "controlled exporter exited before READY");
                SequenceRange crashRange = generateProbeEvents(core, 1, "publish-before-ack");
                crashBarrier.await(W5PublishBarrier.State.PUBLISHED, Duration.ofSeconds(20));
                Map<Long, KafkaEvent> crashEvents = observer.await(Set.of(crashRange.firstSequence()),
                        Duration.ofSeconds(15));
                KafkaEvent firstCrashEvent = crashEvents.get(crashRange.firstSequence());
                require(firstCrashEvent != null, "first published crash-window event was not observed");
                printKafka("publish-before-ack", firstCrashEvent);
                CoreExportStatus stopped = exportStatus(core);
                require(stopped.pendingCount() > 0, "controlled exporter ACKed past its publish barrier");
                long controlledChild = childPid("controlled-exporter", W5FaultQaMain.class.getName());
                System.out.printf("PUBLISH_BEFORE_ACK_CRASH=PASS pending=%d acknowledged=%d next=%d "
                                + "childPid=%d barrier=%s%n",
                        stopped.pendingCount(), stopped.acknowledgedSequence(), stopped.nextSequence(),
                        controlledChild, crashBarrierPath);
                crashOwnedProcess("controlled-exporter", W5FaultQaMain.class.getName());
                restartOwnedProcess("exporter");
                KafkaEvent replay = observer.awaitReplay(crashRange.firstSequence(), firstCrashEvent.offset(),
                        Duration.ofSeconds(20));
                CoreExportStatus recovered = awaitStatus(core,
                        status -> status.pendingCount() == 0, Duration.ofSeconds(20));
                PgSnapshot afterCrash = awaitPgWatermark(recovered.acknowledgedSequence(), Duration.ofSeconds(20));
                require(afterCrash.watermark() == recovered.acknowledgedSequence(),
                        "PG watermark did not recover through crash replay");
                System.out.printf("CRASH_REPLAY=PASS originalOffset=%d replayOffset=%d coreAck=%d pgWatermark=%d%n",
                        firstCrashEvent.offset(), replay.offset(), recovered.acknowledgedSequence(),
                        afterCrash.watermark());

                KafkaEvent duplicate = publishDuplicate(firstCrashEvent);
                waitForCommitted(projectionGroup(), duplicate.offset() + 1, Duration.ofSeconds(15));
                PgSnapshot afterDuplicate = requirePg("duplicate");
                require(afterDuplicate.watermark() == afterCrash.watermark(), "duplicate advanced PG watermark");
                System.out.printf("DUPLICATE_REPLAY=PASS originalOffset=%d duplicateOffset=%d key=%s%n",
                        firstCrashEvent.offset(), duplicate.offset(), duplicate.key());

                stopOwnedProcess("projector");
                CoreExportStatus beforeReorder = exportStatus(core);
                SequenceRange reorderRange = generateProbeEvents(core, 3, "reorder-gap");
                Map<Long, KafkaEvent> reorderEvents = observer.await(reorderRange.sequences(), Duration.ofSeconds(45));
                JdbcCoreEventProjector directProjector = new JdbcCoreEventProjector(
                        new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
                expectGap(directProjector, reorderEvents.get(reorderRange.firstSequence() + 1).message());
                require(directProjector.project(productLine,
                        reorderEvents.get(reorderRange.firstSequence()).message()), "ordered first event was not projected");
                require(directProjector.project(productLine,
                        reorderEvents.get(reorderRange.firstSequence() + 1).message()), "ordered second event was not projected");
                require(directProjector.project(productLine,
                        reorderEvents.get(reorderRange.lastSequence()).message()), "ordered third event was not projected");
                projectionGapRejectionCount++;
                System.out.printf("REORDER_GAP=PASS priorWatermark=%d rejectedSequence=%d recoveredThrough=%d%n",
                        beforeReorder.acknowledgedSequence(), reorderRange.firstSequence() + 1, reorderRange.lastSequence());
                restartOwnedProcess("projector");
                waitForCommitted(projectionGroup(), reorderEvents.get(reorderRange.lastSequence()).offset() + 1,
                        Duration.ofSeconds(60));
                PgSnapshot afterReorder = awaitPgWatermark(reorderRange.lastSequence(), Duration.ofSeconds(20));
                require(afterReorder.watermark() == reorderRange.lastSequence(), "reordered replay did not converge");
                System.out.printf("PROJECTOR_RESTART=PASS committedOffset=%d pgWatermark=%d%n",
                        reorderEvents.get(reorderRange.lastSequence()).offset() + 1, afterReorder.watermark());

                PgSnapshot beforePause = requirePg("pause-before");
                pauseOwnedContainer("postgres");
                SequenceRange pgPauseRange = generateProbeEvents(core, 1, "pg-pause");
                Map<Long, KafkaEvent> pgPauseEvents = observer.await(pgPauseRange.sequences(), Duration.ofSeconds(15));
                boolean pgUnavailable = awaitPgUnavailable(Duration.ofMillis(1_000));
                require(pgUnavailable, "PostgreSQL pause did not make the projection authority unavailable");
                CoreExportStatus duringPgPause = awaitStatus(core,
                        status -> status.acknowledgedSequence() >= pgPauseRange.lastSequence(),
                        Duration.ofSeconds(10));
                long projectionLag = duringPgPause.acknowledgedSequence() - beforePause.watermark();
                require(projectionLag > 0, "projection lag did not open while PostgreSQL was paused");
                System.out.printf("PROJECTION_LAG_TIMEOUT=PASS sequence=%d priorWatermark=%d observedAt=%s%n",
                        pgPauseRange.firstSequence(), beforePause.watermark(), Instant.now());
                System.out.printf("PROJECTION_LAG metric=core_ack_minus_pg_watermark value=%d coreAck=%d pgWatermark=%d%n",
                        projectionLag, duringPgPause.acknowledgedSequence(), beforePause.watermark());
                unpauseOwnedContainer("postgres");
                restartOwnedProcess("projector");
                waitForCommitted(projectionGroup(), pgPauseEvents.get(pgPauseRange.firstSequence()).offset() + 1,
                        Duration.ofSeconds(20));
                PgSnapshot afterPause = awaitPgWatermark(pgPauseRange.lastSequence(), Duration.ofSeconds(20));
                require(afterPause.watermark() == pgPauseRange.lastSequence(), "PG pause recovery watermark mismatch");
                System.out.printf("PG_PAUSE_RECOVERY=PASS kafkaOffset=%d pgWatermark=%d%n",
                        pgPauseEvents.get(pgPauseRange.lastSequence()).offset() + 1, afterPause.watermark());

                CoreExportStatus beforeKafkaPause = exportStatus(core);
                stopOwnedProcess("gateway");
                stopOwnedContainer("kafka");
                SequenceRange kafkaPauseRange = generateProbeEvents(core, 4, "exporter-disconnect");
                CoreExportStatus pendingDuringKafkaPause = awaitStatus(core,
                        status -> status.pendingCount() > 0, Duration.ofSeconds(5));
                require(pendingDuringKafkaPause.pendingCount() > 0,
                        "Core export did not remain pending while Kafka was disconnected");
                waitForLog("exporter", Duration.ofSeconds(45), "cycle failed", "reconnecting");
                exporterFailureCount = countLog("exporter", "cycle failed", "reconnecting");
                exporterRetryCount = exporterFailureCount;
                System.out.printf("EXPORTER_DISCONNECT=PASS priorAck=%d pending=%d firstKafkaOffset=%d%n",
                        beforeKafkaPause.acknowledgedSequence(), pendingDuringKafkaPause.pendingCount(),
                        -1L);
                stopOwnedProcess("exporter");
                startOwnedContainer("kafka");
                awaitKafkaExternal();
                restartOwnedProcess("exporter");
                Map<Long, KafkaEvent> kafkaPauseEvents = observer.await(kafkaPauseRange.sequences(),
                        Duration.ofSeconds(60));
                printKafka("exporter-disconnect-recovered", kafkaPauseEvents.get(kafkaPauseRange.firstSequence()));
                CoreExportStatus afterKafka = awaitStatus(core,
                        status -> status.pendingCount() == 0, Duration.ofSeconds(60));
                PgSnapshot afterKafkaProjection = awaitPgWatermark(kafkaPauseRange.lastSequence(), Duration.ofSeconds(60));
                require(afterKafkaProjection.watermark() == kafkaPauseRange.lastSequence(),
                        "Kafka reconnect did not recover projection");
                System.out.printf("ADAPTIVE_RECOVERY=PASS coreAck=%d pgWatermark=%d%n",
                        afterKafka.acknowledgedSequence(), afterKafkaProjection.watermark());
                restartOwnedProcess("gateway");
                CoreExportStatus beforeGateway = exportStatus(core);
                SequenceRange gatewayRange = generateProbeEvents(core, 2, "gateway-restart");
                Map<Long, KafkaEvent> gatewayEvents = observer.await(gatewayRange.sequences(), Duration.ofSeconds(15));
                PgSnapshot afterGatewayProjection = awaitPgWatermark(gatewayRange.lastSequence(), Duration.ofSeconds(20));
                require(afterGatewayProjection.watermark() == gatewayRange.lastSequence(),
                        "gateway restart scenario was not projected");
                waitForCommitted(websocketGroup(), gatewayEvents.get(gatewayRange.lastSequence()).offset() + 1,
                        Duration.ofSeconds(20));
                printGroupOffsets(gatewayEvents.get(gatewayRange.lastSequence()).offset() + 1);
                KafkaEvent gatewayDuplicate = publishDuplicate(gatewayEvents.get(gatewayRange.lastSequence()));
                waitForCommitted(websocketGroup(), gatewayDuplicate.offset() + 1, Duration.ofSeconds(15));
                System.out.printf("GATEWAY_RESTART_COMMITTED_OFFSET=PASS priorCoreAck=%d eventOffset=%d replayOffset=%d%n",
                        beforeGateway.acknowledgedSequence(), gatewayEvents.get(gatewayRange.lastSequence()).offset(),
                        gatewayDuplicate.offset());
                System.out.printf("DETERMINISTIC_EVENT_ID=%s sequence=%d%n",
                        deterministicEventId(gatewayRange.lastSequence()), gatewayRange.lastSequence());

                long afterStateHash = stateHash(core);
                require(afterStateHash != 0L, "Core state hash query did not remain available");
                System.out.printf("CORE_INDEPENDENCE=PASS stateHashBefore=%016x stateHashAfter=%016x "
                                + "orders=%d fundsAuthority=CORE matcherAuthority=CORE%n",
                        initialStateHash, afterStateHash, afterGatewayProjection.orderCount());
                runCoreFinancialGate();
                printMetrics(true);
                printFinalEvidence(before, afterGatewayProjection, observer);
            }
            System.out.println("W5_EXPORT_PROJECTION=PASS delivery=AT_LEAST_ONCE");
        }

        private void runIsolation() throws Exception {
            printHeader("w5-isolation");
            requireRuntimeFiles();
            runCoreFinancialGate();
            try (KafkaObserver observer = new KafkaObserver(kafkaBootstrap, topics.coreEventsTopic(), runId + "-isolation-observer");
                 SurprisingAeronClient core = connectCore()) {
                waitForCore(core);
                PgSnapshot before = requirePg("isolation-before");
                long stateHashBefore = stateHash(core);
                CoreExportStatus beforeStatus = awaitStatus(core, status -> status.pendingCount() == 0,
                        Duration.ofSeconds(20));
                CoreResponseDuringFault coreResponse = proveCoreDuringPostgresPause(core);
                require(coreResponseDuringFault(coreResponse), "Core command did not apply during PostgreSQL pause");
                System.out.printf("CORE_DURING_PG_PAUSE=PASS commandStatus=%s requiredExportSequence=%d stateHash=%016x%n",
                        coreResponse.commandStatus(), coreResponse.requiredExportSequence(), coreResponse.stateHash());
                awaitPgWatermark(coreResponse.requiredExportSequence(), Duration.ofSeconds(20));

                stopOwnedProcess("projector");
                SequenceRange gapRange = generateProbeEvents(core, 3, "isolation-gap");
                Map<Long, KafkaEvent> gapEvents = observer.await(gapRange.sequences(), Duration.ofSeconds(15));
                JdbcCoreEventProjector projector = new JdbcCoreEventProjector(
                        new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
                expectGap(projector, gapEvents.get(gapRange.firstSequence() + 1).message());
                require(projector.project(productLine, gapEvents.get(gapRange.firstSequence()).message()),
                        "isolation first event did not project");
                require(projector.project(productLine, gapEvents.get(gapRange.firstSequence() + 1).message()),
                        "isolation second event did not project");
                require(projector.project(productLine, gapEvents.get(gapRange.lastSequence()).message()),
                        "isolation third event did not project");
                projectionGapRejectionCount++;
                restartOwnedProcess("projector");
                waitForCommitted(projectionGroup(), gapEvents.get(gapRange.lastSequence()).offset() + 1,
                        Duration.ofSeconds(20));
                PgSnapshot afterGap = awaitPgWatermark(gapRange.lastSequence(), Duration.ofSeconds(20));
                System.out.printf("ISOLATION_GAP_REPLAY=PASS offset=%d watermark=%d%n",
                        gapEvents.get(gapRange.lastSequence()).offset() + 1, afterGap.watermark());

                pauseOwnedContainer("postgres");
                SequenceRange pauseRange = generateProbeEvents(core, 1, "isolation-pg-pause");
                Map<Long, KafkaEvent> pauseEvents = observer.await(pauseRange.sequences(), Duration.ofSeconds(15));
                require(awaitPgUnavailable(Duration.ofMillis(1_000)), "isolated PG pause was not observable");
                unpauseOwnedContainer("postgres");
                restartOwnedProcess("projector");
                awaitPgWatermark(pauseRange.lastSequence(), Duration.ofSeconds(20));
                waitForCommitted(projectionGroup(), pauseEvents.get(pauseRange.lastSequence()).offset() + 1,
                        Duration.ofSeconds(20));
                System.out.printf("ISOLATION_PG_RECOVERY=PASS offset=%d watermark=%d%n",
                        pauseEvents.get(pauseRange.lastSequence()).offset() + 1, requirePg("isolation-pg-recovery").watermark());

                stopOwnedProcess("gateway");
                SequenceRange gatewayRange = generateProbeEvents(core, 2, "isolation-gateway-restart");
                Map<Long, KafkaEvent> gatewayEvents = observer.await(gatewayRange.sequences(), Duration.ofSeconds(15));
                restartOwnedProcess("gateway");
                waitForCommitted(websocketGroup(), gatewayEvents.get(gatewayRange.lastSequence()).offset() + 1,
                        Duration.ofSeconds(20));
                KafkaEvent duplicate = publishDuplicate(gatewayEvents.get(gatewayRange.lastSequence()));
                waitForCommitted(websocketGroup(), duplicate.offset() + 1, Duration.ofSeconds(15));
                printGroupOffsets(duplicate.offset() + 1);
                System.out.printf("ISOLATION_GATEWAY_RESTART=PASS eventOffset=%d replayOffset=%d eventId=%s%n",
                        gatewayEvents.get(gatewayRange.lastSequence()).offset(), duplicate.offset(),
                        deterministicEventId(gatewayRange.lastSequence()));
                long stateHashAfter = stateHash(core);
                require(stateHashAfter != 0L, "Core unavailable after isolation faults");
                printMetrics(false);
                System.out.printf("ISOLATION_CORE_STATE=PASS before=%016x after=%016x initialAck=%d%n",
                        stateHashBefore, stateHashAfter, beforeStatus.acknowledgedSequence());
            }
            System.out.println("W5_ISOLATION_RUNTIME=PASS delivery=AT_LEAST_ONCE LIVE_WS=REQUIRED_BY_SCRIPT");
        }

        private CoreResponseDuringFault proveCoreDuringPostgresPause(SurprisingAeronClient core) throws Exception {
            pauseOwnedContainer("postgres");
            try {
                CoreMessage message = command(CoreMessageType.PROBE_INCREMENT, 1L);
                var response = core.submit(message);
                return new CoreResponseDuringFault(response.commandStatus(), response.requiredExportSequence(),
                        response.stateHash());
            } finally {
                unpauseOwnedContainer("postgres");
            }
        }

        private boolean coreResponseDuringFault(CoreResponseDuringFault response) {
            return response.commandStatus() == ResponseStatus.APPLIED || response.commandStatus() == ResponseStatus.DUPLICATE;
        }

        private void runCoreFinancialGate() throws Exception {
            Path toolsJar = Path.of(required("TOOLS_JAR"));
            long seed = 70_000L + Math.floorMod(runId.hashCode(), 100_000L);
            Path output = runDir.resolve("core-financial-gate.log");
            List<String> command = javaCommand(
                    "-Dsurprising.aeron.product-line=" + productLine.name(),
                    "-Dsurprising.aeron.hostnames=" + String.join(",", coreHosts),
                    "-Dsurprising.aeron.egress-hostname=" + coreEgressHost,
                    "-Dsurprising.aeron.smoke-seed=" + seed,
                    "-cp", toolsJar.toString(), "com.surprising.aeron.tools.ClusterProductLineGateMain");
            Files.createDirectories(output.getParent());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            require(process.waitFor(90, TimeUnit.SECONDS), "Core financial gate timed out");
            String raw = Files.readString(output);
            System.out.print(raw);
            require(process.exitValue() == 0 && raw.contains("productLineGate=PASS") && raw.contains("fundsDiff=0"),
                    "real Core perpetual funds/matcher gate failed");
            System.out.printf("CORE_FINANCIAL_GATE=PASS seed=%d artifact=%s%n", seed, output);
        }

        private SequenceRange generateProbeEvents(SurprisingAeronClient core, int count, String reason) {
            require(count > 0, "event count must be positive");
            CoreExportStatus before = exportStatus(core);
            long first = before.nextSequence();
            for (int index = 0; index < count; index++) {
                var response = core.submit(command(CoreMessageType.PROBE_INCREMENT, 1L));
                System.out.printf("CORE_PROBE_RESPONSE reason=%s status=%s commandStatus=%s result=%s "
                                + "requiredExportSequence=%d appliedCommandCount=%d stateHash=%016x%n",
                        reason, response.status(), response.commandStatus(), response.resultCode(),
                        response.requiredExportSequence(), response.appliedCommandCount(), response.stateHash());
                require(response.status() == ResponseStatus.APPLIED
                                && response.commandStatus() == ResponseStatus.APPLIED,
                        "Core probe command was not newly applied reason=" + reason
                                + " status=" + response.status()
                                + " commandStatus=" + response.commandStatus()
                                + " result=" + response.resultCode()
                                + " requiredExportSequence=" + response.requiredExportSequence());
            }
            long last = Math.addExact(first, count - 1L);
            System.out.printf("CORE_EVENT_BATCH=APPLIED reason=%s firstSequence=%d lastSequence=%d count=%d%n",
                    reason, first, last, count);
            CoreExportStatus after = exportStatus(core);
            System.out.printf("CORE_EXPORT_STATUS_AFTER reason=%s acknowledged=%d next=%d pending=%d%n",
                    reason, after.acknowledgedSequence(), after.nextSequence(), after.pendingCount());
            return new SequenceRange(first, last);
        }

        private CoreMessage command(CoreMessageType type, long userId) {
            long sequence = sourceSequence = Math.incrementExact(sourceSequence);
            long now = System.currentTimeMillis();
            UUID commandId = UUID.nameUUIDFromBytes((runId + ":" + type + ":" + sequence)
                    .getBytes(StandardCharsets.UTF_8));
            byte[] payload = type == CoreMessageType.PROBE_INCREMENT
                    ? CoreProtocol.probePayload(1L) : new byte[0];
            return new CoreMessage(CoreMessageHeader.command(type, commandId, productLine, CommandSource.OPERATIONS,
                    sourceId, sequence, userId, now, Math.incrementExact(correlation)), payload);
        }

        private SurprisingAeronClient connectCore() {
            return SurprisingAeronClient.connect(productLine, coreHosts, coreEgressHost,
                    ExporterConfiguration.aeronTimeout());
        }

        private void waitForCore(SurprisingAeronClient core) throws Exception {
            await(() -> {
                try {
                    exportStatus(core);
                    return true;
                } catch (RuntimeException exception) {
                    return false;
                }
            }, Duration.ofSeconds(30), "Core client readiness");
        }

        private CoreExportStatus exportStatus(SurprisingAeronClient core) {
            CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.EXPORT_STATUS_QUERY,
                    UUID.nameUUIDFromBytes((runId + ":export-status:" + Math.incrementExact(correlation))
                            .getBytes(StandardCharsets.UTF_8)), productLine, CommandSource.OPERATIONS, sourceId, 0, 0,
                    System.currentTimeMillis(), Math.incrementExact(correlation)), new byte[0]);
            var response = core.submit(query);
            require(response.status() == ResponseStatus.OK, "Core export status query failed: " + response.resultCode());
            return CoreExportCodec.decodeStatus(response.data());
        }

        private long stateHash(SurprisingAeronClient core) {
            CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.STATE_HASH_QUERY,
                    UUID.nameUUIDFromBytes((runId + ":state-hash:" + Math.incrementExact(correlation))
                            .getBytes(StandardCharsets.UTF_8)), productLine, CommandSource.OPERATIONS, sourceId, 0, 0,
                    System.currentTimeMillis(), Math.incrementExact(correlation)), new byte[0]);
            var response = core.submit(query);
            require(response.status() == ResponseStatus.OK, "Core state hash query failed: " + response.resultCode());
            return response.stateHash();
        }

        private CoreExportStatus awaitStatus(SurprisingAeronClient core,
                                              java.util.function.Predicate<CoreExportStatus> predicate,
                                              Duration timeout) throws Exception {
            final CoreExportStatus[] result = new CoreExportStatus[1];
            await(() -> {
                try {
                    CoreExportStatus status = exportStatus(core);
                    if (predicate.test(status)) {
                        result[0] = status;
                        return true;
                    }
                } catch (RuntimeException ignored) {
                }
                return false;
            }, timeout, "Core export status condition");
            return result[0];
        }

        private PgSnapshot awaitPgWatermark(long expected, Duration timeout) throws Exception {
            final PgSnapshot[] result = new PgSnapshot[1];
            await(() -> {
                try {
                    PgSnapshot snapshot = requirePg("wait-watermark");
                    if (snapshot.watermark() >= expected) {
                        result[0] = snapshot;
                        return true;
                    }
                } catch (RuntimeException ignored) {
                }
                return false;
            }, timeout, "PostgreSQL projection watermark=" + expected);
            return result[0];
        }

        private boolean awaitPgUnavailable(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    requirePg("pause-probe");
                } catch (RuntimeException exception) {
                    return true;
                }
                Thread.sleep(50);
            }
            return false;
        }

        private PgSnapshot requirePg(String label) {
            String sql = "SELECT "
                    + "(SELECT last_export_sequence FROM core_projection_watermark WHERE product_line = ?) AS watermark, "
                    + "(SELECT COUNT(*) FROM core_event_projection WHERE product_line = ?) AS events, "
                    + "(SELECT COUNT(*) FROM core_order_projection WHERE product_line = ?) AS orders, "
                    + "(SELECT COUNT(*) FROM core_user_fact_projection WHERE product_line = ?) AS facts";
            try (Connection connection = DriverManager.getConnection(pgProbeUrl(), databaseUser, databasePassword);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 1; index <= 4; index++) {
                    statement.setString(index, productLine.name());
                }
                try (ResultSet result = statement.executeQuery()) {
                    require(result.next(), "PG snapshot returned no row label=" + label);
                    PgSnapshot snapshot = new PgSnapshot(result.getLong("watermark"), result.getLong("events"),
                            result.getLong("orders"), result.getLong("facts"));
                    System.out.printf("SQL[%s] watermark=%d events=%d orders=%d facts=%d%n",
                            label, snapshot.watermark(), snapshot.eventCount(), snapshot.orderCount(),
                            snapshot.factCount());
                    return snapshot;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("PG snapshot failed label=" + label + " sql=" + sql, exception);
            }
        }

        private String pgProbeUrl() {
            return databaseUrl + (databaseUrl.contains("?") ? '&' : '?')
                    + "connectTimeout=1&socketTimeout=1";
        }

        private void expectGap(JdbcCoreEventProjector projector, CoreMessage event) throws SQLException {
            try {
                projector.project(productLine, event);
            } catch (SQLException expected) {
                require(expected.getMessage().contains("sequence gap")
                                || expected.getMessage().contains("reordered event"),
                        "unexpected reorder/gap rejection: " + expected.getMessage());
                System.out.printf("PROJECTOR_REJECTION=PASS sqlState=%s message=%s%n",
                        expected.getSQLState(), expected.getMessage());
                return;
            }
            throw new IllegalStateException("reordered/gapped event was accepted by the PG projector");
        }

        private KafkaEvent publishDuplicate(KafkaEvent event) throws Exception {
            Properties properties = producerProperties();
            try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(properties)) {
                Future<org.apache.kafka.clients.producer.RecordMetadata> future = producer.send(
                        new ProducerRecord<>(event.topic(), event.partition(), event.key(), event.value()));
                org.apache.kafka.clients.producer.RecordMetadata metadata = future.get(10, TimeUnit.SECONDS);
                KafkaEvent duplicate = new KafkaEvent(event.sequence(), metadata.offset(), event.key(), event.value(),
                        event.message(), event.topic(), metadata.partition());
                printKafka("duplicate-publish", duplicate);
                return duplicate;
            }
        }

        private void waitForCommitted(String group, long expected, Duration timeout) throws Exception {
            await(() -> committedOffset(group) >= expected, timeout,
                    "Kafka committed offset group=" + group + " expected=" + expected);
            System.out.printf("KAFKA_COMMIT=PASS group=%s offset=%d%n", group, committedOffset(group));
        }

        private long committedOffset(String group) {
            TopicPartition partition = new TopicPartition(topics.coreEventsTopic(), 0);
            Properties properties = consumerProperties(kafkaBootstrap, group);
            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
                consumer.assign(List.of(partition));
                var offset = consumer.committed(Set.of(partition)).get(partition);
                return offset == null ? -1L : offset.offset();
            }
        }

        private void printGroupOffsets(long expected) {
            System.out.printf("KAFKA_OFFSETS expectedAtLeast=%d projectionGroup=%s projection=%d websocketGroup=%s websocket=%d%n",
                    expected, projectionGroup(), committedOffset(projectionGroup()), websocketGroup(),
                    committedOffset(websocketGroup()));
        }

        private KafkaObserver observer(String suffix) {
            return new KafkaObserver(kafkaBootstrap, topics.coreEventsTopic(), runId + "-" + suffix);
        }

        private void printKafka(String label, KafkaEvent event) {
            System.out.printf("KAFKA_RECORD label=%s topic=%s partition=%d offset=%d key=%s sequence=%d valueSha256=%s valueBytes=%d%n",
                    label, event.topic(), event.partition(), event.offset(), event.key(), event.sequence(),
                    sha256(event.value()), event.value().length);
        }

        private void printCore(String label, CoreExportStatus status, long hash) {
            System.out.printf("CORE_STATUS label=%s acknowledged=%d next=%d pending=%d pendingBytes=%d stateHash=%016x%n",
                    label, status.acknowledgedSequence(), status.nextSequence(), status.pendingCount(),
                    status.pendingBytes(), hash);
        }

        private void printMetrics(boolean requireExporterFailure) {
            exporterFailureCount += countLog("exporter", "cycle failed", "reconnecting");
            exporterRetryCount = Math.max(exporterRetryCount, exporterFailureCount);
                projectionFailureCount += countLog("projector", "SQLException", "projection", "Connection");
            System.out.printf("METRIC exporter_failure_total=%d exporter_retry_total=%d projector_failure_total=%d "
                            + "projection_rejection_total=%d gateway_rejection_metric=queried_by_LiveSlowClientIsolationTest%n",
                    exporterFailureCount, exporterRetryCount, projectionFailureCount, projectionGapRejectionCount);
            if (requireExporterFailure) {
                require(exporterFailureCount > 0, "exporter failure/retry metric did not move");
            }
            require(projectionGapRejectionCount > 0, "projection rejection metric did not move");
        }

        private void printFinalEvidence(PgSnapshot before, PgSnapshot after, KafkaObserver observer) {
            System.out.printf("EVIDENCE beforeWatermark=%d afterWatermark=%d "
                            + "observedKafkaSequences=%s runDir=%s processDir=%s%n",
                    before.watermark(), after.watermark(),
                    observer.sequences(), runDir, pidDir);
        }

        private void requireRuntimeFiles() {
            require(Files.isDirectory(runDir), "Task 14 run directory is missing: " + runDir);
            require(Files.isDirectory(pidDir), "Task 14 PID directory is missing: " + pidDir);
            require(Files.isDirectory(logDir), "Task 14 log directory is missing: " + logDir);
            require(Files.exists(runDir.resolve("ownership-live.txt")), "live ownership manifest is missing");
        }

        private String projectionGroup() {
            return "surprising-core-projection-" + productLine.topicSegment();
        }

        private String websocketGroup() {
            return topics.consumerGroup("websocket") + "-" + runId;
        }

        private void pauseOwnedContainer(String service) {
            String id = ownedContainer(service);
            runCommand(List.of("docker", "pause", id));
            System.out.printf("PROCESS_FAULT=PAUSE service=%s container=%s runId=%s%n", service, id, runId);
        }

        private void stopOwnedContainer(String service) {
            String id = ownedContainer(service);
            runCommand(List.of("docker", "stop", "--time", "1", id));
            System.out.printf("PROCESS_FAULT=STOP service=%s container=%s runId=%s%n", service, id, runId);
        }

        private void startOwnedContainer(String service) throws InterruptedException {
            String id = ownedContainer(service);
            runCommand(List.of("docker", "start", id));
            await(() -> containerReady(id), Duration.ofSeconds(60), "container recovery service=" + service);
            System.out.printf("PROCESS_FAULT=START service=%s container=%s runId=%s%n", service, id, runId);
        }

        private void awaitKafkaExternal() throws Exception {
            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                    consumerProperties(kafkaBootstrap, runId + "-kafka-readiness"))) {
                await(() -> {
                    try {
                        return !consumer.partitionsFor(topics.coreEventsTopic(), Duration.ofSeconds(1)).isEmpty();
                    } catch (RuntimeException exception) {
                        return false;
                    }
                }, Duration.ofSeconds(60), "Kafka external readiness");
            }
            Thread.sleep(5_000L);
            System.out.printf("KAFKA_EXTERNAL_READY=PASS bootstrap=%s topic=%s%n",
                    kafkaBootstrap, topics.coreEventsTopic());
        }

        private void unpauseOwnedContainer(String service) {
            String id = ownedContainer(service);
            runCommand(List.of("docker", "unpause", id));
            System.out.printf("PROCESS_FAULT=RECOVER service=%s container=%s runId=%s%n", service, id, runId);
        }

        private String ownedContainer(String service) {
            String id = commandOutput(List.of("docker", "ps", "-aq", "--filter", "label=com.docker.compose.project="
                    + composeProject, "--filter", "label=com.docker.compose.service=" + service,
                    "--filter", "label=com.surprising.runtime.run-id=" + runId)).trim();
            require(id.matches("[0-9a-fA-F]{12,64}"), "owned container missing service=" + service);
            String labels = commandOutput(List.of("docker", "inspect", "--format",
                    "{{index .Config.Labels \"com.docker.compose.project\"}} {{index .Config.Labels \"com.surprising.runtime.run-id\"}}",
                    id)).trim();
            require(labels.equals(composeProject + " " + runId), "container ownership mismatch service=" + service);
            return id;
        }

        private boolean containerReady(String id) {
            String state = commandOutput(List.of("docker", "inspect", "--format",
                    "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}", id)).trim();
            return state.equals("running healthy");
        }

        private void restartOwnedProcess(String service) throws Exception {
            stopOwnedProcess(service);
            List<String> command = switch (service) {
                case "projector" -> javaCommand("-cp", exporterJar().toString(),
                        "com.surprising.aeron.exporter.ProjectionMain");
                case "exporter" -> javaCommand("-jar", exporterJar().toString());
                case "gateway" -> javaCommand("-jar", gatewayJar().toString());
                default -> throw new IllegalArgumentException("unsupported restart service=" + service);
            };
            List<String> environment = new ArrayList<>();
            environment.add("PRODUCT_LINE=" + productLine.name());
            environment.add("KAFKA_BOOTSTRAP_SERVERS=" + kafkaBootstrap);
            environment.add("SPRING_KAFKA_BOOTSTRAP_SERVERS=" + kafkaBootstrap);
            environment.add("SURPRISING_KAFKA_BOOTSTRAP_SERVERS=" + kafkaBootstrap);
            environment.add("DATABASE_URL=" + databaseUrl);
            environment.add("DATABASE_USER=" + databaseUser);
            environment.add("DATABASE_PASSWORD=" + databasePassword);
            environment.add("SPRING_DATASOURCE_URL=" + databaseUrl);
            environment.add("SPRING_DATASOURCE_USERNAME=" + databaseUser);
            environment.add("SPRING_DATASOURCE_PASSWORD=" + databasePassword);
            environment.add("AERON_HOSTNAMES=" + String.join(",", coreHosts));
            environment.add("AERON_EGRESS_HOSTNAME=" + coreEgressHost);
            environment.add("SURPRISING_WEBSOCKET_GROUP_ID=" + runId);
            environment.add("SERVER_PORT=9094");
            if (service.equals("gateway")) {
                environment.add("SURPRISING_WEBSOCKET_SESSION_OUTBOUND_QUEUE_CAPACITY="
                        + env("SURPRISING_WEBSOCKET_SESSION_OUTBOUND_QUEUE_CAPACITY", "2"));
                environment.add("SURPRISING_WEBSOCKET_SESSION_SEND_TIMEOUT="
                        + env("SURPRISING_WEBSOCKET_SESSION_SEND_TIMEOUT", "200ms"));
            }
            startOwnedProcess(service, command, environment);
            if (service.equals("gateway")) {
                await(() -> httpStatus(gatewayUrl + "/actuator/health") < 500, Duration.ofSeconds(45),
                        "Gateway restart readiness");
            } else {
                await(() -> processAlive(service), Duration.ofSeconds(10), service + " restart readiness");
            }
            System.out.printf("PROCESS_RESTART=PASS service=%s pid=%s log=%s%n",
                    service, readPid(service), logDir.resolve(service + ".log"));
        }

        private void startControlledExporter(Path barrierPath) throws Exception {
            List<String> command = javaCommand("-cp", exporterJar().toString(),
                    W5FaultQaMain.class.getName(), "controlled-exporter");
            List<String> environment = List.of(
                    "PRODUCT_LINE=" + productLine.name(),
                    "KAFKA_BOOTSTRAP_SERVERS=" + kafkaBootstrap,
                    "AERON_HOSTNAMES=" + String.join(",", coreHosts),
                    "AERON_EGRESS_HOSTNAME=" + coreEgressHost,
                    "AERON_RESPONSE_TIMEOUT_MS=8000",
                    "EXPORT_BATCH_SIZE=1",
                    "RUN_ID=" + runId,
                    "RUNTIME_ROOT=" + runtimeRoot,
                    "W5_EXPORT_BARRIER_PATH=" + barrierPath);
            startOwnedProcess("controlled-exporter", command, environment);
            await(() -> processAlive("controlled-exporter"), Duration.ofSeconds(10),
                    "controlled exporter process readiness");
            System.out.printf("CONTROLLED_EXPORTER_START=PASS pid=%d marker=%s log=%s%n",
                    readPid("controlled-exporter"), barrierPath, logDir.resolve("controlled-exporter.log"));
        }

        private void crashOwnedProcess(String service, String commandPart) throws Exception {
            long wrapperPid = readPid(service);
            ProcessHandle wrapper = ProcessHandle.of(wrapperPid).orElseThrow(
                    () -> new IllegalStateException("owned process wrapper is missing service=" + service));
            String wrapperCommand = wrapper.info().commandLine().orElse("");
            require(wrapper.isAlive() && wrapperCommand.contains("surprising-w3w5:" + runId + ":" + service),
                    "refusing to crash unowned process service=" + service + " pid=" + wrapperPid);
            long childPid = childPid(service, commandPart);
            signal(childPid, "KILL");
            await(() -> !ProcessHandle.of(wrapperPid).map(ProcessHandle::isAlive).orElse(false),
                    Duration.ofSeconds(15), "process crash service=" + service);
            Files.deleteIfExists(pidDir.resolve(service + ".pid"));
            System.out.printf("PROCESS_CRASH=PASS service=%s wrapperPid=%d childPid=%d runId=%s%n",
                    service, wrapperPid, childPid, runId);
        }

        private void stopOwnedProcess(String service) throws Exception {
            Path pidFile = pidDir.resolve(service + ".pid");
            if (!Files.exists(pidFile)) {
                return;
            }
            long pid = readPid(service);
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle == null || !handle.isAlive()) {
                Files.deleteIfExists(pidFile);
                return;
            }
            String command = handle.info().commandLine().orElse("");
            require(command.contains("surprising-w3w5:" + runId + ":" + service),
                    "refusing to stop unowned process service=" + service + " pid=" + pid);
            signal(pid, "TERM");
            await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), Duration.ofSeconds(15),
                    "process stop service=" + service);
            Files.deleteIfExists(pidFile);
            System.out.printf("PROCESS_STOP=PASS service=%s pid=%d%n", service, pid);
        }

        private boolean processAlive(String service) {
            try {
                return ProcessHandle.of(readPid(service)).map(ProcessHandle::isAlive).orElse(false);
            } catch (RuntimeException | IOException exception) {
                return false;
            }
        }

        private long childPid(String service, String commandPart) throws IOException {
            long wrapper = readPid(service);
            ProcessHandle parent = ProcessHandle.of(wrapper).orElseThrow(
                    () -> new IllegalStateException("owned process wrapper is missing service=" + service));
            Queue<ProcessHandle> pending = new ArrayDeque<>();
            parent.children().forEach(pending::add);
            while (!pending.isEmpty()) {
                ProcessHandle candidate = pending.remove();
                if (candidate.info().commandLine().orElse("").contains(commandPart)) {
                    return candidate.pid();
                }
                candidate.children().forEach(pending::add);
            }
            throw new IllegalStateException("child process not found service=" + service + " commandPart=" + commandPart);
        }

        private void startOwnedProcess(String service, List<String> command, List<String> environment) throws IOException {
            Files.createDirectories(pidDir);
            List<String> process = new ArrayList<>(List.of("bash", "-c", WRAPPER_SCRIPT,
                    "surprising-w3w5:" + runId + ":" + service, "env"));
            process.addAll(environment);
            process.addAll(command);
            Path log = logDir.resolve(service + ".log");
            Process child = new ProcessBuilder(process)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
            Files.writeString(pidDir.resolve(service + ".pid"), Long.toString(child.pid()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        private long readPid(String service) throws IOException {
            return Long.parseLong(Files.readString(pidDir.resolve(service + ".pid")).trim());
        }

        private Path exporterJar() {
            return Path.of(env("EXPORTER_JAR", repoRoot.resolve(
                    "surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar").toString()));
        }

        private Path gatewayJar() {
            return Path.of(env("GATEWAY_JAR", repoRoot.resolve(
                    "surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT.jar").toString()));
        }

        private int countLog(String service, String... needles) {
            try {
                Path log = logDir.resolve(service + ".log");
                if (!Files.exists(log)) {
                    return 0;
                }
                int count = 0;
                for (String line : Files.readAllLines(log)) {
                    for (String needle : needles) {
                        if (line.contains(needle)) {
                            count++;
                            break;
                        }
                    }
                }
                return count;
            } catch (IOException exception) {
                throw new IllegalStateException("cannot read process log service=" + service, exception);
            }
        }

        private void waitForLog(String service, Duration timeout, String... needles) throws InterruptedException {
            await(() -> countLog(service, needles) > 0, timeout,
                    "log evidence service=" + service + " needles=" + String.join(",", needles));
        }

        private int httpStatus(String url) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
                return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (Exception exception) {
                return 599;
            }
        }

        private void signal(long pid, String signal) {
            runCommand(List.of("kill", "-" + signal, Long.toString(pid)));
            System.out.printf("PROCESS_SIGNAL=PASS pid=%d signal=%s runId=%s%n", pid, signal, runId);
        }

        private void await(BooleanSupplier condition, Duration timeout, String description) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("timed out waiting for " + description);
        }

        private static void printHeader(String scenario) {
            System.out.printf("W5_SCENARIO_START=%s productLine=%s delivery=AT_LEAST_ONCE h2=ABSENT mocks=ABSENT%n",
                    scenario, PRODUCT_LINE_NAME);
        }
    }

    private static final class KafkaObserver implements AutoCloseable {

        private final String topic;
        private final KafkaConsumer<String, byte[]> consumer;
        private final Map<Long, KafkaEvent> events = new LinkedHashMap<>();

        private KafkaObserver(String bootstrap, String topic, String groupId) {
            this.topic = topic;
            this.consumer = new KafkaConsumer<>(consumerProperties(bootstrap, groupId));
            this.consumer.assign(List.of(new TopicPartition(topic, 0)));
            this.consumer.seekToBeginning(List.of(new TopicPartition(topic, 0)));
        }

        private Map<Long, KafkaEvent> await(Collection<Long> sequences, Duration timeout) {
            Set<Long> required = Set.copyOf(sequences);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && !events.keySet().containsAll(required)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    KafkaEvent event = event(record);
                    events.putIfAbsent(event.sequence(), event);
                }
            }
            if (!events.keySet().containsAll(required)) {
                Set<Long> missing = new HashSet<>(required);
                missing.removeAll(events.keySet());
                throw new IllegalStateException("Kafka records missing topic=" + topic + " sequences=" + missing);
            }
            return required.stream().collect(Collectors.toMap(sequence -> sequence, events::get,
                    (left, right) -> left, LinkedHashMap::new));
        }

        private KafkaEvent awaitReplay(long sequence, long afterOffset, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    KafkaEvent event = event(record);
                    events.putIfAbsent(event.sequence(), event);
                    if (event.sequence() == sequence && event.offset() > afterOffset) {
                        return event;
                    }
                }
            }
            throw new IllegalStateException("Kafka replay missing topic=" + topic + " sequence=" + sequence
                    + " afterOffset=" + afterOffset);
        }

        private KafkaEvent event(ConsumerRecord<String, byte[]> record) {
            CoreMessage message = CoreMessageCodec.decode(record.value());
            CoreExportEvent event = CoreExportCodec.decodeEvent(message, ProductLine.LINEAR_PERPETUAL);
            return new KafkaEvent(event.exportSequence(), record.offset(), record.key(), record.value(), message,
                    record.topic(), record.partition());
        }

        private Set<Long> sequences() {
            return Collections.unmodifiableSet(events.keySet());
        }

        @Override
        public void close() {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    private record KafkaEvent(long sequence, long offset, String key, byte[] value, CoreMessage message,
                              String topic, int partition) {
        private KafkaEvent {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    private record SequenceRange(long firstSequence, long lastSequence) {
        private SequenceRange {
            require(firstSequence > 0 && lastSequence >= firstSequence, "invalid sequence range");
        }

        private Set<Long> sequences() {
            Set<Long> values = new HashSet<>();
            for (long sequence = firstSequence; sequence <= lastSequence; sequence++) {
                values.add(sequence);
            }
            return values;
        }
    }

    private record PgSnapshot(long watermark, long eventCount, long orderCount, long factCount) {
    }

    private record CoreResponseDuringFault(ResponseStatus commandStatus, long requiredExportSequence,
                                           long stateHash) {
    }

    private static Properties consumerProperties(String bootstrap, String groupId) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        return properties;
    }

    private static Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, required("KAFKA_BOOTSTRAP_SERVERS"));
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "w5-fault-driver-" + required("RUN_ID"));
        return properties;
    }

    private static String deterministicEventId(long sequence) {
        UUID id = UUID.nameUUIDFromBytes((PRODUCT_LINE_NAME + ':' + sequence).getBytes(StandardCharsets.UTF_8));
        return id.toString();
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static List<String> javaCommand(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED");
        command.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
        command.addAll(List.of(arguments));
        return command;
    }

    private static List<String> splitHosts(String value) {
        List<String> hosts = List.of(value.split(",")).stream().map(String::trim).filter(host -> !host.isBlank()).toList();
        require(hosts.size() == 3, "AERON_HOSTNAMES must contain three hosts");
        return hosts;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required environment variable " + name);
        }
        return value.trim();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void runCommand(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(process.waitFor(15, TimeUnit.SECONDS), "command timeout: " + command.getFirst());
            require(process.exitValue() == 0, "command failed: " + String.join(" ", command) + " output=" + output);
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("command failed: " + String.join(" ", command), exception);
        }
    }

    private static String commandOutput(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(process.waitFor(15, TimeUnit.SECONDS), "command timeout: " + command.getFirst());
            require(process.exitValue() == 0, "command failed: " + String.join(" ", command) + " output=" + output);
            return output;
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("command failed: " + String.join(" ", command), exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
