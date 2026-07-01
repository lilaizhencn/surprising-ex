package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.repository.RiskOutboxRepository;
import com.surprising.risk.provider.repository.RiskRepository;
import com.surprising.risk.provider.repository.RiskSequenceRepository;
import com.surprising.risk.provider.model.PositionRiskTarget;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.trading.api.model.MarginMode;
import java.util.Comparator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class RiskServiceTest {

    @Test
    void scanRollsBackFailedRiskGroupBeforeOutbox() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), new RiskProperties(), riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scan();

        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.savedPositions).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isZero();
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
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

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
    void positionUpdateScansResolvedRiskGroupImmediately() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of(
                new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                        10L, 65_000L, 65_000L, 650_000L, 0L, 100L),
                new CalculatedPositionRisk(2002L, "ETH-USDT", 7L, "USDT",
                        10L, 3_500L, 3_500L, 35_000L, 0L, 100L));
        riskRepository.positionEventTarget = Optional.of(new PositionRiskTarget(2002L, "ETH-USDT", 7L, "USDT"));
        riskRepository.walletBalanceUnits = 1_000_000L;
        RiskProperties properties = new RiskProperties();
        properties.getCoordination().setEnabled(false);
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scanPositionUpdate(2002L, "eth-usdt", 7L);

        assertThat(riskRepository.positionEventResolveCalls).isEqualTo(1);
        assertThat(riskRepository.lastPositionEventUserId).isEqualTo(2002L);
        assertThat(riskRepository.lastPositionEventSymbol).isEqualTo("ETH-USDT");
        assertThat(riskRepository.lastPositionEventVersion).isEqualTo(7L);
        assertThat(riskRepository.riskGroupCalls).isZero();
        assertThat(riskRepository.calculateCalls).isEqualTo(1);
        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.savedPositions).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void positionUpdateDoesNothingWhenCalculationIsDisabled() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positionEventTarget = Optional.of(new PositionRiskTarget(1001L, "BTC-USDT", 7L, "USDT"));
        RiskProperties properties = new RiskProperties();
        properties.getCalculation().setEnabled(false);
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scanPositionUpdate(1001L, "BTC-USDT", 7L);

        assertThat(riskRepository.positionEventResolveCalls).isZero();
        assertThat(riskRepository.calculateCalls).isZero();
        assertThat(riskRepository.savedAccounts).isZero();
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isZero();
    }

    @Test
    void positionUpdateRespectsRiskGroupLease() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positionEventTarget = Optional.of(new PositionRiskTarget(1001L, "BTC-USDT", 7L, "USDT"));
        riskRepository.scanLeaseAcquired = false;
        RiskProperties properties = new RiskProperties();
        properties.getCoordination().setNodeId("risk-node-b");
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scanPositionUpdate(1001L, "BTC-USDT", 7L);

        assertThat(riskRepository.positionEventResolveCalls).isEqualTo(1);
        assertThat(riskRepository.scanLeaseAttempts).isEqualTo(1);
        assertThat(riskRepository.lastOwnerId).isEqualTo("risk-node-b");
        assertThat(riskRepository.calculateCalls).isZero();
        assertThat(riskRepository.savedAccounts).isZero();
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isZero();
    }

    @Test
    void positionUpdateWritesFlatRiskSnapshotsWhenPositionIsClosed() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of();
        riskRepository.positionEventTarget = Optional.of(new PositionRiskTarget(1001L, "BTC-USDT", 7L, "USDT"));
        riskRepository.walletBalanceUnits = 1_000_000L;
        RiskProperties properties = new RiskProperties();
        properties.getCoordination().setEnabled(false);
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scanPositionUpdate(1001L, "BTC-USDT", 0L);

        assertThat(riskRepository.positionEventResolveCalls).isEqualTo(1);
        assertThat(riskRepository.calculateCalls).isEqualTo(1);
        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.lastAccountSnapshot.status()).isEqualTo(RiskStatus.NORMAL);
        assertThat(riskRepository.lastAccountSnapshot.walletBalanceUnits()).isEqualTo(1_000_000L);
        assertThat(riskRepository.lastAccountSnapshot.unrealizedPnlUnits()).isZero();
        assertThat(riskRepository.lastAccountSnapshot.maintenanceMarginUnits()).isZero();
        assertThat(riskRepository.lastAccountSnapshot.marginRatioPpm()).isZero();
        assertThat(riskRepository.savedPositions).isEqualTo(1);
        assertThat(riskRepository.savedPositionSnapshots).singleElement().satisfies(position -> {
            assertThat(position.symbol()).isEqualTo("BTC-USDT");
            assertThat(position.instrumentVersion()).isEqualTo(7L);
            assertThat(position.signedQuantitySteps()).isZero();
            assertThat(position.entryPriceTicks()).isZero();
            assertThat(position.markPriceTicks()).isZero();
            assertThat(position.notionalUnits()).isZero();
            assertThat(position.unrealizedPnlUnits()).isZero();
            assertThat(position.maintenanceMarginUnits()).isZero();
        });
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void positionUpdateDoesNotWriteFlatSnapshotWhenOpenPositionsRemainButMarksAreStale() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of();
        riskRepository.openPositionsExist = true;
        riskRepository.positionEventTarget = Optional.of(new PositionRiskTarget(1001L, "BTC-USDT", 7L, "USDT"));
        RiskProperties properties = new RiskProperties();
        properties.getCoordination().setEnabled(false);
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scanPositionUpdate(1001L, "BTC-USDT", 7L);

        assertThat(riskRepository.calculateCalls).isEqualTo(1);
        assertThat(riskRepository.hasOpenPositionsCalls).isEqualTo(1);
        assertThat(riskRepository.savedAccounts).isZero();
        assertThat(riskRepository.savedPositions).isZero();
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void scanPublishesOutboxEventWhenCandidateIsInsertedAndReadable() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.returnInsertedCandidate = true;
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), new RiskProperties(), riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scan();

        assertThat(outboxRepository.enqueued).isEqualTo(1);
        assertThat(outboxRepository.topic).isEqualTo("surprising.perp.liquidation.candidates.v1");
        assertThat(outboxRepository.eventKey).isEqualTo("BTC-USDT");
        assertThat(outboxRepository.eventType).isEqualTo("LIQUIDATION_CANDIDATE");
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void scanSkipsRiskGroupWhenAnotherNodeOwnsTheLease() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.scanLeaseAcquired = false;
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskProperties properties = new RiskProperties();
        properties.getCoordination().setNodeId("risk-node-a");
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scan();

        assertThat(riskRepository.riskGroupCalls).isEqualTo(2);
        assertThat(riskRepository.calculateCalls).isZero();
        assertThat(riskRepository.scanLeaseAttempts).isEqualTo(1);
        assertThat(riskRepository.lastOwnerId).isEqualTo("risk-node-a");
        assertThat(riskRepository.savedAccounts).isZero();
        assertThat(riskRepository.savedPositions).isZero();
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isZero();
    }

    @Test
    void scanBypassesRiskGroupLeaseWhenCoordinationIsDisabled() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.returnInsertedCandidate = true;
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskProperties properties = new RiskProperties();
        properties.getCoordination().setEnabled(false);
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scan();

        assertThat(riskRepository.scanLeaseAttempts).isZero();
        assertThat(riskRepository.savedAccounts).isEqualTo(1);
        assertThat(riskRepository.savedPositions).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isEqualTo(1);
    }

    @Test
    void scanContinuesWithOtherRiskGroupsWhenOneGroupFails() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of(
                new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                        10L, 65_000L, 60_000L, 600_000L, -100L, 100L),
                new CalculatedPositionRisk(2002L, "ETH-USDT", 7L, "USDT",
                        10L, 3_500L, 3_000L, 30_000L, -100L, 100L));
        riskRepository.returnInsertedCandidate = true;
        riskRepository.unreadableCandidateSymbols = Set.of("BTC-USDT");
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), new RiskProperties(), riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scan();

        assertThat(riskRepository.savedAccounts).isEqualTo(2);
        assertThat(riskRepository.savedPositions).isEqualTo(2);
        assertThat(outboxRepository.eventKeys).containsExactly("ETH-USDT");
        assertThat(transactionManager.commits).isEqualTo(1);
        assertThat(transactionManager.rollbacks).isEqualTo(1);
    }

    @Test
    void scanRollsBackRiskGroupWhenMaintenanceAggregateOverflows() {
        FakeRiskRepository riskRepository = new FakeRiskRepository();
        riskRepository.positions = List.of(
                new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                        10L, 65_000L, 60_000L, 600_000L, 0L, Long.MAX_VALUE),
                new CalculatedPositionRisk(1001L, "ETH-USDT", 7L, "USDT",
                        10L, 3_500L, 3_000L, 30_000L, 0L, 1L));
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), new RiskProperties(), riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scan();

        assertThat(riskRepository.savedAccounts).isZero();
        assertThat(riskRepository.savedPositions).isZero();
        assertThat(outboxRepository.enqueued).isZero();
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isEqualTo(1);
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
        properties.getCoordination().setEnabled(false);
        FakeRiskOutboxRepository outboxRepository = new FakeRiskOutboxRepository();
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        RiskService service = new RiskService(new ObjectMapper(), properties, riskRepository,
                new FakeRiskSequenceRepository(), outboxRepository, transactionManager);

        service.scan();

        assertThat(riskRepository.riskGroupCalls).isEqualTo(3);
        assertThat(riskRepository.calculateCalls).isEqualTo(2);
        assertThat(riskRepository.riskGroupLimits).containsExactly(1, 1, 1);
        assertThat(riskRepository.savedAccounts).isEqualTo(2);
        assertThat(riskRepository.savedPositions).isEqualTo(2);
        assertThat(transactionManager.commits).isEqualTo(2);
    }

    private static final class FakeRiskRepository extends RiskRepository {
        private List<CalculatedPositionRisk> positions = List.of(new CalculatedPositionRisk(1001L,
                "BTC-USDT", 7L, "USDT", 10L, 65_000L, 60_000L, 600_000L, -100L, 100L));
        private Set<String> unreadableCandidateSymbols = Set.of();
        private final Map<Long, CalculatedPositionRisk> candidatePositions = new HashMap<>();
        private final List<CalculatedPositionRisk> savedPositionSnapshots = new ArrayList<>();
        private RiskAccountSnapshotResponse lastAccountSnapshot;
        private int savedAccounts;
        private int savedPositions;
        private boolean returnInsertedCandidate;
        private int calculateCalls;
        private int riskGroupCalls;
        private final List<Integer> riskGroupLimits = new ArrayList<>();
        private Optional<PositionRiskTarget> positionEventTarget = Optional.empty();
        private int positionEventResolveCalls;
        private long lastPositionEventUserId;
        private String lastPositionEventSymbol;
        private MarginMode lastPositionEventMarginMode;
        private long lastPositionEventVersion;
        private long walletBalanceUnits;
        private boolean openPositionsExist;
        private int hasOpenPositionsCalls;
        private boolean scanLeaseAcquired = true;
        private int scanLeaseAttempts;
        private String lastOwnerId;

        private FakeRiskRepository() {
            super(null);
        }

        @Override
        public List<RiskGroupKey> riskGroups(Duration maxMarkAge, RiskGroupKey after, int limit) {
            riskGroupCalls++;
            riskGroupLimits.add(limit);
            return positions.stream()
                    .map(position -> new RiskGroupKey(position.userId(), position.settleAsset()))
                    .distinct()
                    .sorted(Comparator.comparingLong(RiskGroupKey::userId).thenComparing(RiskGroupKey::settleAsset))
                    .filter(key -> after == null || key.userId() > after.userId()
                            || (key.userId() == after.userId()
                            && key.settleAsset().compareTo(after.settleAsset()) > 0))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<PositionRiskTarget> riskTargetForPositionEvent(long userId,
                                                                       String symbol,
                                                                       MarginMode marginMode,
                                                                       long instrumentVersion) {
            positionEventResolveCalls++;
            lastPositionEventUserId = userId;
            lastPositionEventSymbol = symbol;
            lastPositionEventMarginMode = marginMode;
            lastPositionEventVersion = instrumentVersion;
            return positionEventTarget;
        }

        @Override
        public Optional<PositionRiskTarget> riskTargetForPositionEvent(long userId,
                                                                       String symbol,
                                                                       long instrumentVersion) {
            return riskTargetForPositionEvent(userId, symbol, MarginMode.CROSS, instrumentVersion);
        }

        @Override
        public List<CalculatedPositionRisk> calculatePositions(RiskGroupKey key, Duration maxMarkAge) {
            calculateCalls++;
            return positions.stream()
                    .filter(position -> position.userId() == key.userId()
                            && position.settleAsset().equals(key.settleAsset()))
                    .toList();
        }

        @Override
        public boolean hasOpenPositions(RiskGroupKey key) {
            hasOpenPositionsCalls++;
            return openPositionsExist || positions.stream()
                    .anyMatch(position -> position.userId() == key.userId()
                            && position.settleAsset().equals(key.settleAsset()));
        }

        @Override
        public boolean acquireScanLease(com.surprising.risk.provider.model.RiskGroupKey key,
                                        String ownerId,
                                        Duration leaseDuration) {
            scanLeaseAttempts++;
            lastOwnerId = ownerId;
            return scanLeaseAcquired;
        }

        @Override
        public long walletBalanceUnits(long userId, String settleAsset) {
            return walletBalanceUnits;
        }

        @Override
        public void saveAccountSnapshot(RiskAccountSnapshotResponse snapshot) {
            savedAccounts++;
            lastAccountSnapshot = snapshot;
        }

        @Override
        public void savePositionSnapshot(long snapshotId,
                                         CalculatedPositionRisk position,
                                         long marginRatioPpm,
                                         RiskStatus status,
                                         Instant now) {
            savedPositions++;
            savedPositionSnapshots.add(position);
        }

        @Override
        public long createLiquidationCandidate(RiskAccountSnapshotResponse account,
                                               CalculatedPositionRisk position,
                                               RiskStatus positionStatus,
                                               long positionMarginRatioPpm,
                                               long equityUnits,
                                               long candidateId,
                                               Instant now) {
            candidatePositions.put(candidateId, position);
            return candidateId;
        }

        @Override
        public long createLiquidationCandidate(RiskAccountSnapshotResponse account,
                                               CalculatedPositionRisk position,
                                               RiskStatus positionStatus,
                                               long positionMarginRatioPpm,
                                               long candidateId,
                                               Instant now) {
            return createLiquidationCandidate(account, position, positionStatus, positionMarginRatioPpm,
                    account.equityUnits(), candidateId, now);
        }

        @Override
        public Optional<LiquidationCandidateResponse> liquidationCandidate(long candidateId) {
            CalculatedPositionRisk position = candidatePositions.get(candidateId);
            if (!returnInsertedCandidate || position == null
                    || unreadableCandidateSymbols.contains(position.symbol())) {
                return Optional.empty();
            }
            return Optional.of(new LiquidationCandidateResponse(candidateId, 101L, position.userId(), position.symbol(),
                    position.instrumentVersion(), position.settleAsset(), position.signedQuantitySteps(),
                    position.markPriceTicks(), -100L, position.maintenanceMarginUnits(),
                    RiskMath.INFINITE_MARGIN_RATIO, LiquidationCandidateStatus.NEW,
                    Instant.parse("2026-07-01T00:00:00Z")));
        }
    }

    private static final class FakeRiskSequenceRepository extends RiskSequenceRepository {
        private long snapshot = 100L;
        private long candidate = 200L;

        private FakeRiskSequenceRepository() {
            super(null);
        }

        @Override
        public long nextSequence(String sequenceName) {
            return switch (sequenceName) {
                case "risk-snapshot" -> ++snapshot;
                case "liquidation-candidate" -> ++candidate;
                default -> throw new IllegalArgumentException(sequenceName);
            };
        }
    }

    private static final class FakeRiskOutboxRepository extends RiskOutboxRepository {
        private int enqueued;
        private String topic;
        private String eventKey;
        private String eventType;
        private final List<String> eventKeys = new ArrayList<>();

        private FakeRiskOutboxRepository() {
            super(null, null);
        }

        @Override
        public void enqueue(String topic, String eventKey, String eventType, String payload, Instant now) {
            this.enqueued++;
            this.topic = topic;
            this.eventKey = eventKey;
            this.eventType = eventType;
            this.eventKeys.add(eventKey);
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
