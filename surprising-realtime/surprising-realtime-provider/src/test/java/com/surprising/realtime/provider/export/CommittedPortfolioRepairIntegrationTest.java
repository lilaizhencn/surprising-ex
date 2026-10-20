package com.surprising.realtime.provider.export;

import static org.junit.jupiter.api.Assertions.*;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.PortfolioIncomePeriod;
import io.aeron.*;
import io.aeron.archive.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.service.ClusterCounters;
import io.aeron.driver.*;
import java.math.BigDecimal;
import java.net.DatagramSocket;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedPortfolioRepairIntegrationTest {
    @TempDir Path temp;
    private static final ProductLine PRODUCT = ProductLine.SPOT;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T04:05:30Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private CommittedOrderProjectionRepository repository;
    private String schema;
    private long sequence;

    @BeforeEach void prepare() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "portfolio_archive_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("deployment/migrations/20261020-portfolio-income.sql"))) root = root.getParent();
        assertNotNull(root);
        jdbc.execute(Files.readString(root.resolve("deployment/migrations/20261020-portfolio-income.sql")));
        // No order, statistics or Kafka tables exist in this schema. Repair must write only financial history.
        repository = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
    }

    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void committedArchiveRebuildsOpeningAndMinuteEquityAndRestartsAfterSqlFailureWithoutLeakingUncommittedCash() throws Exception {
        String directory = temp.resolve("driver").toString();
        int port;
        try (var socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        String control = "aeron:udp?endpoint=127.0.0.1:" + port;
        Path cluster = temp.resolve("cluster"), checkpoint = temp.resolve("history.portfolio-repair");
        Files.createDirectories(cluster);
        var config = new TradeExportProperties(cluster, directory, control, checkpoint, 0, null, null, null);
        var exporter = new CommittedTradeExporter(config, PRODUCT, null, repository, CLOCK);
        long opening = PortfolioIncomePeriod.dayStart(CLOCK.millis()), minute = PortfolioIncomePeriod.minuteStart(CLOCK.millis());
        try (var driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory).dirDeleteOnShutdown(true).threadingMode(ThreadingMode.SHARED));
             var service = Archive.launch(new Archive.Context().aeronDirectoryName(directory).archiveDir(temp.resolve("archive").toFile())
                     .controlChannel(control).replicationChannel("aeron:udp?endpoint=127.0.0.1:0").threadingMode(ArchiveThreadingMode.SHARED));
             var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory));
             var archive = AeronArchive.connect(new AeronArchive.Context().aeron(aeron).ownsAeronClient(false)
                     .controlRequestChannel(control).controlResponseChannel("aeron:ipc"));
             var commit = ClusterCounters.allocate(aeron, new UnsafeBuffer(new byte[1024]), "portfolio commit",
                     AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID, 0)) {
            archive.startRecording("aeron:ipc", 1001, SourceLocation.LOCAL);
            try (var pub = aeron.addExclusivePublication("aeron:ipc?mtu=128", 1001)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int recordingCounter;
                while ((recordingCounter = RecordingPos.findCounterIdBySession(aeron.countersReader(), pub.sessionId())) < 0) {
                    check(deadline); Thread.sleep(1);
                }
                long recording = RecordingPos.getRecordingId(aeron.countersReader(), recordingCounter);
                try (var log = new RecordingLog(cluster.toFile(), true)) { log.appendTerm(recording, 1, 0, opening - 1000); log.force(2); }
                long before = offer(pub, opening - 1000, 100);
                long deposit = offer(pub, opening + 1000, 20);
                long through = offer(pub, minute + 1, 0);
                long uncommitted = offer(pub, minute + 2000, 999);
                while (aeron.countersReader().getCounterValue(recordingCounter) < uncommitted) { check(deadline); Thread.sleep(1); }

                commit.set(before);
                exporter.repairPortfolio(before);
                byte[] seed = Files.readAllBytes(checkpoint);
                assertEquals(1, count("core_portfolio_cash_flow"));
                assertFalse(repository.portfolio().initialized(PRODUCT));
                jdbc.execute("ALTER TABLE core_portfolio_cash_flow ADD CONSTRAINT fail_next CHECK(cluster_position<=" + before + ")");
                commit.set(through);
                assertThrows(org.springframework.dao.DataAccessException.class, () -> exporter.repairPortfolio(through));
                assertArrayEquals(seed, Files.readAllBytes(checkpoint));
                assertFalse(repository.portfolio().contains(PRODUCT, minute));
                assertEquals(1, count("core_portfolio_cash_flow"));
                jdbc.execute("ALTER TABLE core_portfolio_cash_flow DROP CONSTRAINT fail_next");

                exporter.repairPortfolio(through);
                assertEquals(through, TradeExportCheckpoint.read(checkpoint, PRODUCT).logPosition());
                assertEquals(2, count("core_portfolio_cash_flow"));
                assertEquals("100", units(opening));
                assertEquals("120", units(minute));
                assertEquals(deposit, jdbc.queryForObject("SELECT cluster_position FROM core_portfolio_cutoff WHERE cutoff_epoch_ms=?", Long.class, minute));
                assertEquals(0, jdbc.queryForObject("SELECT amount_units FROM core_portfolio_cash_flow WHERE cluster_position=?", BigDecimal.class, deposit).compareTo(new BigDecimal("20")));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM core_portfolio_cash_flow WHERE amount_units=999", Integer.class));
                exporter.repairPortfolio(through);
                assertEquals(2, count("core_portfolio_cash_flow"));

                Path live = temp.resolve("live.bin"), renamed = temp.resolve("renamed.portfolio-repair");
                TradeExportCheckpoint.read(checkpoint, PRODUCT).write(live);
                Files.copy(live, renamed);
                var wrongPurpose = new TradeExportProperties(cluster, directory, control, renamed, 0, null, null, null);
                var failure = assertThrows(java.io.IOException.class,
                        () -> new CommittedTradeExporter(wrongPurpose, PRODUCT, null, repository, CLOCK).repairPortfolio(through));
                assertTrue(failure.getMessage().contains("checkpoint purpose"));
                assertThrows(IllegalArgumentException.class, () -> exporter.repairPortfolio(Long.MAX_VALUE));
                assertFalse(repository.portfolio().initialized(PRODUCT), "A finite repair alone cannot certify an unrelated live checkpoint");

                // An existing live checkpoint requires a complete financial rebuild before
                // normal export resumes. Stop afterward at an intentionally invalid Kafka
                // configuration, proving readiness does not depend on market/order SQL tables.
                var liveRepository = org.mockito.Mockito.spy(repository);
                org.mockito.Mockito.doReturn(true).when(liveRepository).executionsInitialized(org.mockito.ArgumentMatchers.any());
                var statistics = org.mockito.Mockito.mock(CommittedTradingStatistics.class);
                org.mockito.Mockito.when(statistics.initialized(org.mockito.ArgumentMatchers.any())).thenReturn(true);
                org.mockito.Mockito.doReturn(statistics).when(liveRepository).statistics();
                var liveConfig = new TradeExportProperties(cluster, directory, control, live, 0, null, null, null);
                assertThrows(org.apache.kafka.common.KafkaException.class,
                        () -> new CommittedTradeExporter(liveConfig, PRODUCT, "invalid-bootstrap", liveRepository, CLOCK)
                                .run(new java.util.concurrent.atomic.AtomicBoolean(true), connected -> {}, through));
                assertTrue(repository.portfolio().initialized(PRODUCT));
                assertFalse(Files.exists(live.resolveSibling(live.getFileName() + ".portfolio-repair")));
                assertEquals(2, count("core_portfolio_cash_flow"));
                assertEquals("120", units(minute));
            }
        }
    }

    private String units(long cutoff) {
        return jdbc.queryForObject("SELECT net_asset_units->>'USDT' FROM core_portfolio_equity WHERE user_id=7 AND cutoff_epoch_ms=?", String.class, cutoff);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private long offer(ExclusivePublication publication, long timestamp, long amount) throws Exception {
        long id = ++sequence;
        var command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, new UUID(73, id), PRODUCT,
                CommandSource.OPERATIONS, 73, id, 7, timestamp, id), TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", amount)));
        byte[] encoded = CoreMessageCodec.encode(command);
        int offset = MessageHeaderEncoder.ENCODED_LENGTH + SessionMessageHeaderEncoder.BLOCK_LENGTH;
        var buffer = new UnsafeBuffer(new byte[offset + encoded.length]);
        new SessionMessageHeaderEncoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder()).leadershipTermId(1).clusterSessionId(7).timestamp(timestamp);
        buffer.putBytes(offset, encoded);
        long result, deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((result = publication.offer(buffer)) < 0) { check(deadline); Thread.sleep(1); }
        return result;
    }
    private static void check(long deadline) { if (System.nanoTime() > deadline) throw new AssertionError("portfolio archive fixture timed out"); }
}
