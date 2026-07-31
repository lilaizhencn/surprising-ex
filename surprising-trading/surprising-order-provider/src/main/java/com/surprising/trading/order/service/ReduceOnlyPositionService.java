package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.model.ReduceOnlyPositionLookup;
import com.surprising.trading.order.repository.ReduceOnlyOpenOrderRepository;
import com.surprising.trading.order.repository.ReduceOnlyPositionRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 聚合只减仓校验所需的持仓和开放订单单表查询。
 */
@Service
public class ReduceOnlyPositionService implements ReduceOnlyPositionLookup {

    private final ReduceOnlyPositionRepository positionRepository;
    private final ReduceOnlyOpenOrderRepository openOrderRepository;

    public ReduceOnlyPositionService(ReduceOnlyPositionRepository positionRepository,
                                     ReduceOnlyOpenOrderRepository openOrderRepository) {
        this.positionRepository = positionRepository;
        this.openOrderRepository = openOrderRepository;
    }

    @Override
    public Optional<ReduceOnlyPosition> lockedPosition(long userId,
                                                       String symbol,
                                                       MarginMode marginMode,
                                                       PositionSide positionSide) {
        return lockedPosition(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide);
    }

    @Override
    public Optional<ReduceOnlyPosition> lockedPosition(ProductLine productLine,
                                                       long userId,
                                                       String symbol,
                                                       MarginMode marginMode,
                                                       PositionSide positionSide) {
        return positionRepository.lockedPosition(productLine, userId, symbol, marginMode, positionSide);
    }

    @Override
    public long lockedOpenReduceOnlySteps(long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long instrumentVersion,
                                          PositionSide positionSide,
                                          OrderSide closeSide) {
        return lockedOpenReduceOnlySteps(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode,
                instrumentVersion, positionSide, closeSide);
    }

    @Override
    public long lockedOpenReduceOnlySteps(ProductLine productLine,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long instrumentVersion,
                                          PositionSide positionSide,
                                          OrderSide closeSide) {
        return openOrderRepository.lockedOpenReduceOnlySteps(
                productLine, userId, symbol, marginMode, instrumentVersion, positionSide, closeSide);
    }
}
