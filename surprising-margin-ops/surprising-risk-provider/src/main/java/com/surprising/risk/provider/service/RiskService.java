package com.surprising.risk.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.model.AccountRiskWalletUpdatedEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.risk.api.model.AdminCursorPage;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskAccountUpdatedEvent;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.PositionRiskTarget;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.repository.RiskOutboxRepository;
import com.surprising.risk.provider.repository.RiskOutboxRepository.PendingRiskOutboxEvent;
import com.surprising.risk.provider.repository.RiskRepository;
import com.surprising.risk.provider.repository.RiskLiquidationCandidateRepository.LiquidationCandidateWrite;
import com.surprising.risk.provider.repository.RiskPositionSnapshotRepository.PositionSnapshotWrite;
import com.surprising.risk.provider.repository.RiskRuleRepository.RiskRuleOverride;
import com.surprising.risk.provider.repository.RiskSequenceRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
    public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final ObjectMapper objectMapper;
    private final RiskProperties properties;
    private final RiskRepository riskRepository;
    private final RiskPersistenceService persistenceService;
    private final RiskSequenceRepository sequenceRepository;
    private final RiskOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RedisRiskStateStore stateStore;
    private final RedisRiskCalculator redisCalculator;
    /** 计算线程与数据库投影器之间的本地事实队列。 */
    private final RiskLocalProjectionStore localProjectionStore;
    private RiskGroupKey reconcileAfter;
    private volatile boolean initialProjectionComplete;
    private boolean projectionStarted;
    private RedisRiskStateStore.ProjectionLease projectionLease;
    private String projectionGeneration;
    /** 事件快速路径使用的本地风险组；Redis 仍负责跨节点投影和协调。 */
    private final ConcurrentMap<LocalGroupKey, CachedRiskGroup> localGroups = new ConcurrentHashMap<>();

    @Autowired
    public RiskService(ObjectMapper objectMapper,
                       RiskProperties properties,
                       RiskRepository riskRepository,
                       RiskPersistenceService persistenceService,
                       RiskSequenceRepository sequenceRepository,
                       RiskOutboxRepository outboxRepository,
                       @Qualifier("riskKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                       PlatformTransactionManager transactionManager,
                       RedisRiskStateStore stateStore,
                       RedisRiskCalculator redisCalculator,
                       RiskLocalProjectionStore localProjectionStore) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.riskRepository = riskRepository;
        this.persistenceService = persistenceService;
        this.sequenceRepository = sequenceRepository;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.stateStore = stateStore;
        this.redisCalculator = redisCalculator;
        this.localProjectionStore = localProjectionStore;
    }

    /** 仅供现有单元测试构造风险计算器，不作为生产 Bean 构造器。 */
    RiskService(ObjectMapper objectMapper,
                RiskProperties properties,
                RiskRepository riskRepository,
                RiskPersistenceService persistenceService,
                RiskSequenceRepository sequenceRepository,
                RiskOutboxRepository outboxRepository,
                KafkaTemplate<String, String> kafkaTemplate,
                PlatformTransactionManager transactionManager,
                RedisRiskStateStore stateStore,
                RedisRiskCalculator redisCalculator) {
        this(objectMapper, properties, riskRepository, persistenceService, sequenceRepository,
                outboxRepository, kafkaTemplate, transactionManager, stateStore,
                redisCalculator, null);
    }

    RiskService(ObjectMapper objectMapper,
                RiskProperties properties,
                RiskRepository riskRepository,
                RiskPersistenceService persistenceService,
                RiskSequenceRepository sequenceRepository,
                RiskOutboxRepository outboxRepository,
                KafkaTemplate<String, String> kafkaTemplate,
                PlatformTransactionManager transactionManager) {
        this(objectMapper, properties, riskRepository, persistenceService, sequenceRepository,
                outboxRepository, kafkaTemplate,
                transactionManager, null, null, null);
    }

    RiskService(ObjectMapper objectMapper,
                RiskProperties properties,
                RiskRepository riskRepository,
                RiskPersistenceService persistenceService,
                RiskSequenceRepository sequenceRepository,
                RiskOutboxRepository outboxRepository,
                PlatformTransactionManager transactionManager) {
        this(objectMapper, properties, riskRepository, persistenceService, sequenceRepository,
                outboxRepository, null,
                transactionManager, null, null, null);
    }

    public synchronized void scan() {
        if (!properties.getCalculation().isEnabled()) {
            return;
        }
        requireRedisState();
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!ensureProjectionOwnership(productLine)) {
            return;
        }
        try {
            if (!projectionStarted) {
                projectionGeneration = stateStore.startRebuild(productLine);
                projectionStarted = true;
            } else {
                stateStore.renewRebuild(productLine, projectionGeneration);
            }
            int batchSize = Math.max(1, properties.getCalculation().getScanBatchSize());
            List<RiskGroupKey> groups = riskRepository.riskGroups(reconcileAfter, batchSize);
            if (groups.isEmpty()) {
                completeProjectionCycle(productLine);
                return;
            }
            List<CachedRiskGroup> states = new ArrayList<>(groups.size());
            List<CachedRiskGroup> changedStates = new ArrayList<>(groups.size());
            for (RiskGroupKey key : groups) {
                ProjectionUpdate update = refreshState(productLine, key, false);
                states.add(update.state());
                localGroups.put(new LocalGroupKey(productLine, key), update.state());
                if (update.changed()) {
                    changedStates.add(update.state());
                }
                reconcileAfter = key;
            }
            List<CachedRiskGroup> evaluationStates = initialProjectionComplete ? changedStates : states;
            if (!evaluationStates.isEmpty()) {
                evaluateBatch(evaluationStates, Map.of());
            }
            requireProjectionRenewed(productLine);
            if (initialProjectionComplete) {
                stateStore.markReady(productLine);
            } else {
                stateStore.refreshReady(productLine);
            }
        } catch (RedisRiskStateStore.ProjectionLostException ex) {
            abandonProjection(productLine);
            throw ex;
        } catch (RuntimeException ex) {
            invalidateProjection();
            throw ex;
        }
    }

    /**
     * 事件驱动快速路径。一次 Kafka 拉取可能包含同一用户的多笔成交，因此先按精确持仓保留最新修订，
     * 再确保每个用户、账户类型和结算资产组成的风险组只投影和评估一次。
     */
    public void scanPositionUpdates(List<PositionUpdatedEvent> events) {
        if (!properties.getCalculation().isEnabled()) {
            return;
        }
        if (events == null || events.isEmpty()) {
            return;
        }

        Map<RiskGroupKey, PositionEventGroup> groups = new LinkedHashMap<>();
        for (PositionUpdatedEvent event : events) {
            PositionRiskTarget target = targetFrom(event);
            groups.computeIfAbsent(target.riskGroupKey(), ignored -> new PositionEventGroup())
                    .merge(event, target);
        }
        requireRedisState();
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!stateStore.ready(productLine)) {
            throw new IllegalStateException("风险 JVM/Redis 快照尚未完成恢复，暂停事件计算");
        }
        List<CachedRiskGroup> states = new ArrayList<>(groups.size());
        try {
            for (RiskGroupKey key : groups.keySet()) {
                PositionEventGroup eventGroup = groups.get(key);
                // 先读取本地/Redis 基础状态；Redis 短暂过期时仍可用本地快照重建，
                // 不能因为跨节点投影暂时缺失而让 Kafka 分区永久重试。
                ProjectionUpdate base = refreshState(productLine, key, true);
                CachedRiskGroup state = stateStore.replace(productLine, key, () -> {
                    CachedRiskGroup current = stateStore.read(productLine, key);
                    return mergePositionEvents(current == null ? base.state() : current, eventGroup);
                }).state();
                localGroups.put(new LocalGroupKey(productLine, key), state);
                states.add(state);
            }
        } catch (RuntimeException ex) {
            invalidateProjection();
            throw ex;
        }
        evaluateBatch(states, groups);
        if (initialProjectionComplete) {
            stateStore.markReady(productLine);
        }
    }

    /**
     * 账户余额、订单冻结、欠款或隔离保证金变化后的钱包快照快速路径。
     * 事件已经由账户单写者在同一事务中计算完成，风险服务不再回查账户库或 trading_orders。
     */
    public void scanAccountWalletUpdates(List<AccountRiskWalletUpdatedEvent> events) {
        if (!properties.getCalculation().isEnabled() || events == null || events.isEmpty()) {
            return;
        }
        Map<RiskGroupKey, AccountRiskWalletUpdatedEvent> latest = new LinkedHashMap<>();
        ProductLine productLine = properties.getKafka().getProductLine();
        for (AccountRiskWalletUpdatedEvent event : events) {
            if (event.productLine() != productLine) {
                throw new IllegalArgumentException("风险钱包事件产品线不一致");
            }
            RiskGroupKey key = new RiskGroupKey(event.userId(), event.accountType(), event.settleAsset());
            AccountRiskWalletUpdatedEvent previous = latest.putIfAbsent(key, event);
            if (previous != null && event.accountRevision() > previous.accountRevision()) {
                latest.put(key, event);
            } else if (previous != null && event.accountRevision() == previous.accountRevision()
                    && event.walletBalanceUnits() != previous.walletBalanceUnits()) {
                throw new IllegalStateException("同一账户修订号对应多个风险钱包值: " + key);
            }
        }
        requireRedisState();
        if (!stateStore.ready(productLine)) {
            throw new IllegalStateException("风险 JVM/Redis 快照尚未完成恢复，暂停钱包事件计算");
        }
        List<CachedRiskGroup> states = new ArrayList<>(latest.size());
        try {
            for (Map.Entry<RiskGroupKey, AccountRiskWalletUpdatedEvent> entry : latest.entrySet()) {
                RiskGroupKey key = entry.getKey();
                AccountRiskWalletUpdatedEvent event = entry.getValue();
                refreshState(productLine, key, true);
                CachedRiskGroup state = stateStore.replace(productLine, key, () -> {
                    CachedRiskGroup current = stateStore.read(productLine, key);
                    if (current == null) {
                        throw new IllegalStateException("Redis 风险组快照缺失: " + key);
                    }
                    return event.accountRevision() > current.walletRevision()
                            ? current.withWallet(event.walletBalanceUnits(), event.accountRevision(), Instant.now())
                            : current;
                }).state();
                localGroups.put(new LocalGroupKey(productLine, key), state);
                states.add(state);
            }
        } catch (RuntimeException ex) {
            invalidateProjection();
            throw ex;
        }
        evaluateBatch(states, Map.of());
        if (initialProjectionComplete) {
            stateStore.markReady(productLine);
        }
    }

    public void scanMarkPrice(MarkPriceEvent markPrice) {
        requireRedisState();
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!stateStore.ready(productLine)) {
            throw new IllegalStateException("Redis risk state is not ready for " + productLine);
        }
        List<String> groupIds = stateStore.groupIds(productLine, markPrice.symbol(), markPrice.instrumentVersion());
        int batchSize = Math.max(1, properties.getRedisState().getTriggerBatchSize());
        for (int start = 0; start < groupIds.size(); start += batchSize) {
            int end = Math.min(groupIds.size(), start + batchSize);
            List<CachedRiskGroup> states = stateStore.groups(productLine, groupIds.subList(start, end));
            if (states.size() != end - start) {
                stateStore.markNotReady(productLine);
                throw new IllegalStateException("Redis risk reverse index references missing group state");
            }
            states.forEach(state -> localGroups.put(new LocalGroupKey(productLine, state.key()), state));
            evaluateBatch(states, Map.of());
        }
    }

    private ProjectionUpdate refreshState(ProductLine productLine, RiskGroupKey key, boolean preferLocal) {
        if (preferLocal) {
            CachedRiskGroup local = localGroups.get(new LocalGroupKey(productLine, key));
            if (local != null && !local.capturedAt().isBefore(
                    Instant.now().minus(properties.getRedisState().getStateTtl()))) {
                return new ProjectionUpdate(local, false);
            }
            CachedRiskGroup projected = stateStore.read(productLine, key);
            if (projected == null) {
                // 事件热路径禁止因为缓存缺失而回查数据库；等待完整账户/持仓快照初始化后再重试。
                throw new IllegalStateException("风险组 JVM/Redis 快照缺失，禁止回退数据库: " + key);
            }
            return new ProjectionUpdate(projected, false);
        }
        RedisRiskStateStore.ProjectionUpdate update =
                stateStore.replace(productLine, key, () -> riskRepository.cachedRiskGroup(key));
        return new ProjectionUpdate(update.state(), update.changed());
    }

    private CachedRiskGroup mergePositionEvents(CachedRiskGroup state, PositionEventGroup eventGroup) {
        if (eventGroup == null || eventGroup.events().isEmpty()) {
            return state;
        }
        Map<PositionScope, com.surprising.risk.provider.model.CachedRiskPosition> positions = new LinkedHashMap<>();
        for (var position : state.positions()) {
            positions.put(new PositionScope(position.symbol(), position.marginMode(), position.positionSide()), position);
        }
        for (PositionUpdatedEvent event : eventGroup.events()) {
            PositionScope scope = new PositionScope(event.symbol(), event.marginMode(), event.positionSide());
            if (event.signedQuantitySteps() == 0L) {
                positions.remove(scope);
            } else {
                String settleAsset = event.marginAsset() == null || event.marginAsset().isBlank()
                        ? state.key().settleAsset() : event.marginAsset();
                positions.put(scope, new com.surprising.risk.provider.model.CachedRiskPosition(
                        event.symbol(), event.marginMode(), event.positionSide(), event.instrumentVersion(),
                        settleAsset, event.signedQuantitySteps(), event.entryPriceTicks(), event.marginUnits(),
                        event.revision()));
            }
        }
        return new CachedRiskGroup(state.key(), state.walletBalanceUnits(), state.walletRevision(),
                List.copyOf(positions.values()), Instant.now());
    }

    private void evaluateBatch(List<CachedRiskGroup> states,
                               Map<RiskGroupKey, PositionEventGroup> eventGroups) {
        Instant now = Instant.now();
        List<GroupEvaluation> evaluations = new ArrayList<>(states.size());
        int positionWriteCount = 0;
        int candidateCount = 0;
        for (CachedRiskGroup state : states) {
            PositionEventGroup eventGroup = eventGroups.get(state.key());
            GroupEvaluation evaluation = evaluateGroup(state.key(), state.walletBalanceUnits(),
                    redisCalculator.calculate(state), eventGroup == null ? List.of() : eventGroup.targets(),
                    now, eventGroup == null ? null : eventGroup.traceId());
            evaluations.add(evaluation);
            positionWriteCount += evaluation.positions().size() + evaluation.flatPositions().size();
            candidateCount += evaluation.candidateCount();
        }

        if (localProjectionStore != null) {
            // 风险计算热路径只落本地同步 WAL，数据库序列、快照和强平候选由独立投影任务处理。
            localProjectionStore.append(toProjectionBatch(evaluations));
            return;
        }

        // 仅保留给没有注入本地队列的单元测试构造器；生产构造器始终注入 RiskLocalProjectionStore。
        projectEvaluations(evaluations);
    }

    /** 消费本地风险事实并异步写入数据库读模型。 */
    public void projectPending() {
        if (localProjectionStore == null) {
            return;
        }
        List<RiskLocalProjectionStore.PendingBatch> pending = localProjectionStore.pending(
                Math.max(1, properties.getLocalState().getProjectionBatchSize()));
        for (RiskLocalProjectionStore.PendingBatch item : pending) {
            projectPendingBatch(item);
        }
    }

    private void projectPendingBatch(RiskLocalProjectionStore.PendingBatch pending) {
        RiskLocalProjectionStore.RiskProjectionBatch batch = pending.batch();
        if (batch.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalStateException("风险本地投影事实产品线不匹配: " + batch.productLine());
        }
        int positionCount = batch.groups().stream()
                .mapToInt(group -> group.positions().size() + group.flatPositions().size())
                .sum();
        int candidateCount = batch.groups().stream()
                .mapToInt(group -> (int) group.positions().stream()
                        .filter(RiskLocalProjectionStore.RiskProjectionPosition::liquidation)
                        .count())
                .sum();
        RiskLocalProjectionStore.ProjectionIds ids = pending.ids().orElseGet(() -> {
            List<Long> snapshots = sequenceRepository.nextSequences("risk-snapshot", batch.groups().size());
            List<Long> events = sequenceRepository.nextSequences("risk-event",
                    batch.groups().size() + positionCount);
            List<Long> candidates = sequenceRepository.nextSequences("liquidation-candidate", candidateCount);
            RiskLocalProjectionStore.ProjectionIds allocated = new RiskLocalProjectionStore.ProjectionIds(
                    firstOrZero(snapshots), firstOrZero(events), firstOrZero(candidates));
            localProjectionStore.assign(pending.sequence(), allocated);
            return allocated;
        });
        List<GroupEvaluation> evaluations = new ArrayList<>(batch.groups().size());
        for (RiskLocalProjectionStore.RiskProjectionGroup group : batch.groups()) {
            List<EvaluatedPosition> positions = group.positions().stream()
                    .map(position -> new EvaluatedPosition(position.position(), position.marginRatioPpm(),
                            position.status(), position.equityUnits(), position.liquidation()))
                    .toList();
            evaluations.add(new GroupEvaluation(
                    new RiskGroupKey(group.userId(), group.accountType(), group.settleAsset()),
                    group.walletBalanceUnits(), group.unrealizedPnlUnits(), group.equityUnits(),
                    group.maintenanceMarginUnits(), group.marginRatioPpm(), group.status(), positions,
                    group.flatPositions(), positions.stream().mapToInt(position -> position.liquidation() ? 1 : 0)
                            .sum(), group.eventTime(), group.traceId()));
        }
        projectEvaluations(evaluations, ids);
        localProjectionStore.markProjected(pending.sequence());
    }

    private RiskLocalProjectionStore.RiskProjectionBatch toProjectionBatch(List<GroupEvaluation> evaluations) {
        return new RiskLocalProjectionStore.RiskProjectionBatch(properties.getKafka().getProductLine(),
                evaluations.stream().map(evaluation -> new RiskLocalProjectionStore.RiskProjectionGroup(
                        evaluation.key().userId(), evaluation.key().accountType(), evaluation.key().settleAsset(),
                        evaluation.walletBalanceUnits(), evaluation.unrealizedPnlUnits(), evaluation.equityUnits(),
                        evaluation.maintenanceMarginUnits(), evaluation.marginRatioPpm(), evaluation.status(),
                        evaluation.positions().stream().map(position ->
                                new RiskLocalProjectionStore.RiskProjectionPosition(position.position(),
                                        position.marginRatioPpm(), position.status(), position.equityUnits(),
                                        position.liquidation())).toList(),
                        evaluation.flatPositions(), evaluation.eventTime(), evaluation.traceId())).toList());
    }

    private long firstOrZero(List<Long> values) {
        return values.isEmpty() ? 0L : values.get(0);
    }

    private void projectEvaluations(List<GroupEvaluation> evaluations) {
        int positionWriteCount = evaluations.stream()
                .mapToInt(evaluation -> evaluation.positions().size() + evaluation.flatPositions().size()).sum();
        int candidateCount = evaluations.stream().mapToInt(GroupEvaluation::candidateCount).sum();
        List<Long> snapshotIds = sequenceRepository.nextSequences("risk-snapshot", evaluations.size());
        List<Long> eventIds = sequenceRepository.nextSequences("risk-event", evaluations.size() + positionWriteCount);
        List<Long> candidateIds = sequenceRepository.nextSequences("liquidation-candidate", candidateCount);
        projectEvaluations(evaluations, new RiskLocalProjectionStore.ProjectionIds(
                firstOrZero(snapshotIds), firstOrZero(eventIds), firstOrZero(candidateIds)));
    }

    private void projectEvaluations(List<GroupEvaluation> evaluations,
                                    RiskLocalProjectionStore.ProjectionIds ids) {
        int positionWriteCount = evaluations.stream()
                .mapToInt(evaluation -> evaluation.positions().size() + evaluation.flatPositions().size()).sum();
        int candidateCount = evaluations.stream().mapToInt(GroupEvaluation::candidateCount).sum();
        List<RealtimeRiskEvent> realtimeEvents = new ArrayList<>();
        transactionTemplate.executeWithoutResult(status -> {
            int eventIndex = 0;
            int candidateIndex = 0;
            List<RiskAccountSnapshotResponse> accounts = new ArrayList<>(evaluations.size());
            List<PositionSnapshotWrite> positions = new ArrayList<>(positionWriteCount);
            List<LiquidationCandidateWrite> candidates = new ArrayList<>(candidateCount);

            for (int groupIndex = 0; groupIndex < evaluations.size(); groupIndex++) {
                GroupEvaluation evaluation = evaluations.get(groupIndex);
                long snapshotId = ids.snapshotStart() + groupIndex;
                RiskAccountSnapshotResponse account = evaluation.account(snapshotId);
                accounts.add(account);
                stageAccountRisk(ids.eventStart() + eventIndex++, account, evaluation.traceId(), realtimeEvents);
                for (EvaluatedPosition evaluatedPosition : evaluation.positions()) {
                    CalculatedPositionRisk position = evaluatedPosition.position();
                    positions.add(new PositionSnapshotWrite(account.snapshotId(), position,
                            evaluatedPosition.marginRatioPpm(), evaluatedPosition.status(), evaluation.eventTime()));
                    stagePositionRisk(ids.eventStart() + eventIndex++, account.snapshotId(), position,
                            evaluatedPosition.marginRatioPpm(), evaluatedPosition.status(), evaluation.eventTime(),
                            evaluation.traceId(), realtimeEvents);
                    if (evaluatedPosition.liquidation()) {
                        candidates.add(new LiquidationCandidateWrite(ids.candidateStart() + candidateIndex++, account,
                                position, evaluatedPosition.marginRatioPpm(), evaluatedPosition.equityUnits(),
                                evaluation.eventTime()));
                    }
                }
                for (CalculatedPositionRisk flatPosition : evaluation.flatPositions()) {
                    positions.add(new PositionSnapshotWrite(account.snapshotId(), flatPosition, 0L,
                            RiskStatus.NORMAL, evaluation.eventTime()));
                    stagePositionRisk(ids.eventStart() + eventIndex++, account.snapshotId(), flatPosition, 0L,
                            RiskStatus.NORMAL, evaluation.eventTime(), evaluation.traceId(), realtimeEvents);
                }
            }

            persistenceService.saveAccountSnapshots(accounts);
            persistenceService.savePositionSnapshots(positions);
            Set<Long> insertedCandidateIds = persistenceService.createLiquidationCandidates(candidates);
            outboxRepository.enqueue(candidateOutboxEvents(candidates, insertedCandidateIds));
        });
        publishRealtimeEvents(realtimeEvents);
    }

    private void requireRedisState() {
        if (stateStore == null || redisCalculator == null) {
            throw new IllegalStateException("Redis risk state components are required");
        }
    }

    synchronized void invalidateProjection() {
        ProductLine productLine = properties.getKafka().getProductLine();
        localGroups.clear();
        try {
            stateStore.markNotReady(productLine);
        } finally {
            abandonProjection(productLine);
        }
    }

    private boolean ensureProjectionOwnership(ProductLine productLine) {
        if (projectionLease == null) {
            projectionLease = stateStore.tryAcquireProjection(productLine);
            if (projectionLease == null) {
                return false;
            }
            resetProjectionCycle();
            return true;
        }
        if (!stateStore.renewProjection(projectionLease)) {
            loseProjectionOwnership();
            return false;
        }
        return true;
    }

    private void requireProjectionRenewed(ProductLine productLine) {
        if (!stateStore.renewProjection(projectionLease)) {
            loseProjectionOwnership();
            throw new RedisRiskStateStore.ProjectionLostException("Redis 风险投影协调租约已经丢失");
        }
        stateStore.renewRebuild(productLine, projectionGeneration);
    }

    private void completeProjectionCycle(ProductLine productLine) {
        requireProjectionRenewed(productLine);
        stateStore.pruneUnseen(productLine, projectionGeneration);
        requireProjectionRenewed(productLine);
        stateStore.completeRebuild(productLine, projectionGeneration);
        reconcileAfter = null;
        initialProjectionComplete = true;
        projectionStarted = false;
        projectionGeneration = null;
        stateStore.markReady(productLine);
    }

    private void abandonProjection(ProductLine productLine) {
        try {
            if (stateStore != null) {
                stateStore.abandonRebuild(productLine, projectionGeneration);
            }
        } finally {
            try {
                if (stateStore != null) {
                    stateStore.releaseProjection(projectionLease);
                }
            } finally {
                loseProjectionOwnership();
            }
        }
    }

    private void resetProjectionCycle() {
        reconcileAfter = null;
        initialProjectionComplete = false;
        projectionStarted = false;
        projectionGeneration = null;
    }

    private void loseProjectionOwnership() {
        projectionLease = null;
        resetProjectionCycle();
    }

    @PreDestroy
    synchronized void closeProjectionLease() {
        ProductLine productLine = properties.getKafka().getProductLine();
        abandonProjection(productLine);
    }

    public RiskAccountSnapshotResponse latestAccount(long userId, String settleAsset) {
        return latestAccount(userId, null, settleAsset);
    }

    public RiskAccountSnapshotResponse latestAccount(long userId, String accountType, String settleAsset) {
        return persistenceService.latestAccount(userId, scopedAccountType(accountType), normalizeAsset(settleAsset))
                .orElseThrow(() -> new IllegalStateException("risk snapshot not found"));
    }

    public RiskPositionQueryResponse latestPositions(long userId) {
        List<RiskPositionSnapshotResponse> rows = persistenceService.latestPositions(userId);
        return new RiskPositionQueryResponse(rows.size(), rows);
    }

    public LiquidationCandidateQueryResponse liquidationCandidates(String status, int limit) {
        LiquidationCandidateStatus candidateStatus = LiquidationCandidateStatus.valueOf(status.trim().toUpperCase());
        List<LiquidationCandidateResponse> rows = persistenceService.liquidationCandidates(candidateStatus,
                normalizeLimit(limit));
        return new LiquidationCandidateQueryResponse(rows.size(), rows);
    }

    public LiquidationCandidateQueryResponse liquidationCandidates(String status,
                                                                  int limit,
                                                                  String cursor,
                                                                  String sort) {
        LiquidationCandidateStatus candidateStatus = LiquidationCandidateStatus.valueOf(status.trim().toUpperCase());
        AdminCursorPage.CursorPage<LiquidationCandidateResponse> page =
                persistenceService.liquidationCandidatesPage(
                candidateStatus, normalizeLimit(limit), cursor, sort);
        return new LiquidationCandidateQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public RiskRulesResponse riskRules() {
        List<RiskRuleOverride> overrides = persistenceService.riskRuleOverrides();
        RiskRuleOverride marginOverride = override(overrides, "GLOBAL_MARGIN_POLICY");
        RiskRuleOverride scanOverride = override(overrides, "RISK_SCAN_CONTROL");
        List<RiskRuleResponse> rules = List.of(
                rule("GLOBAL_MARGIN_POLICY", "Global margin thresholds", "GLOBAL_MARGIN",
                        marginOverride == null ? null : marginOverride.enabled(),
                        properties.getCalculation().getWarningMarginRatioPpm(),
                        properties.getCalculation().getLiquidationMarginRatioPpm(),
                        null,
                        null,
                        marginOverride),
                rule("RISK_SCAN_CONTROL", "Risk scan control", "SCAN_CONTROL",
                        scanOverride == null ? properties.getCalculation().isEnabled() : scanOverride.enabled(),
                        null,
                        null,
                        properties.getCalculation().getScanDelayMs(),
                        properties.getCalculation().getScanBatchSize(),
                        scanOverride));
        return new RiskRulesResponse(rules.size(), rules);
    }

    public RiskRuleResponse updateRiskRule(String ruleCode, String adminUserId, RiskRuleUpdateCommand command) {
        String normalizedCode = normalizeRuleCode(ruleCode);
        String normalizedAdmin = requireText(adminUserId, "adminUserId");
        if (command == null) {
            throw new IllegalArgumentException("request is required");
        }
        String reason = requireText(command.reason(), "reason");
        if (reason.length() > 500) {
            throw new IllegalArgumentException("reason must be at most 500 characters");
        }
        if ("GLOBAL_MARGIN_POLICY".equals(normalizedCode)) {
            long warning = nonNegative(
                    command.warningMarginRatioPpm() == null
                            ? properties.getCalculation().getWarningMarginRatioPpm()
                            : command.warningMarginRatioPpm(),
                    "warningMarginRatioPpm");
            long liquidation = nonNegative(
                    command.liquidationMarginRatioPpm() == null
                            ? properties.getCalculation().getLiquidationMarginRatioPpm()
                            : command.liquidationMarginRatioPpm(),
                    "liquidationMarginRatioPpm");
            if (warning >= liquidation) {
                throw new IllegalArgumentException("warningMarginRatioPpm must be less than liquidationMarginRatioPpm");
            }
            properties.getCalculation().setWarningMarginRatioPpm(warning);
            properties.getCalculation().setLiquidationMarginRatioPpm(liquidation);
            RiskRuleOverride override = persistenceService.upsertRiskRuleOverride(normalizedCode,
                    ruleName(command.ruleName(), "Global margin thresholds"), "GLOBAL_MARGIN",
                    command.enabled() == null || command.enabled(), warning, liquidation, null, null,
                    normalizedAdmin, reason, Instant.now());
            return ruleFromOverride(override);
        }
        if ("RISK_SCAN_CONTROL".equals(normalizedCode)) {
            boolean enabled = command.enabled() == null ? properties.getCalculation().isEnabled() : command.enabled();
            long scanDelayMs = nonNegative(command.scanDelayMs() == null
                    ? properties.getCalculation().getScanDelayMs()
                    : command.scanDelayMs(), "scanDelayMs");
            int scanBatchSize = bounded(command.scanBatchSize() == null
                    ? properties.getCalculation().getScanBatchSize()
                    : command.scanBatchSize(), 1, 10_000, "scanBatchSize");
            properties.getCalculation().setEnabled(enabled);
            properties.getCalculation().setScanDelayMs(scanDelayMs);
            properties.getCalculation().setScanBatchSize(scanBatchSize);
            RiskRuleOverride override = persistenceService.upsertRiskRuleOverride(normalizedCode,
                    ruleName(command.ruleName(), "Risk scan control"), "SCAN_CONTROL", enabled,
                    null, null, scanDelayMs, scanBatchSize, normalizedAdmin, reason, Instant.now());
            return ruleFromOverride(override);
        }
        throw new IllegalArgumentException("unsupported risk rule: " + ruleCode);
    }

    private GroupEvaluation evaluateGroup(RiskGroupKey key,
                                          long walletBalance,
                                          List<CalculatedPositionRisk> positions,
                                          List<PositionRiskTarget> eventTargets,
                                          Instant now,
                                          String traceId) {
        List<CalculatedPositionRisk> crossPositions = positions.stream()
                .filter(position -> position.marginMode() == MarginMode.CROSS)
                .toList();
        long unrealizedPnl = sumUnrealizedPnl(crossPositions);
        long maintenanceMargin = sumMaintenanceMargin(crossPositions);
        long equity = RiskMath.equity(walletBalance, unrealizedPnl);
        long marginRatio = RiskMath.marginRatioPpm(maintenanceMargin, equity);
        RiskStatus accountStatus = RiskMath.status(marginRatio,
                properties.getCalculation().getWarningMarginRatioPpm(),
                properties.getCalculation().getLiquidationMarginRatioPpm());
        List<EvaluatedPosition> evaluatedPositions = new ArrayList<>(positions.size());
        int candidateCount = 0;
        for (CalculatedPositionRisk position : positions) {
            long positionEquity = position.marginMode() == MarginMode.ISOLATED
                    ? RiskMath.equity(position.positionMarginUnits(), position.unrealizedPnlUnits())
                    : equity;
            long positionMarginRatio = RiskMath.marginRatioPpm(position.maintenanceMarginUnits(), positionEquity);
            RiskStatus positionStatus = RiskMath.status(positionMarginRatio,
                    properties.getCalculation().getWarningMarginRatioPpm(),
                    properties.getCalculation().getLiquidationMarginRatioPpm());
            boolean liquidation = (position.marginMode() == MarginMode.CROSS
                    && accountStatus == RiskStatus.LIQUIDATION)
                    || (position.marginMode() == MarginMode.ISOLATED
                    && positionStatus == RiskStatus.LIQUIDATION);
            if (liquidation) {
                candidateCount++;
            }
            evaluatedPositions.add(new EvaluatedPosition(position, positionMarginRatio, positionStatus,
                    positionEquity, liquidation));
        }
        List<CalculatedPositionRisk> flatPositions = new ArrayList<>();
        for (PositionRiskTarget eventTarget : eventTargets) {
            boolean positionStillOpen = positions.stream()
                    .anyMatch(position -> position.symbol().equals(eventTarget.symbol())
                            && position.marginMode() == eventTarget.marginMode()
                            && position.positionSide() == eventTarget.positionSide());
            if (!positionStillOpen) {
                CalculatedPositionRisk flatPosition = new CalculatedPositionRisk(eventTarget.userId(),
                        eventTarget.symbol(), eventTarget.marginMode(), eventTarget.positionSide(),
                        eventTarget.instrumentVersion(), eventTarget.settleAsset(), 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        0L);
                flatPositions.add(flatPosition);
            }
        }
        return new GroupEvaluation(key, walletBalance, unrealizedPnl, equity, maintenanceMargin, marginRatio,
                accountStatus, evaluatedPositions, flatPositions, candidateCount, now, traceId);
    }

    private void stageAccountRisk(long eventId,
                                  RiskAccountSnapshotResponse account,
                                  String traceId,
                                  List<RealtimeRiskEvent> realtimeEvents) {
        RiskAccountUpdatedEvent event = RiskAccountUpdatedEvent.from(eventId, account, traceId);
        realtimeEvents.add(new RealtimeRiskEvent(properties.getKafka().getAccountRiskEventsTopic(),
                account.userId() + ":" + account.accountType() + ":" + account.settleAsset(),
                "RISK_ACCOUNT_UPDATED", payload(event)));
    }

    private void stagePositionRisk(long eventId,
                                   long snapshotId,
                                   CalculatedPositionRisk position,
                                   long marginRatioPpm,
                                   RiskStatus status,
                                   Instant now,
                                   String traceId,
                                   List<RealtimeRiskEvent> realtimeEvents) {
        RiskPositionUpdatedEvent event = new RiskPositionUpdatedEvent(
                eventId,
                properties.getKafka().getProductLine(),
                snapshotId,
                position.userId(),
                position.symbol(),
                position.marginMode(),
                position.positionSide(),
                position.instrumentVersion(),
                position.settleAsset(),
                position.signedQuantitySteps(),
                position.entryPriceTicks(),
                position.markPriceTicks(),
                position.notionalUnits(),
                position.unrealizedPnlUnits(),
                position.maintenanceMarginUnits(),
                position.positionMarginUnits(),
                marginRatioPpm,
                status,
                now,
                traceId);
        realtimeEvents.add(new RealtimeRiskEvent(properties.getKafka().getPositionRiskEventsTopic(), position.symbol(),
                "RISK_POSITION_UPDATED", payload(event)));
    }

    private void publishRealtimeEvents(List<RealtimeRiskEvent> events) {
        if (kafkaTemplate == null) {
            return;
        }
        for (RealtimeRiskEvent event : events) {
            kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish committed risk snapshot topic={} key={} type={}: {}",
                            event.topic(), event.eventKey(), event.eventType(), ex.getMessage());
                }
            });
        }
    }

    private List<PendingRiskOutboxEvent> candidateOutboxEvents(List<LiquidationCandidateWrite> candidates,
                                                               Set<Long> insertedCandidateIds) {
        List<PendingRiskOutboxEvent> events = new ArrayList<>(insertedCandidateIds.size());
        for (LiquidationCandidateWrite candidate : candidates) {
            if (!insertedCandidateIds.contains(candidate.candidateId())) {
                continue;
            }
            RiskAccountSnapshotResponse account = candidate.account();
            CalculatedPositionRisk position = candidate.position();
            LiquidationCandidateEvent event = new LiquidationCandidateEvent(
                    candidate.candidateId(), account.snapshotId(), position.userId(), position.symbol(),
                    position.marginMode(), position.positionSide(), position.instrumentVersion(),
                    position.settleAsset(), position.signedQuantitySteps(), position.markPriceTicks(),
                    candidate.equityUnits(), position.maintenanceMarginUnits(),
                    Math.max(account.marginRatioPpm(), candidate.positionMarginRatioPpm()), candidate.eventTime(),
                    position.positionRevision() > 0L ? position.positionRevision() : account.snapshotId());
            events.add(new PendingRiskOutboxEvent(properties.getKafka().getLiquidationCandidatesTopic(),
                    position.symbol(), "LIQUIDATION_CANDIDATE", payload(event), candidate.eventTime()));
        }
        return events;
    }

    private long sumUnrealizedPnl(List<CalculatedPositionRisk> positions) {
        long total = 0L;
        for (CalculatedPositionRisk position : positions) {
            total = Math.addExact(total, position.unrealizedPnlUnits());
        }
        return total;
    }

    private long sumMaintenanceMargin(List<CalculatedPositionRisk> positions) {
        long total = 0L;
        for (CalculatedPositionRisk position : positions) {
            total = Math.addExact(total, position.maintenanceMarginUnits());
        }
        return total;
    }

    private String normalizeAsset(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String normalized = asset.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        return normalized;
    }

    private String normalizeAccountType(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            return "USDT_PERPETUAL";
        }
        String normalized = accountType.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9_]{2,32}")) {
            throw new IllegalArgumentException("invalid accountType: " + accountType);
        }
        return normalized;
    }

    private String scopedAccountType(String accountType) {
        ProductLine productLine = currentProductLineFilter();
        if (productLine == null) {
            return normalizeAccountType(accountType);
        }
        String currentAccountType = productLine.accountTypeCode();
        if (accountType == null || accountType.isBlank()) {
            return currentAccountType;
        }
        String normalized = normalizeAccountType(accountType);
        if (!currentAccountType.equals(normalized)) {
            throw new IllegalArgumentException("accountType must match current product line account");
        }
        return normalized;
    }

    private ProductLine currentProductLineFilter() {
        RiskProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        return kafka != null && kafka.isProductTopicsEnabled() ? kafka.getProductLine() : null;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9-]{3,64}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private PositionRiskTarget targetFrom(PositionUpdatedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("position event is required");
        }
        ProductLine currentProductLine = properties.getKafka().getProductLine();
        if (event.productLine() != currentProductLine) {
            throw new IllegalArgumentException("position event product line must match current risk provider");
        }
        if (event.instrumentVersion() <= 0L) {
            throw new IllegalArgumentException("position event instrumentVersion must be positive");
        }
        return new PositionRiskTarget(
                event.userId(),
                normalizeSymbol(event.symbol()),
                MarginMode.defaultIfNull(event.marginMode()),
                PositionSide.defaultIfNull(event.positionSide()),
                event.instrumentVersion(),
                event.productLine().accountTypeCode(),
                normalizeAsset(event.marginAsset()));
    }

    private RiskRuleOverride override(List<RiskRuleOverride> overrides, String ruleCode) {
        return overrides.stream()
                .filter(item -> ruleCode.equals(item.ruleCode()))
                .findFirst()
                .orElse(null);
    }

    private RiskRuleResponse rule(String ruleCode,
                                  String ruleName,
                                  String ruleType,
                                  Boolean enabled,
                                  Long warningMarginRatioPpm,
                                  Long liquidationMarginRatioPpm,
                                  Long scanDelayMs,
                                  Integer scanBatchSize,
                                  RiskRuleOverride override) {
        return new RiskRuleResponse(ruleCode, ruleName, ruleType, enabled == null || enabled,
                warningMarginRatioPpm, liquidationMarginRatioPpm, scanDelayMs, scanBatchSize,
                override == null ? "runtime" : "override",
                override == null ? null : override.adminUserId(),
                override == null ? null : override.reason(),
                override == null ? null : override.updatedAt());
    }

    private RiskRuleResponse ruleFromOverride(RiskRuleOverride override) {
        return new RiskRuleResponse(override.ruleCode(), override.ruleName(), override.ruleType(),
                override.enabled(), override.warningMarginRatioPpm(), override.liquidationMarginRatioPpm(),
                override.scanDelayMs(), override.scanBatchSize(), "override", override.adminUserId(),
                override.reason(), override.updatedAt());
    }

    private String normalizeRuleCode(String value) {
        String normalized = requireText(value, "ruleCode").toUpperCase();
        if (!normalized.matches("[A-Z0-9_.:-]{2,96}")) {
            throw new IllegalArgumentException("invalid ruleCode: " + value);
        }
        return normalized;
    }

    private String ruleName(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim();
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("ruleName must be at most 120 characters");
        }
        return normalized;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private long nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private int normalizeLimit(int limit) {
        return bounded(limit, 1, 1000, "limit");
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize risk event", ex);
        }
    }

    private record RealtimeRiskEvent(String topic, String eventKey, String eventType, String payload) {
    }

    private record EvaluatedPosition(CalculatedPositionRisk position,
                                     long marginRatioPpm,
                                     RiskStatus status,
                                     long equityUnits,
                                     boolean liquidation) {
    }

    private record GroupEvaluation(RiskGroupKey key,
                                   long walletBalanceUnits,
                                   long unrealizedPnlUnits,
                                   long equityUnits,
                                   long maintenanceMarginUnits,
                                   long marginRatioPpm,
                                   RiskStatus status,
                                   List<EvaluatedPosition> positions,
                                   List<CalculatedPositionRisk> flatPositions,
                                   int candidateCount,
                                   Instant eventTime,
                                   String traceId) {

        private RiskAccountSnapshotResponse account(long snapshotId) {
            return new RiskAccountSnapshotResponse(snapshotId, key.userId(), key.accountType(), key.settleAsset(),
                    walletBalanceUnits, unrealizedPnlUnits, equityUnits, maintenanceMarginUnits, marginRatioPpm,
                    status, eventTime);
        }
    }

    private record ProjectionUpdate(CachedRiskGroup state, boolean changed) {
    }

    private record PositionScope(String symbol, MarginMode marginMode, PositionSide positionSide) {
    }

    private record LocalGroupKey(ProductLine productLine, RiskGroupKey key) {
    }

    private record VersionedPositionTarget(long revision, PositionRiskTarget target) {
    }

    private static final class PositionEventGroup {
        private final Map<PositionScope, VersionedPositionTarget> targets = new LinkedHashMap<>();
        private final Map<PositionScope, VersionedPositionEvent> events = new LinkedHashMap<>();
        private long latestRevision;
        private String traceId;

        private void merge(PositionUpdatedEvent event, PositionRiskTarget target) {
            long revision = event.revision();
            PositionScope scope = new PositionScope(target.symbol(), target.marginMode(), target.positionSide());
            VersionedPositionTarget current = targets.get(scope);
            if (current == null || revision >= current.revision()) {
                targets.put(scope, new VersionedPositionTarget(revision, target));
                events.put(scope, new VersionedPositionEvent(revision, event));
            }
            if (revision >= latestRevision) {
                latestRevision = revision;
                traceId = event.traceId();
            }
        }

        private List<PositionRiskTarget> targets() {
            return targets.values().stream()
                    .map(VersionedPositionTarget::target)
                    .toList();
        }

        private String traceId() {
            return traceId;
        }

        private List<PositionUpdatedEvent> events() {
            return events.values().stream()
                    .map(VersionedPositionEvent::event)
                    .toList();
        }
    }

    private record VersionedPositionEvent(long revision, PositionUpdatedEvent event) {
    }

    public record RiskRulesResponse(int ruleCount,
                                    List<RiskRuleResponse> rules) {
    }

    public record RiskRuleResponse(String ruleCode,
                                   String ruleName,
                                   String ruleType,
                                   boolean enabled,
                                   Long warningMarginRatioPpm,
                                   Long liquidationMarginRatioPpm,
                                   Long scanDelayMs,
                                   Integer scanBatchSize,
                                   String source,
                                   String adminUserId,
                                   String reason,
                                   Instant updatedAt) {
    }

    public record RiskRuleUpdateCommand(String ruleName,
                                        Boolean enabled,
                                        Long warningMarginRatioPpm,
                                        Long liquidationMarginRatioPpm,
                                        Long scanDelayMs,
                                        Integer scanBatchSize,
                                        String reason) {
    }

}
