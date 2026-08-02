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
import org.springframework.lang.Nullable;
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
                                      @Nullable PerpetualAccountStateSnapshotCache accountStateSnapshotCache,
                                      @Nullable PerpetualAccountStateRpcApi accountStateRpcApi) {
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

    /**
     * 订单 WAL 热路径使用的仓位模式读取；永续只允许从账户 JVM 快照读取，不能退回数据库。
     */
    public PositionMode localPositionMode(ProductLine line, long userId) {
        if (line != ProductLine.LINEAR_PERPETUAL || accountStateSnapshotCache == null) {
            throw new IllegalStateException("订单本地状态尚未支持该产品线的仓位模式快照: " + line);
        }
        return positionMode(line, userId);
    }

    /**
     * 从账户完整快照判断已有持仓是否使用了不同的保证金模式。
     */
    public boolean cachedPositionMarginModeConflict(ProductLine line,
                                                     long userId,
                                                     String symbol,
                                                     MarginMode marginMode) {
        if (line != ProductLine.LINEAR_PERPETUAL || accountStateSnapshotCache == null) {
            throw new IllegalStateException("订单本地状态尚未支持该产品线的保证金模式快照: " + line);
        }
        // positionMode 会在需要时通过账户内部 RPC 初始化单个用户，初始化完成后只读 JVM 快照。
        positionMode(line, userId);
        MarginMode normalized = MarginMode.defaultIfNull(marginMode);
        return state(line, userId).positions().stream()
                .filter(position -> position.symbol().equalsIgnoreCase(symbol))
                .filter(position -> position.signedQuantitySteps() != 0L)
                .anyMatch(position -> position.marginMode() != normalized);
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
                if (result == PerpetualAccountStateSnapshotCache.ApplyResult.CONFLICT) {
                    throw new IllegalStateException("永续账户初始化快照同一修订号内容冲突");
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

    /**
     * 永续订单事实流使用账户 JVM 快照读取持仓，不允许为了平仓重新打开持仓表事务。
     */
    public Optional<ReduceOnlyPosition> localPosition(ProductLine line,
                                                      long userId,
                                                      String symbol,
                                                      MarginMode mode,
                                                      PositionSide side) {
        if (line != ProductLine.LINEAR_PERPETUAL || accountStateSnapshotCache == null) {
            throw new IllegalStateException("订单本地状态尚未支持该产品线的持仓快照: " + line);
        }
        PerpetualAccountStateUpdatedEvent state = state(line, userId);
        MarginMode normalizedMode = MarginMode.defaultIfNull(mode);
        PositionSide normalizedSide = PositionSide.defaultIfNull(side);
        return state.positions().stream()
                .filter(position -> position.symbol().equalsIgnoreCase(symbol))
                .filter(position -> position.marginMode() == normalizedMode)
                .filter(position -> position.positionSide() == normalizedSide)
                .filter(position -> position.signedQuantitySteps() != 0L)
                .map(position -> new ReduceOnlyPosition(position.signedQuantitySteps(),
                        position.instrumentVersion()))
                .findFirst();
    }

    public boolean hasActiveMarginModeConflict(ProductLine line, long userId, String symbol, MarginMode mode) {
        return positionRepository.hasMarginModeConflict(line, userId, symbol, mode)
                || orderRepository.hasActiveMarginModeConflict(line, userId, symbol, mode)
                || triggerRepository.hasMarginModeConflict(line, userId, symbol, mode)
                || algoRepository.hasMarginModeConflict(line, userId, symbol, mode);
    }
}
