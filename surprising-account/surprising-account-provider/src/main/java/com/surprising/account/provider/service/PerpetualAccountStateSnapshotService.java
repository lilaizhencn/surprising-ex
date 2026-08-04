package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.repository.AccountProductBalanceRepository;
import com.surprising.account.provider.repository.AccountProductDeficitRepository;
import com.surprising.account.provider.repository.AccountRiskStateRevisionRepository;
import com.surprising.account.provider.repository.AccountStateOrderLockRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 构造产品线用户账户的启动恢复快照。
 *
 * <p>用户分区 WAL 和本地 reducer 才是在线资金事实源。本服务只在本地分区尚未初始化时
 * 从数据库恢复一份带修订号的基线；下游初始化完成后由账户事实流发布的 Kafka 快照保持更新，
 * 不允许在线下单或结算重新拼装数据库状态。</p>
 */
@Service
public class PerpetualAccountStateSnapshotService {

    private final AccountProductBalanceRepository balanceRepository;
    private final AccountProductDeficitRepository deficitRepository;
    private final AccountStateOrderLockRepository orderLockRepository;
    private final PositionMarginRepository positionMarginRepository;
    private final PositionRepository positionRepository;
    private final PositionModeRepository positionModeRepository;
    private final AccountSequenceRepository sequenceRepository;
    private final AccountRiskStateRevisionRepository revisionRepository;

    @Autowired
    public PerpetualAccountStateSnapshotService(AccountProductBalanceRepository balanceRepository,
                                                AccountProductDeficitRepository deficitRepository,
                                                AccountStateOrderLockRepository orderLockRepository,
                                                PositionMarginRepository positionMarginRepository,
                                                PositionRepository positionRepository,
                                                PositionModeRepository positionModeRepository,
                                                AccountSequenceRepository sequenceRepository,
                                                AccountRiskStateRevisionRepository revisionRepository) {
        this.balanceRepository = balanceRepository;
        this.deficitRepository = deficitRepository;
        this.orderLockRepository = orderLockRepository;
        this.positionMarginRepository = positionMarginRepository;
        this.positionRepository = positionRepository;
        this.positionModeRepository = positionModeRepository;
        this.sequenceRepository = sequenceRepository;
        this.revisionRepository = revisionRepository;
    }

    /**
     * 读取一个用户当前已提交的完整快照，供下游 JVM 缓存初始化使用。
     *
     * <p>该入口不会写账户 outbox，也不会被下单热路径直接调用；下游成功初始化后仍由
     * Kafka 增量事件保持一致。没有账户修订号的用户不能被伪造为默认零余额快照。</p>
     */
    // 快照读取主体是只读的，但需要分配账户状态事件序号；不能使用 readOnly 事务执行 nextval。
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PerpetualAccountStateUpdatedEvent snapshot(ProductLine productLine, long userId) {
        AccountType accountType = accountType(productLine);
        if (accountType == null) {
            throw new IllegalArgumentException("账户快照初始化不支持该产品线: " + productLine);
        }
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (revisionRepository == null) {
            throw new IllegalStateException("账户快照初始化修订号仓储未配置");
        }
        long accountRevision = revisionRepository.current(productLine, userId);
        if (accountRevision <= 0L) {
            throw new IllegalStateException("产品线用户账户快照不存在: " + productLine + ":" + userId);
        }
        return build(productLine, accountType, userId, accountRevision, Instant.now(), "rpc-snapshot-init");
    }

    private PerpetualAccountStateUpdatedEvent build(ProductLine productLine,
                                                    AccountType accountType,
                                                    long userId,
                                                    long accountRevision,
                                                    Instant eventTime,
                                                    String traceId) {
        List<PerpetualAccountStateUpdatedEvent.Balance> balances = balanceRepository.findByUser(accountType, userId).stream()
                .map(row -> new PerpetualAccountStateUpdatedEvent.Balance(
                        row.asset(), row.availableUnits(), row.lockedUnits()))
                .toList();
        List<PerpetualAccountStateUpdatedEvent.Deficit> deficits = deficitRepository.findByUser(accountType, userId).stream()
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
        List<PerpetualAccountStateUpdatedEvent.OrderLock> orderLocks = orderLockRepository
                .findByUser(productLine, userId).stream()
                .map(row -> new PerpetualAccountStateUpdatedEvent.OrderLock(row.asset(), row.lockedUnits()))
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
                accountType.name(),
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

    private AccountType accountType(ProductLine productLine) {
        if (productLine == null) {
            return null;
        }
        return switch (productLine) {
            case SPOT -> AccountType.SPOT;
            case LINEAR_PERPETUAL -> AccountType.USDT_PERPETUAL;
            case INVERSE_PERPETUAL -> AccountType.COIN_PERPETUAL;
            case LINEAR_DELIVERY -> AccountType.USDT_DELIVERY;
            case INVERSE_DELIVERY -> AccountType.COIN_DELIVERY;
            case OPTION -> AccountType.OPTION;
        };
    }
}
