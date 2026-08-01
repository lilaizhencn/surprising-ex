package com.surprising.trading.order.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.client.PerpetualAccountStateRpcApi;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.repository.OrderAlgoStateRepository;
import com.surprising.trading.order.repository.OrderCoordinationRepository;
import com.surprising.trading.order.repository.OrderPositionModeRepository;
import com.surprising.trading.order.repository.OrderPositionRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OrderTriggerStateRepository;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/** 在订单业务事务内聚合持仓、仓位模式、普通订单、触发单和算法单的单表状态查询。 */
@Service
public class OrderPlacementStateService {
    private final OrderCoordinationRepository coordinationRepository;
    private final OrderPositionModeRepository positionModeRepository;
    private final OrderPositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final OrderTriggerStateRepository triggerRepository;
    private final OrderAlgoStateRepository algoRepository;
    private final PerpetualAccountStateSnapshotCache accountStateSnapshotCache;
    private final PerpetualAccountStateRpcApi accountStateRpcApi;
    private final ConcurrentMap<Long, Object> snapshotInitializationLocks = new ConcurrentHashMap<>();

    public OrderPlacementStateService(OrderCoordinationRepository coordinationRepository,
                                      OrderPositionModeRepository positionModeRepository,
                                      OrderPositionRepository positionRepository,
                                      OrderRepository orderRepository,
                                      OrderTriggerStateRepository triggerRepository,
                                      OrderAlgoStateRepository algoRepository) {
        this(coordinationRepository, positionModeRepository, positionRepository, orderRepository,
                triggerRepository, algoRepository, null);
    }

    public OrderPlacementStateService(OrderCoordinationRepository coordinationRepository,
                                      OrderPositionModeRepository positionModeRepository,
                                      OrderPositionRepository positionRepository,
                                      OrderRepository orderRepository,
                                      OrderTriggerStateRepository triggerRepository,
                                      OrderAlgoStateRepository algoRepository,
                                      PerpetualAccountStateSnapshotCache accountStateSnapshotCache) {
        this(coordinationRepository, positionModeRepository, positionRepository, orderRepository,
                triggerRepository, algoRepository, accountStateSnapshotCache, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderPlacementStateService(OrderCoordinationRepository coordinationRepository,
                                      OrderPositionModeRepository positionModeRepository,
                                      OrderPositionRepository positionRepository,
                                      OrderRepository orderRepository,
                                      OrderTriggerStateRepository triggerRepository,
                                      OrderAlgoStateRepository algoRepository,
                                      PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                                      PerpetualAccountStateRpcApi accountStateRpcApi) {
        this.coordinationRepository = coordinationRepository;
        this.positionModeRepository = positionModeRepository;
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.triggerRepository = triggerRepository;
        this.algoRepository = algoRepository;
        this.accountStateSnapshotCache = accountStateSnapshotCache;
        this.accountStateRpcApi = accountStateRpcApi;
    }

    public void lockUserPositionMode(ProductLine line, long userId) {
        coordinationRepository.lockUserPositionMode(line, userId);
    }

    public void lockUserSymbolMarginScope(ProductLine line, long userId, String symbol) {
        coordinationRepository.lockUserSymbolMarginScope(line, userId, symbol);
    }

    public PositionMode positionMode(ProductLine line, long userId) {
        if (line == ProductLine.LINEAR_PERPETUAL && accountStateSnapshotCache != null) {
            if (!accountStateSnapshotCache.ready()) {
                // Kafka 可能仍在追赶历史位点；只对当前用户做一次内部 RPC 初始化，
                // 初始化成功后本地缓存即可安全服务该用户，后续更新仍由 Kafka 驱动。
                initializeAccountState(line, userId);
            }
            return state(line, userId).positionMode();
        }
        return positionModeRepository.positionMode(line, userId);
    }

    /** 返回订单计算所依据的永续账户修订号；非永续或影子快照未启用时返回零。 */
    public long accountRevision(ProductLine line, long userId) {
        if (line != ProductLine.LINEAR_PERPETUAL || accountStateSnapshotCache == null) {
            return 0L;
        }
        if (!accountStateSnapshotCache.ready()) {
            initializeAccountState(line, userId);
        }
        return state(line, userId).accountRevision();
    }

    private PerpetualAccountStateUpdatedEvent state(ProductLine line, long userId) {
        Optional<PerpetualAccountStateUpdatedEvent> state = accountStateSnapshotCache.state(userId);
        if (state.isPresent()) {
            return state.get();
        }
        initializeAccountState(line, userId);
        return accountStateSnapshotCache.state(userId)
                .orElseThrow(() -> new IllegalStateException("永续用户账户状态快照不存在: " + userId));
    }

    private void initializeAccountState(ProductLine line, long userId) {
        if (accountStateRpcApi == null) {
            if (!accountStateSnapshotCache.ready()) {
                throw new IllegalStateException("永续账户状态快照尚未就绪");
            }
            throw new IllegalStateException("永续用户账户状态快照不存在: " + userId);
        }
        Object lock = snapshotInitializationLocks.computeIfAbsent(userId, ignored -> new Object());
        try {
            synchronized (lock) {
                if (accountStateSnapshotCache.state(userId).isPresent()) {
                    return;
                }
                PerpetualAccountStateUpdatedEvent event = accountStateRpcApi.snapshot(line, userId);
                PerpetualAccountStateSnapshotCache.ApplyResult result = accountStateSnapshotCache.initialize(event);
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
                    throw new IllegalStateException("永续账户初始化快照产品线不一致");
                }
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("永续用户账户状态快照初始化失败: " + userId, ex);
        } finally {
            snapshotInitializationLocks.remove(userId, lock);
        }
    }

    public Optional<ReduceOnlyPosition> lockedPosition(ProductLine line, long userId, String symbol,
                                                       MarginMode mode, PositionSide side) {
        return positionRepository.lockedPosition(line, userId, symbol, mode, side);
    }

    public boolean hasActiveMarginModeConflict(ProductLine line, long userId, String symbol, MarginMode mode) {
        return positionRepository.hasMarginModeConflict(line, userId, symbol, mode)
                || orderRepository.hasActiveMarginModeConflict(line, userId, symbol, mode)
                || triggerRepository.hasMarginModeConflict(line, userId, symbol, mode)
                || algoRepository.hasMarginModeConflict(line, userId, symbol, mode);
    }
}
