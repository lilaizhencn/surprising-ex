package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.AdminCursorPage;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.repository.RiskOutboxRepository;
import com.surprising.risk.provider.repository.RiskOutboxRepository.PendingRiskOutboxEvent;
import com.surprising.risk.provider.repository.RiskRepository;
import com.surprising.risk.provider.repository.RiskRepository.HighRiskAccount;
import com.surprising.risk.provider.repository.RiskRepository.LiquidationCandidateWrite;
import com.surprising.risk.provider.repository.RiskRepository.PositionSnapshotWrite;
import com.surprising.risk.provider.repository.RiskRepository.RiskRuleOverride;
import com.surprising.risk.provider.repository.RiskSequenceRepository;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.Comparator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class RiskServiceTest {

    @Test
    void scanRollsBackFailedRiskGroupBeforeOutbox() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.failCandidateBatch = true;
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        RiskService service = redisRiskService(new RiskProperties(), riskRepository, outboxRepository, kafka,
                transactionManager);

        assertThatThrownBy(service::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate batch failed");

        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.savedPositions).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isZero();
        verifyNoInteractions(kafka);
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isEqualTo(1);
    }

    @Test
    void scanDoesNothingWhenCalculationIsDisabled() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskProperties properties = new RiskProperties();
        properties.getCalculation().setEnabled(false);
        RiskService service = redisRiskService(properties, riskRepository, outboxRepository, null,
                transactionManager);

        service.scan();

        assertThat(riskRepository.riskGroupCalls).isZero();
        assertThat(riskRepository.calculateCalls).isZero();
        assertThat(riskRepository.savedAccounts).isZero();
        assertThat(riskRepository.savedPositions).isZero();
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void positionEventBatchCoalescesRiskGroupAndKeepsLatestExactPositionRevision() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of();
        riskRepository.walletBalanceUnits = 1_000_000L;
        RiskProperties properties = new RiskProperties();
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        RiskService service = redisRiskService(properties, riskRepository, outboxRepository, kafka,
                transactionManager);

        service.scanPositionUpdates(List.of(
                positionEvent(31L, 2002L, "btc-usdt", 7L, "USDT", "trace-old"),
                positionEvent(33L, 2002L, "BTC-USDT", 8L, "usdt", "trace-latest"),
                positionEvent(32L, 2002L, "eth-usdt", 9L, "USDT", "trace-eth")));

        assertThat(riskRepository.riskGroupCalls).isZero();
        assertThat(riskRepository.calculateCalls).isEqualTo(1);
        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.savedPositions).isEqualTo(2);
        assertThat(riskRepository.savedPositionSnapshots)
                .extracting(CalculatedPositionRisk::symbol, CalculatedPositionRisk::instrumentVersion)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("BTC-USDT", 8L),
                        org.assertj.core.groups.Tuple.tuple("ETH-USDT", 9L));
        assertThat(outboxRepository.enqueued).isZero();
        verify(kafka).send(eq(properties.getKafka().getAccountRiskEventsTopic()),
                eq("2002:USDT_PERPETUAL:USDT"), contains("\"traceId\":\"trace-latest\""));
        verify(kafka).send(eq(properties.getKafka().getPositionRiskEventsTopic()), eq("BTC-USDT"),
                argThat(payload -> payload.contains("\"instrumentVersion\":8")
                        && payload.contains("\"productLine\":\"LINEAR_PERPETUAL\"")));
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void positionEventBatchScansDifferentRiskGroupsIndependently() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of();
        RiskProperties properties = new RiskProperties();
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = redisRiskService(properties, riskRepository, outboxRepository, null,
                transactionManager);

        service.scanPositionUpdates(List.of(
                positionEvent(31L, 1001L, "BTC-USDT", 7L, "USDT", "trace-usdt"),
                positionEvent(32L, 1001L, "BTC-USDC", 8L, "USDC", "trace-usdc")));

        assertThat(riskRepository.calculateCalls).isEqualTo(2);
        assertThat(riskRepository.savedAccounts).isEqualTo(2);
        assertThat(riskRepository.savedPositions).isEqualTo(2);
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isEqualTo(1);
    }

    @Test
    void positionEventBatchDoesNothingWhenCalculationIsDisabled() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        RiskProperties properties = new RiskProperties();
        properties.getCalculation().setEnabled(false);
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scanPositionUpdates(List.of(
                positionEvent(31L, 1001L, "BTC-USDT", 7L, "USDT", "trace-1")));

        assertThat(riskRepository.calculateCalls).isZero();
        assertThat(riskRepository.savedAccounts).isZero();
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isZero();
    }

    @Test
    void positionEventBatchUsesRedisProjectionWithoutDatabaseLease() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of();
        RiskProperties properties = new RiskProperties();
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = redisRiskService(properties, riskRepository, outboxRepository, null,
                transactionManager);

        service.scanPositionUpdates(List.of(
                positionEvent(31L, 1001L, "BTC-USDT", 7L, "USDT", "trace-1")));

        assertThat(riskRepository.scanLeaseAttempts).isZero();
        assertThat(riskRepository.calculateCalls).isEqualTo(1);
        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isEqualTo(1);
    }

    @Test
    void positionEventBatchWritesFlatTombstoneFromCompleteRedisProjection() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of();
        RiskProperties properties = new RiskProperties();
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = redisRiskService(properties, riskRepository, outboxRepository, null,
                transactionManager);

        service.scanPositionUpdates(List.of(
                positionEvent(31L, 1001L, "BTC-USDT", 7L, "USDT", "trace-1")));

        assertThat(riskRepository.calculateCalls).isEqualTo(1);
        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.savedPositions).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void positionEventBatchRejectsIncompleteProjectionFields() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        RiskService service = new RiskService(new ObjectMapper(), new RiskProperties(), riskRepository,
                new FakeRiskSequenceRepository(), new FakeRiskOutboxRepository(), new TrackingTransactionManager());

        assertThatThrownBy(() -> service.scanPositionUpdates(List.of(
                positionEvent(31L, 1001L, "BTC-USDT", 7L, "", "trace-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asset is required");
        assertThat(riskRepository.calculateCalls).isZero();
    }

    @Test
    void latestAccountDefaultsToProviderProductLineWhenProductTopicsAreEnabled() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), new FakeRiskOutboxRepository(), new TrackingTransactionManager());

        RiskAccountSnapshotResponse response = service.latestAccount(1001L, "USDT");

        assertThat(response.accountType()).isEqualTo("USDT_DELIVERY");
        assertThat(riskRepository.lastLatestAccountType).isEqualTo("USDT_DELIVERY");
        assertThat(riskRepository.lastLatestAccountSettleAsset).isEqualTo("USDT");
    }

    @Test
    void latestAccountRejectsOtherAccountTypeWhenProviderIsProductScoped() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.OPTION);
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), new FakeRiskOutboxRepository(), new TrackingTransactionManager());

        assertThatThrownBy(() -> service.latestAccount(1001L, "USDT_DELIVERY", "USDT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountType must match current product line account");
        assertThat(riskRepository.lastLatestAccountType).isNull();
    }

    @Test
    void scanPublishesOutboxEventWhenCandidateIsInsertedAndReadable() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of(new CalculatedPositionRisk(1001L, "BTC-USDT",
                MarginMode.CROSS, PositionSide.SHORT, 7L, "USDT", -10L, 65_000L, 60_000L,
                600_000L, -100L, 100L, 0L));
        riskRepository.returnInsertedCandidate = true;
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = redisRiskService(new RiskProperties(), riskRepository, outboxRepository, null,
                transactionManager);

        service.scan();

        assertThat(outboxRepository.enqueued).isEqualTo(1);
        assertThat(outboxRepository.eventTypes).containsExactly("LIQUIDATION_CANDIDATE");
        assertThat(outboxRepository.topic).isEqualTo("surprising.perp.liquidation.candidates.v1");
        assertThat(outboxRepository.eventKey).isEqualTo("BTC-USDT");
        assertThat(outboxRepository.eventType).isEqualTo("LIQUIDATION_CANDIDATE");
        assertThat(outboxRepository.payloads.get(0)).contains("\"positionSide\":\"SHORT\"");
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void scanProjectsAndEvaluatesWithoutDatabaseLease() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.walletBalanceUnits = 1_000_000L;
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskProperties properties = new RiskProperties();
        RiskService service = redisRiskService(properties, riskRepository, outboxRepository, null,
                transactionManager);

        service.scan();

        assertThat(riskRepository.riskGroupCalls).isEqualTo(1);
        assertThat(riskRepository.calculateCalls).isEqualTo(1);
        assertThat(riskRepository.scanLeaseAttempts).isZero();
        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.savedPositions).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void scanBypassesRiskGroupLeaseWhenCoordinationIsDisabled() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.returnInsertedCandidate = true;
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskProperties properties = new RiskProperties();
        RiskService service = redisRiskService(properties, riskRepository, outboxRepository, null,
                transactionManager);

        service.scan();

        assertThat(riskRepository.scanLeaseAttempts).isZero();
        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.savedPositions).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isEqualTo(1);
    }

    @Test
    void scanFailsClosedWhenOneRedisBatchGroupFails() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of(
                new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                        10L, 65_000L, 60_000L, 600_000L, -100L, 100L),
                new CalculatedPositionRisk(2002L, "ETH-USDT", 7L, "USDT",
                        10L, 3_500L, 3_000L, 30_000L, -100L, 100L));
        riskRepository.returnInsertedCandidate = true;
        riskRepository.failedCandidateSymbols = Set.of("BTC-USDT");
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = redisRiskService(new RiskProperties(), riskRepository, outboxRepository, null,
                transactionManager);

        assertThatThrownBy(service::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate batch failed");

        assertThat(riskRepository.savedAccounts).isEqualTo(2);
        assertThat(riskRepository.savedPositions).isEqualTo(2);
        assertThat(outboxRepository.candidateEventKeys()).isEmpty();
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isEqualTo(1);
    }

    @Test
    void scanRejectsMaintenanceAggregateOverflowBeforeStartingTransaction() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of(
                new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                        10L, 65_000L, 60_000L, 600_000L, 0L, Long.MAX_VALUE),
                new CalculatedPositionRisk(1001L, "ETH-USDT", 7L, "USDT",
                        10L, 3_500L, 3_000L, 30_000L, 0L, 1L));
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = redisRiskService(new RiskProperties(), riskRepository, outboxRepository, null,
                transactionManager);

        assertThatThrownBy(service::scan).isInstanceOf(ArithmeticException.class);

        assertThat(riskRepository.savedAccounts).isZero();
        assertThat(riskRepository.savedPositions).isZero();
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void scanPaginatesRiskGroupsByConfiguredBatchSize() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of(
                new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                        10L, 65_000L, 60_000L, 600_000L, -100L, 100L),
                new CalculatedPositionRisk(2002L, "ETH-USDT", 7L, "USDT",
                        10L, 3_500L, 3_000L, 30_000L, -100L, 100L));
        riskRepository.returnInsertedCandidate = true;
        RiskProperties properties = new RiskProperties();
        properties.getCalculation().setScanBatchSize(1);
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = redisRiskService(properties, riskRepository, outboxRepository, null,
                transactionManager);

        service.scan();
        service.scan();
        service.scan();

        assertThat(riskRepository.riskGroupCalls).isEqualTo(3);
        assertThat(riskRepository.calculateCalls).isEqualTo(2);
        assertThat(riskRepository.riskGroupLimits).containsExactly(1, 1, 1);
        assertThat(riskRepository.savedAccounts).isEqualTo(2);
        assertThat(riskRepository.savedPositions).isEqualTo(2);
        assertThat(transactionManager.commits).isEqualTo(2);
    }

    @Test
    void updatesMarginRiskRuleAndRuntimeThresholdsWithAuditMetadata() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        RiskProperties properties = new RiskProperties();
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), new FakeRiskOutboxRepository(), new TrackingTransactionManager());

        RiskService.RiskRuleResponse response = service.updateRiskRule("global_margin_policy", " admin-risk ",
                new RiskService.RiskRuleUpdateCommand("Margin policy", true, 700_000L,
                        950_000L, null, null, "lower warning threshold"));

        assertThat(properties.getCalculation().getWarningMarginRatioPpm()).isEqualTo(700_000L);
        assertThat(properties.getCalculation().getLiquidationMarginRatioPpm()).isEqualTo(950_000L);
        assertThat(response.ruleCode()).isEqualTo("GLOBAL_MARGIN_POLICY");
        assertThat(response.ruleType()).isEqualTo("GLOBAL_MARGIN");
        assertThat(response.source()).isEqualTo("override");
        assertThat(response.adminUserId()).isEqualTo("admin-risk");
        assertThat(response.reason()).isEqualTo("lower warning threshold");
        assertThat(riskRepository.ruleOverrides).singleElement().satisfies(rule -> {
            assertThat(rule.warningMarginRatioPpm()).isEqualTo(700_000L);
            assertThat(rule.liquidationMarginRatioPpm()).isEqualTo(950_000L);
        });
    }

    @Test
    void returnsHighRiskAccountAggregationUsingWarningThresholdByDefault() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.highRiskRows = List.of(new HighRiskAccount(901L, 1001L, "USDT_PERPETUAL", "USDT",
                1_000_000L, -300_000L, 700_000L, 600_000L, 857_142L,
                RiskStatus.WARNING, Instant.parse("2026-07-01T00:00:00Z"), 2, 1,
                0, "BTC-USDT", MarginMode.CROSS, 900_000L, RiskStatus.WARNING, "WARNING"));
        RiskProperties properties = new RiskProperties();
        properties.getCalculation().setWarningMarginRatioPpm(750_000L);
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), new FakeRiskOutboxRepository(), new TrackingTransactionManager());

        RiskService.HighRiskAccountsResponse response = service.highRiskAccounts(null, 50);

        assertThat(riskRepository.lastHighRiskThreshold).isEqualTo(750_000L);
        assertThat(riskRepository.lastHighRiskLimit).isEqualTo(50);
        assertThat(response.accountCount()).isEqualTo(1);
        assertThat(response.warningCount()).isEqualTo(1);
        assertThat(response.liquidationCount()).isZero();
        assertThat(response.accounts()).singleElement().satisfies(account -> {
            assertThat(account.userId()).isEqualTo(1001L);
            assertThat(account.accountType()).isEqualTo("USDT_PERPETUAL");
            assertThat(account.topSymbol()).isEqualTo("BTC-USDT");
            assertThat(account.riskLevel()).isEqualTo("WARNING");
        });
    }

    @Test
    void adminRiskQueriesExposeCursorMetadata() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.candidateRows = List.of(new LiquidationCandidateResponse(9401L, 9301L, 2002L,
                "BTC-USDT", 8L, "USDT", 10L, 590_000L, -200_000_000L,
                88_500_000L, 1_100_000L, LiquidationCandidateStatus.NEW,
                Instant.parse("2026-07-01T00:00:00Z")));
        riskRepository.highRiskRows = List.of(new HighRiskAccount(901L, 1001L, "USDT_PERPETUAL", "USDT",
                1_000_000L, -300_000L, 700_000L, 600_000L, 857_142L,
                RiskStatus.WARNING, Instant.parse("2026-07-01T00:00:00Z"), 2, 1,
                0, "BTC-USDT", MarginMode.CROSS, 900_000L, RiskStatus.WARNING, "WARNING"));
        RiskService service = new RiskService(new ObjectMapper(), new RiskProperties(), riskRepository,
                new FakeRiskSequenceRepository(), new FakeRiskOutboxRepository(), new TrackingTransactionManager());

        var candidates = service.liquidationCandidates("new", 25, "cursor-candidates", "eventTime.asc");
        var accounts = service.highRiskAccounts(null, 25, "cursor-accounts", "eventTime.desc");

        assertThat(riskRepository.lastCandidateStatus).isEqualTo(LiquidationCandidateStatus.NEW);
        assertThat(riskRepository.lastCandidateCursor).isEqualTo("cursor-candidates");
        assertThat(riskRepository.lastCandidateSort).isEqualTo("eventTime.asc");
        assertThat(candidates.candidates()).hasSize(1);
        assertThat(candidates.nextCursor()).isEqualTo("next-candidates");
        assertThat(candidates.hasMore()).isTrue();
        assertThat(accounts.accounts()).hasSize(1);
        assertThat(accounts.nextCursor()).isEqualTo("next-accounts");
        assertThat(accounts.sort()).isEqualTo("eventTime.desc");
        assertThat(accounts.limit()).isEqualTo(25);
    }

    private RiskService redisRiskService(RiskProperties properties,
                                         FakeRiskRepository riskRepository,
                                         FakeRiskOutboxRepository outboxRepository,
                                         KafkaTemplate<String, String> kafka,
                                         TrackingTransactionManager transactionManager) {
        RedisRiskStateStore stateStore = mock(RedisRiskStateStore.class);
        RedisRiskCalculator calculator = mock(RedisRiskCalculator.class);
        when(stateStore.ready(any(ProductLine.class))).thenReturn(true);
        when(calculator.calculate(any(CachedRiskGroup.class))).thenAnswer(invocation -> {
            CachedRiskGroup state = invocation.getArgument(0);
            riskRepository.calculateCalls++;
            return riskRepository.positions.stream()
                    .filter(position -> position.userId() == state.key().userId()
                            && position.settleAsset().equals(state.key().settleAsset()))
                    .toList();
        });
        return new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, kafka, transactionManager, stateStore,
                calculator);
    }

    private PositionUpdatedEvent positionEvent(long revision,
                                               long userId,
                                               String symbol,
                                               long instrumentVersion,
                                               String marginAsset,
                                               String traceId) {
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION,
                revision,
                revision,
                ProductLine.LINEAR_PERPETUAL,
                revision,
                userId,
                symbol,
                instrumentVersion,
                MarginMode.CROSS,
                PositionSide.NET,
                0L,
                0L,
                0L,
                0L,
                marginAsset,
                0L,
                eventTime,
                eventTime,
                eventTime,
                traceId);
    }

    private static final class FakeRiskRepository extends RiskRepository {
        private List<CalculatedPositionRisk> positions = List.of(new CalculatedPositionRisk(1001L,
                "BTC-USDT", 7L, "USDT", 10L, 65_000L, 60_000L, 600_000L, -100L, 100L));
        private Set<String> failedCandidateSymbols = Set.of();
        private final List<CalculatedPositionRisk> savedPositionSnapshots = new ArrayList<>();
        private RiskAccountSnapshotResponse lastAccountSnapshot;
        private int savedAccounts;
        private int savedPositions;
        private boolean returnInsertedCandidate;
        private boolean failCandidateBatch;
        private int calculateCalls;
        private int riskGroupCalls;
        private final List<Integer> riskGroupLimits = new ArrayList<>();
        private long walletBalanceUnits;
        private int scanLeaseAttempts;
        private final List<RiskRuleOverride> ruleOverrides = new ArrayList<>();
        private List<HighRiskAccount> highRiskRows = List.of();
        private List<LiquidationCandidateResponse> candidateRows = List.of();
        private long lastHighRiskThreshold;
        private int lastHighRiskLimit;
        private String lastHighRiskCursor;
        private String lastHighRiskSort;
        private String lastLatestAccountType;
        private String lastLatestAccountSettleAsset;
        private LiquidationCandidateStatus lastCandidateStatus;
        private int lastCandidateLimit;
        private String lastCandidateCursor;
        private String lastCandidateSort;

        private FakeRiskRepository() {
            super(null);
        }

        @Override
        public List<RiskGroupKey> riskGroups(RiskGroupKey after, int limit) {
            riskGroupCalls++;
            riskGroupLimits.add(limit);
            return positions.stream()
                    .map(position -> new RiskGroupKey(position.userId(), position.settleAsset()))
                    .distinct()
                    .sorted(Comparator.comparingLong(RiskGroupKey::userId)
                            .thenComparing(RiskGroupKey::accountType)
                            .thenComparing(RiskGroupKey::settleAsset))
                    .filter(key -> after == null || key.userId() > after.userId()
                            || (key.userId() == after.userId()
                            && key.accountType().compareTo(after.accountType()) > 0)
                            || (key.userId() == after.userId()
                            && key.accountType().equals(after.accountType())
                            && key.settleAsset().compareTo(after.settleAsset()) > 0))
                    .limit(limit)
                    .toList();
        }

        @Override
        public CachedRiskGroup cachedRiskGroup(RiskGroupKey key) {
            return new CachedRiskGroup(key, walletBalanceUnits, List.of(), Instant.now());
        }

        @Override
        public long walletBalanceUnits(long userId, String settleAsset) {
            return walletBalanceUnits;
        }

        @Override
        public long walletBalanceUnits(long userId, String accountType, String settleAsset) {
            return walletBalanceUnits;
        }

        @Override
        public Optional<RiskAccountSnapshotResponse> latestAccount(long userId, String accountType, String settleAsset) {
            lastLatestAccountType = accountType;
            lastLatestAccountSettleAsset = settleAsset;
            return Optional.of(new RiskAccountSnapshotResponse(101L, userId, accountType, settleAsset,
                    1_000_000L, 0L, 1_000_000L, 0L, 0L, RiskStatus.NORMAL,
                    Instant.parse("2026-07-01T00:00:00Z")));
        }

        @Override
        public void saveAccountSnapshots(List<RiskAccountSnapshotResponse> snapshots) {
            savedAccounts += snapshots.size();
            if (!snapshots.isEmpty()) {
                lastAccountSnapshot = snapshots.getLast();
            }
        }

        @Override
        public void savePositionSnapshots(List<PositionSnapshotWrite> snapshots) {
            savedPositions += snapshots.size();
            savedPositionSnapshots.addAll(snapshots.stream().map(PositionSnapshotWrite::position).toList());
        }

        @Override
        public Set<Long> createLiquidationCandidates(List<LiquidationCandidateWrite> candidates) {
            if (failCandidateBatch || candidates.stream()
                    .anyMatch(candidate -> failedCandidateSymbols.contains(candidate.position().symbol()))) {
                throw new IllegalStateException("candidate batch failed");
            }
            return returnInsertedCandidate
                    ? candidates.stream().map(LiquidationCandidateWrite::candidateId)
                    .collect(java.util.stream.Collectors.toSet())
                    : Set.of();
        }

        @Override
        public List<RiskRuleOverride> riskRuleOverrides() {
            return List.copyOf(ruleOverrides);
        }

        @Override
        public RiskRuleOverride upsertRiskRuleOverride(String ruleCode,
                                                       String ruleName,
                                                       String ruleType,
                                                       boolean enabled,
                                                       Long warningMarginRatioPpm,
                                                       Long liquidationMarginRatioPpm,
                                                       Long scanDelayMs,
                                                       Integer scanBatchSize,
                                                       String adminUserId,
                                                       String reason,
                                                       Instant now) {
            RiskRuleOverride override = new RiskRuleOverride(ruleCode, ruleName, ruleType, enabled,
                    warningMarginRatioPpm, liquidationMarginRatioPpm, scanDelayMs, scanBatchSize,
                    adminUserId, reason, now, now);
            ruleOverrides.removeIf(item -> item.ruleCode().equals(ruleCode));
            ruleOverrides.add(override);
            return override;
        }

        @Override
        public List<HighRiskAccount> highRiskAccounts(long minMarginRatioPpm, int limit) {
            lastHighRiskThreshold = minMarginRatioPpm;
            lastHighRiskLimit = limit;
            return highRiskRows;
        }

        @Override
        public AdminCursorPage.CursorPage<HighRiskAccount> highRiskAccountsPage(long minMarginRatioPpm,
                                                                                int limit,
                                                                                String cursor,
                                                                                String sort) {
            lastHighRiskThreshold = minMarginRatioPpm;
            lastHighRiskLimit = limit;
            lastHighRiskCursor = cursor;
            lastHighRiskSort = sort;
            return new AdminCursorPage.CursorPage<>(highRiskRows, "next-accounts", true,
                    "eventTime.desc", limit);
        }

        @Override
        public AdminCursorPage.CursorPage<LiquidationCandidateResponse> liquidationCandidatesPage(
                LiquidationCandidateStatus status,
                int limit,
                String cursor,
                String sort) {
            lastCandidateStatus = status;
            lastCandidateLimit = limit;
            lastCandidateCursor = cursor;
            lastCandidateSort = sort;
            return new AdminCursorPage.CursorPage<>(candidateRows, "next-candidates", true,
                    "eventTime.asc", limit);
        }
    }

    private static final class FakeRiskSequenceRepository extends RiskSequenceRepository {
        private long snapshot = 100L;
        private long candidate = 200L;
        private long riskEvent = 300L;

        private FakeRiskSequenceRepository() {
            super(null);
        }

        @Override
        public List<Long> nextSequences(String sequenceName, int count) {
            List<Long> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(switch (sequenceName) {
                    case "risk-snapshot" -> ++snapshot;
                    case "liquidation-candidate" -> ++candidate;
                    case "risk-event" -> ++riskEvent;
                    default -> throw new IllegalArgumentException(sequenceName);
                });
            }
            return values;
        }
    }

    private static final class FakeRiskOutboxRepository extends RiskOutboxRepository {
        private int enqueued;
        private String topic;
        private String eventKey;
        private String eventType;
        private final List<String> eventKeys = new ArrayList<>();
        private final List<String> eventTypes = new ArrayList<>();
        private final List<String> payloads = new ArrayList<>();

        private FakeRiskOutboxRepository() {
            super(null, null);
        }

        @Override
        public void enqueue(List<PendingRiskOutboxEvent> events) {
            for (PendingRiskOutboxEvent event : events) {
                this.enqueued++;
                this.topic = event.topic();
                this.eventKey = event.eventKey();
                this.eventType = event.eventType();
                this.eventKeys.add(event.eventKey());
                this.eventTypes.add(event.eventType());
                this.payloads.add(event.payload());
            }
        }

        private List<String> candidateEventKeys() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < eventTypes.size(); i++) {
                if ("LIQUIDATION_CANDIDATE".equals(eventTypes.get(i))) {
                    keys.add(eventKeys.get(i));
                }
            }
            return keys;
        }
    }

    private static final class TrackingTransactionManager implements PlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
