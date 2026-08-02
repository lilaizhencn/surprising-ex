package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountRiskStateRevisionRepository;
import com.surprising.account.provider.repository.AccountStateOrderLockRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将用户分区事实流的完整账户快照异步投影到数据库。
 *
 * <p>本服务不是账户命令执行器，不能被下单、撮合、风控或结算同步调用。每个用户事件都
 * 通过修订号栅栏幂等处理，并在同一数据库事务中替换所有状态表；数据库短暂落后时不影响
 * 本地 WAL/RocksDB 的资金裁决。</p>
 */
@Service
public class AccountStateProjectionService {

    private static final ProductLine PRODUCT_LINE = ProductLine.LINEAR_PERPETUAL;
    private static final AccountType ACCOUNT_TYPE = AccountType.USDT_PERPETUAL;

    private final AccountBalanceRepository balanceRepository;
    private final AccountDeficitRepository deficitRepository;
    private final PositionRepository positionRepository;
    private final PositionMarginRepository positionMarginRepository;
    private final PositionModeRepository positionModeRepository;
    private final AccountStateOrderLockRepository orderLockRepository;
    private final AccountRiskStateRevisionRepository revisionRepository;

    public AccountStateProjectionService(AccountBalanceRepository balanceRepository,
                                         AccountDeficitRepository deficitRepository,
                                         PositionRepository positionRepository,
                                         PositionMarginRepository positionMarginRepository,
                                         PositionModeRepository positionModeRepository,
                                         AccountStateOrderLockRepository orderLockRepository,
                                         AccountRiskStateRevisionRepository revisionRepository) {
        this.balanceRepository = balanceRepository;
        this.deficitRepository = deficitRepository;
        this.positionRepository = positionRepository;
        this.positionMarginRepository = positionMarginRepository;
        this.positionModeRepository = positionModeRepository;
        this.orderLockRepository = orderLockRepository;
        this.revisionRepository = revisionRepository;
    }

    /** 返回 true 表示本次事件推进了数据库投影，false 表示重复或过期事件。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean project(PerpetualAccountStateUpdatedEvent event) {
        validate(event);
        Instant projectedAt = event.eventTime();
        if (!revisionRepository.beginProjection(PRODUCT_LINE, event.userId(), event.accountRevision(), projectedAt)) {
            return false;
        }
        balanceRepository.replaceProjection(event.userId(), event.balances(), projectedAt);
        deficitRepository.replaceProjection(event.userId(), event.deficits(), projectedAt);
        positionRepository.replaceProjection(PRODUCT_LINE, event.userId(), event.positions(), projectedAt);
        positionMarginRepository.replaceProjection(PRODUCT_LINE, event.userId(), event.positionMargins(), projectedAt);
        positionModeRepository.upsert(PRODUCT_LINE, event.userId(), event.positionMode(), projectedAt);
        orderLockRepository.replaceProjection(PRODUCT_LINE, event.userId(),
                event.orderLocks().stream()
                        .map(lock -> new AccountStateOrderLockRepository.LockProjectionRow(
                                lock.asset(), lock.lockedUnits(), projectedAt))
                        .toList(), projectedAt);
        return true;
    }

    private void validate(PerpetualAccountStateUpdatedEvent event) {
        if (event == null || event.productLine() != PRODUCT_LINE) {
            throw new IllegalArgumentException("账户状态投影只支持 LINEAR_PERPETUAL");
        }
        if (!ACCOUNT_TYPE.name().equals(event.accountType())) {
            throw new IllegalArgumentException("账户状态类型不匹配: " + event.accountType());
        }
        requireUnique(event.balances().stream().map(PerpetualAccountStateUpdatedEvent.Balance::asset).toList(),
                "balances");
        requireUnique(event.deficits().stream().map(PerpetualAccountStateUpdatedEvent.Deficit::asset).toList(),
                "deficits");
        requireUnique(event.orderLocks().stream().map(PerpetualAccountStateUpdatedEvent.OrderLock::asset).toList(),
                "orderLocks");
        requireUnique(event.positions().stream()
                .map(value -> value.symbol() + '|' + value.marginMode() + '|' + value.positionSide())
                .toList(), "positions");
        requireUnique(event.positionMargins().stream()
                .map(value -> value.symbol() + '|' + value.asset() + '|'
                        + value.marginMode() + '|' + value.positionSide())
                .toList(), "positionMargins");
    }

    private void requireUnique(java.util.List<String> values, String field) {
        Set<String> unique = new HashSet<>(values);
        if (unique.size() != values.size()) {
            throw new IllegalArgumentException("账户状态快照存在重复键: " + field);
        }
    }
}
