package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountOrderLockRepository;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRiskStateRevisionRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 构造永续用户账户的启动恢复快照。
 *
 * <p>用户分区 WAL 和本地 reducer 才是在线资金事实源。本服务只在本地分区尚未初始化时
 * 从数据库恢复一份带修订号的基线；下游初始化完成后由账户事实流发布的 Kafka 快照保持更新，
 * 不允许在线下单或结算重新拼装数据库状态。</p>
 */
@Service
public class PerpetualAccountStateSnapshotService {

    private static final AccountType ACCOUNT_TYPE = AccountType.USDT_PERPETUAL;

    private final AccountBalanceRepository balanceRepository;
    private final AccountDeficitRepository deficitRepository;
    private final AccountOrderLockRepository orderLockRepository;
    private final PositionMarginRepository positionMarginRepository;
    private final PositionRepository positionRepository;
    private final PositionModeRepository positionModeRepository;
    private final AccountSequenceRepository sequenceRepository;
    private final AccountRiskStateRevisionRepository revisionRepository;
    private final AccountOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * 保留旧测试和嵌入式调用方构造签名；生产 Spring 容器必须使用下面包含修订号仓储的构造函数。
     */
    public PerpetualAccountStateSnapshotService(AccountBalanceRepository balanceRepository,
                                                AccountDeficitRepository deficitRepository,
                                                AccountOrderLockRepository orderLockRepository,
                                                PositionMarginRepository positionMarginRepository,
                                                PositionRepository positionRepository,
                                                PositionModeRepository positionModeRepository,
                                                AccountSequenceRepository sequenceRepository,
                                                AccountOutboxRepository outboxRepository,
                                                ObjectMapper objectMapper) {
        this(balanceRepository, deficitRepository, orderLockRepository, positionMarginRepository,
                positionRepository, positionModeRepository, sequenceRepository, null, outboxRepository, objectMapper);
    }

    @Autowired
    public PerpetualAccountStateSnapshotService(AccountBalanceRepository balanceRepository,
                                                AccountDeficitRepository deficitRepository,
                                                AccountOrderLockRepository orderLockRepository,
                                                PositionMarginRepository positionMarginRepository,
                                                PositionRepository positionRepository,
                                                PositionModeRepository positionModeRepository,
                                                AccountSequenceRepository sequenceRepository,
                                                AccountRiskStateRevisionRepository revisionRepository,
                                                AccountOutboxRepository outboxRepository,
                                                ObjectMapper objectMapper) {
        this.balanceRepository = balanceRepository;
        this.deficitRepository = deficitRepository;
        this.orderLockRepository = orderLockRepository;
        this.positionMarginRepository = positionMarginRepository;
        this.positionRepository = positionRepository;
        this.positionModeRepository = positionModeRepository;
        this.sequenceRepository = sequenceRepository;
        this.revisionRepository = revisionRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /** 仅发布永续快照；调用方必须在账户命令事务中执行。 */
    public PerpetualAccountStateUpdatedEvent publish(ProductLine productLine,
                                                     long userId,
                                                     long accountRevision,
                                                     String topic,
                                                     Instant eventTime,
                                                     String traceId) {
        if (productLine != ProductLine.LINEAR_PERPETUAL) {
            return null;
        }
        if (userId <= 0L || accountRevision <= 0L || eventTime == null) {
            throw new IllegalArgumentException("userId, accountRevision and eventTime are required");
        }
        PerpetualAccountStateUpdatedEvent event = build(productLine, userId, accountRevision, eventTime, traceId);
        outboxRepository.insert(productLine.name(), "ACCOUNT_STATE", event.eventId(), topic,
                event.partitionKey(), "ACCOUNT_STATE_UPDATED", objectMapper.writeValueAsString(event), eventTime);
        return event;
    }

    /**
     * 读取一个用户当前已提交的完整快照，供下游 JVM 缓存初始化使用。
     *
     * <p>该入口不会写账户 outbox，也不会被下单热路径直接调用；下游成功初始化后仍由
     * Kafka 增量事件保持一致。没有账户修订号的用户不能被伪造为默认零余额快照。</p>
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PerpetualAccountStateUpdatedEvent snapshot(ProductLine productLine, long userId) {
        if (productLine != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalArgumentException("账户快照初始化只支持 LINEAR_PERPETUAL");
        }
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (revisionRepository == null) {
            throw new IllegalStateException("账户快照初始化修订号仓储未配置");
        }
        long accountRevision = revisionRepository.current(productLine, userId);
        if (accountRevision <= 0L) {
            throw new IllegalStateException("永续用户账户快照不存在: " + userId);
        }
        return build(productLine, userId, accountRevision, Instant.now(), "rpc-snapshot-init");
    }

    private PerpetualAccountStateUpdatedEvent build(ProductLine productLine,
                                                    long userId,
                                                    long accountRevision,
                                                    Instant eventTime,
                                                    String traceId) {
        List<PerpetualAccountStateUpdatedEvent.Balance> balances = balanceRepository.findByUser(userId).stream()
                .map(row -> new PerpetualAccountStateUpdatedEvent.Balance(
                        row.asset(), row.availableUnits(), row.lockedUnits()))
                .toList();
        List<PerpetualAccountStateUpdatedEvent.Deficit> deficits = deficitRepository.findByUser(userId).stream()
                .map(row -> new PerpetualAccountStateUpdatedEvent.Deficit(
                        row.asset(), row.deficitUnits(), row.reservedUnits()))
                .toList();
        List<PerpetualAccountStateUpdatedEvent.Position> positions = positionRepository
                .findSnapshotByUser(productLine, userId).stream()
                .map(row -> new PerpetualAccountStateUpdatedEvent.Position(
                        row.symbol(), row.instrumentVersion(), row.marginMode(), row.positionSide(),
                        row.signedQuantitySteps(), row.entryPriceTicks(), row.entryValueTicks(),
                        row.realizedPnlUnits(), row.updatedAt()))
                .toList();
        List<PerpetualAccountStateUpdatedEvent.PositionMargin> margins = positionMarginRepository
                .findByUser(productLine, userId).stream()
                .map(row -> new PerpetualAccountStateUpdatedEvent.PositionMargin(
                        row.symbol(), row.asset(), row.marginMode(), row.positionSide(), row.marginUnits()))
                .toList();
        Map<String, Long> openOrderLocks = orderLockRepository.sumOpenIsolatedByAsset(
                productLine, userId, ACCOUNT_TYPE);
        List<PerpetualAccountStateUpdatedEvent.OrderLock> orderLocks = openOrderLocks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PerpetualAccountStateUpdatedEvent.OrderLock(entry.getKey(), entry.getValue()))
                .toList();
        var mode = positionModeRepository.find(productLine, userId)
                .map(PositionModeRepository.PositionModeRow::positionMode)
                .orElse(com.surprising.trading.api.model.PositionMode.ONE_WAY);
        long eventId = sequenceRepository.nextAccountStateEventId();
        PerpetualAccountStateUpdatedEvent event = new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION,
                eventId,
                accountRevision,
                productLine,
                userId,
                ACCOUNT_TYPE.name(),
                balances,
                deficits,
                positions,
                margins,
                orderLocks,
                mode,
                eventTime,
                traceId);
        return event;
    }
}
