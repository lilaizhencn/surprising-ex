package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.repository.AccountProductBalanceRepository;
import com.surprising.account.provider.repository.AccountProductDeficitRepository;
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

    private final AccountProductBalanceRepository balanceRepository;
    private final AccountProductDeficitRepository deficitRepository;
    private final PositionRepository positionRepository;
    private final PositionMarginRepository positionMarginRepository;
    private final PositionModeRepository positionModeRepository;
    private final AccountStateOrderLockRepository orderLockRepository;
    private final AccountRiskStateRevisionRepository revisionRepository;

    public AccountStateProjectionService(AccountProductBalanceRepository balanceRepository,
                                         AccountProductDeficitRepository deficitRepository,
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
        ProductLine productLine = event.productLine();
        AccountType accountType = AccountType.valueOf(event.accountType());
        if (!revisionRepository.beginProjection(productLine, event.userId(), event.accountRevision(), projectedAt)) {
            return false;
        }
        balanceRepository.replaceProjection(accountType, event.userId(), event.balances(), projectedAt);
        deficitRepository.replaceProjection(accountType, event.userId(), event.deficits(), projectedAt);
        positionRepository.replaceProjection(productLine, event.userId(), event.positions(), projectedAt);
        positionMarginRepository.replaceProjection(productLine, event.userId(), event.positionMargins(), projectedAt);
        positionModeRepository.upsert(productLine, event.userId(), event.positionMode(), projectedAt);
        orderLockRepository.replaceProjection(productLine, event.userId(),
                event.orderLocks().stream()
                        .map(lock -> new AccountStateOrderLockRepository.LockProjectionRow(
                                lock.asset(), lock.lockedUnits(), projectedAt))
                        .toList(), projectedAt);
        return true;
    }

    private void validate(PerpetualAccountStateUpdatedEvent event) {
        if (event == null || event.productLine() == null) {
            throw new IllegalArgumentException("账户状态投影缺少产品线");
        }
        AccountType accountType;
        try {
            accountType = AccountType.valueOf(event.accountType());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("账户状态类型不匹配: " + event.accountType(), ex);
        }
        if (accountType == AccountType.FUNDING
                || accountType.productLine().orElse(null) != event.productLine()) {
            throw new IllegalArgumentException("账户状态类型与产品线不匹配: "
                    + event.accountType() + " / " + event.productLine());
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
