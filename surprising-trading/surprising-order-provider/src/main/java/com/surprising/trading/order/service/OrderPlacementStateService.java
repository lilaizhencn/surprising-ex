package com.surprising.trading.order.service;

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

    public OrderPlacementStateService(OrderCoordinationRepository coordinationRepository,
                                      OrderPositionModeRepository positionModeRepository,
                                      OrderPositionRepository positionRepository,
                                      OrderRepository orderRepository,
                                      OrderTriggerStateRepository triggerRepository,
                                      OrderAlgoStateRepository algoRepository) {
        this.coordinationRepository = coordinationRepository;
        this.positionModeRepository = positionModeRepository;
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.triggerRepository = triggerRepository;
        this.algoRepository = algoRepository;
    }

    public void lockUserPositionMode(ProductLine line, long userId) {
        coordinationRepository.lockUserPositionMode(line, userId);
    }

    public void lockUserSymbolMarginScope(ProductLine line, long userId, String symbol) {
        coordinationRepository.lockUserSymbolMarginScope(line, userId, symbol);
    }

    public PositionMode positionMode(ProductLine line, long userId) {
        return positionModeRepository.positionMode(line, userId);
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
