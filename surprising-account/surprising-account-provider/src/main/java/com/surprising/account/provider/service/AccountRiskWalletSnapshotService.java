package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountRiskWalletUpdatedEvent;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountOrderLockRepository;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRiskStateRevisionRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 在账户事务提交前生成永续风险钱包完整快照。
 *
 * <p>这里暂时读取各个单表仓储，是从数据库单写者迁移到 JVM 快照的过渡层；风险服务不再执行
 * 跨表查询。后续账户单写者的内存状态稳定后，只需替换本服务的数据来源，不改变事件协议。</p>
 */
@Service
public class AccountRiskWalletSnapshotService {

    private static final AccountType LINEAR_ACCOUNT = AccountType.USDT_PERPETUAL;

    private final AccountBalanceRepository accountBalanceRepository;
    private final AccountDeficitRepository accountDeficitRepository;
    private final AccountOrderLockRepository orderLockRepository;
    private final PositionMarginRepository positionMarginRepository;
    private final PositionRepository positionRepository;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final AccountRiskStateRevisionRepository revisionRepository;
    private final AccountSequenceRepository sequenceRepository;
    private final AccountOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public AccountRiskWalletSnapshotService(AccountBalanceRepository accountBalanceRepository,
                                            AccountDeficitRepository accountDeficitRepository,
                                            AccountOrderLockRepository orderLockRepository,
                                            PositionMarginRepository positionMarginRepository,
                                            PositionRepository positionRepository,
                                            InstrumentSnapshotCache instrumentSnapshotCache,
                                            AccountRiskStateRevisionRepository revisionRepository,
                                            AccountSequenceRepository sequenceRepository,
                                            AccountOutboxRepository outboxRepository,
                                            ObjectMapper objectMapper) {
        this.accountBalanceRepository = accountBalanceRepository;
        this.accountDeficitRepository = accountDeficitRepository;
        this.orderLockRepository = orderLockRepository;
        this.positionMarginRepository = positionMarginRepository;
        this.positionRepository = positionRepository;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.revisionRepository = revisionRepository;
        this.sequenceRepository = sequenceRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 只为永续账户发布快照。方法必须在账户命令事务中调用，任何读取或序列化失败都会回滚资金变更。
     */
    public List<AccountRiskWalletUpdatedEvent> publish(ProductLine productLine,
                                                        long userId,
                                                        String topic,
                                                        Instant eventTime,
                                                        String traceId) {
        if (productLine != ProductLine.LINEAR_PERPETUAL) {
            return List.of();
        }
        long accountRevision = revisionRepository.next(productLine, userId, eventTime);
        Map<String, Long> walletByAsset = walletByAsset(productLine, userId);
        if (walletByAsset.isEmpty()) {
            return List.of();
        }
        List<AccountRiskWalletUpdatedEvent> events = walletByAsset.entrySet().stream()
                .map(entry -> new AccountRiskWalletUpdatedEvent(
                        AccountRiskWalletUpdatedEvent.CURRENT_SCHEMA_VERSION,
                        sequenceRepository.nextRiskWalletEventId(),
                        accountRevision,
                        productLine,
                        userId,
                        LINEAR_ACCOUNT.name(),
                        entry.getKey(),
                        entry.getValue(),
                        eventTime,
                        traceId))
                .toList();
        for (AccountRiskWalletUpdatedEvent event : events) {
            outboxRepository.insert(productLine.name(), "RISK_WALLET", event.eventId(), topic,
                    event.partitionKey(), "ACCOUNT_RISK_WALLET_UPDATED",
                    objectMapper.writeValueAsString(event), eventTime);
        }
        return events;
    }

    private Map<String, Long> walletByAsset(ProductLine productLine, long userId) {
        Map<String, Long> balanceByAsset = new LinkedHashMap<>();
        accountBalanceRepository.findByUser(userId).forEach(row ->
                balanceByAsset.merge(row.asset(), Math.addExact(row.availableUnits(), row.lockedUnits()), Math::addExact));
        Map<String, Long> deficitByAsset = new LinkedHashMap<>();
        accountDeficitRepository.findByUser(userId).forEach(row ->
                deficitByAsset.merge(row.asset(), row.deficitUnits(), Math::addExact));
        Map<String, Long> marginByAsset = positionMarginRepository.sumOpenIsolatedByAsset(productLine, userId);
        Map<String, Long> orderLockByAsset = orderLockRepository.sumOpenIsolatedByAsset(productLine, userId,
                LINEAR_ACCOUNT);

        Set<String> assets = new LinkedHashSet<>();
        positionRepository.findOpenByUser(productLine, userId, null).forEach(position -> {
            if (position.instrumentVersion() <= 0) {
                throw new IllegalStateException("永续持仓缺少合约版本: " + position.symbol());
            }
            var instrument = instrumentSnapshotCache.version(productLine, position.symbol(),
                            position.instrumentVersion())
                    .orElseThrow(() -> new IllegalStateException("永续持仓合约快照不存在: " + position.symbol()));
            assets.add(instrument.settleAsset());
        });
        assets.addAll(marginByAsset.keySet());
        assets.addAll(orderLockByAsset.keySet());
        Map<String, Long> result = new LinkedHashMap<>();
        for (String asset : assets) {
            long wallet = balanceByAsset.getOrDefault(asset, 0L);
            wallet = Math.subtractExact(wallet, deficitByAsset.getOrDefault(asset, 0L));
            wallet = Math.subtractExact(wallet, marginByAsset.getOrDefault(asset, 0L));
            wallet = Math.subtractExact(wallet, orderLockByAsset.getOrDefault(asset, 0L));
            result.put(asset, wallet);
        }
        return result;
    }
}
