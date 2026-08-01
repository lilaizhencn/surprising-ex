package com.surprising.trading.order.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
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

    public OrderPlacementStateService(OrderCoordinationRepository coordinationRepository,
                                      OrderPositionModeRepository positionModeRepository,
                                      OrderPositionRepository positionRepository,
                                      OrderRepository orderRepository,
                                      OrderTriggerStateRepository triggerRepository,
                                      OrderAlgoStateRepository algoRepository) {
        this(coordinationRepository, positionModeRepository, positionRepository, orderRepository,
                triggerRepository, algoRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderPlacementStateService(OrderCoordinationRepository coordinationRepository,
                                      OrderPositionModeRepository positionModeRepository,
                                      OrderPositionRepository positionRepository,
                                      OrderRepository orderRepository,
                                      OrderTriggerStateRepository triggerRepository,
                                      OrderAlgoStateRepository algoRepository,
                                      PerpetualAccountStateSnapshotCache accountStateSnapshotCache) {
        this.coordinationRepository = coordinationRepository;
        this.positionModeRepository = positionModeRepository;
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.triggerRepository = triggerRepository;
        this.algoRepository = algoRepository;
        this.accountStateSnapshotCache = accountStateSnapshotCache;
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
                throw new IllegalStateException("永续账户状态快照尚未就绪");
            }
            return accountStateSnapshotCache.state(userId)
                    .map(com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent::positionMode)
                    .orElseThrow(() -> new IllegalStateException("永续用户账户状态快照不存在: " + userId));
        }
        return positionModeRepository.positionMode(line, userId);
    }

    /** 返回订单计算所依据的永续账户修订号；非永续或影子快照未启用时返回零。 */
    public long accountRevision(ProductLine line, long userId) {
        if (line != ProductLine.LINEAR_PERPETUAL || accountStateSnapshotCache == null) {
            return 0L;
        }
        if (!accountStateSnapshotCache.ready()) {
            throw new IllegalStateException("永续账户状态快照尚未就绪");
        }
        return accountStateSnapshotCache.state(userId)
                .map(com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent::accountRevision)
                .orElseThrow(() -> new IllegalStateException("永续用户账户状态快照不存在: " + userId));
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
