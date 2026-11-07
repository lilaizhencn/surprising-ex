package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import com.surprising.trading.order.model.GridStrategyModels.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GridStrategyService {
    private static final Logger log = LoggerFactory.getLogger(GridStrategyService.class);

    private final OrderService orderService;
    private final AtomicLong strategyIdGenerator = new AtomicLong(System.currentTimeMillis() % 10000000);
    private final Map<Long, GridStrategyRecord> strategies = new ConcurrentHashMap<>();
    private final Map<Long, List<GridLevel>> strategyLevels = new ConcurrentHashMap<>();

    public GridStrategyService(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建并启动网格交易策略。
     */
    public GridStrategyRecord createAndStartGrid(long userId, CreateGridStrategyRequest request, long currentPriceTicks) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.lowerPriceTicks() <= 0 || request.upperPriceTicks() <= request.lowerPriceTicks()) {
            throw new IllegalArgumentException("upperPriceTicks must be greater than lowerPriceTicks");
        }
        if (request.gridCount() < 2 || request.gridCount() > 100) {
            throw new IllegalArgumentException("gridCount must be between 2 and 100");
        }
        if (currentPriceTicks <= 0) {
            currentPriceTicks = (request.lowerPriceTicks() + request.upperPriceTicks()) / 2;
        }

        long strategyId = strategyIdGenerator.incrementAndGet();
        ProductLine productLine = request.productLine() != null ? request.productLine() : ProductLine.LINEAR_PERPETUAL;

        // 生成各档位价格
        List<Long> priceGrid = generatePriceGrid(request.lowerPriceTicks(), request.upperPriceTicks(),
                request.gridCount(), request.isArithmetic());

        long perGridInvestment = Math.max(1L, request.totalInvestmentUnits() / request.gridCount());
        List<GridLevel> levels = new ArrayList<>();

        for (int i = 0; i < priceGrid.size(); i++) {
            long price = priceGrid.get(i);
            long qty = Math.max(1L, perGridInvestment / Math.max(1L, price));
            OrderSide side = price < currentPriceTicks ? OrderSide.BUY : OrderSide.SELL;
            String clientOrderId = "grid-" + strategyId + "-" + i + "-" + (System.currentTimeMillis() % 100000);

            long orderId = 0L;
            String status = "PENDING";
            try {
                PlaceOrderRequest orderRequest = new PlaceOrderRequest(
                        userId, clientOrderId, request.instrumentId(),
                        side, OrderType.LIMIT, TimeInForce.GTC,
                        price, qty, MarginMode.CROSS, PositionSide.NET,
                        false, true // 默认 post-only 做流动性挂单
                );
                OrderResponse response = orderService.place(orderRequest);
                if (response != null && response.orderId() > 0) {
                    orderId = response.orderId();
                    status = "PLACED";
                }
            } catch (Exception ex) {
                log.warn("Failed to place initial grid level order at price {}: {}", price, ex.getMessage());
            }

            levels.add(new GridLevel(i, price, side.name(), qty, orderId, status));
        }

        GridStrategyRecord record = new GridStrategyRecord(
                strategyId, userId, request.instrumentId(), productLine,
                request.lowerPriceTicks(), request.upperPriceTicks(),
                request.gridCount(), request.isArithmetic(), request.totalInvestmentUnits(),
                "ACTIVE", 0L, 0, Instant.now()
        );

        strategies.put(strategyId, record);
        strategyLevels.put(strategyId, levels);

        log.info("Grid strategy started: strategyId={}, userId={}, instrumentId={}, grids={}",
                strategyId, userId, request.instrumentId(), request.gridCount());
        return record;
    }

    /**
     * 查询指定用户的网格策略列表。
     */
    public List<GridStrategyRecord> listStrategies(long userId) {
        return strategies.values().stream()
                .filter(s -> s.userId() == userId)
                .toList();
    }

    /**
     * 停止网格并撤销所有活动挂单。
     */
    public GridStrategyRecord stopGrid(long userId, long strategyId) {
        GridStrategyRecord record = strategies.get(strategyId);
        if (record == null || record.userId() != userId) {
            throw new IllegalArgumentException("Grid strategy not found");
        }

        List<GridLevel> levels = strategyLevels.get(strategyId);
        if (levels != null) {
            for (GridLevel level : levels) {
                if (level.orderId() > 0 && "PLACED".equalsIgnoreCase(level.status())) {
                    try {
                        orderService.cancel(new CancelOrderRequest(userId, level.orderId()));
                    } catch (Exception ex) {
                        log.debug("Cancel grid order error: {}", ex.getMessage());
                    }
                }
            }
        }

        GridStrategyRecord stopped = new GridStrategyRecord(
                record.strategyId(), record.userId(), record.instrumentId(), record.productLine(),
                record.lowerPriceTicks(), record.upperPriceTicks(), record.gridCount(),
                record.isArithmetic(), record.totalInvestmentUnits(),
                "STOPPED", record.totalProfitUnits(), record.matchedTradeCount(), record.createdAt()
        );
        strategies.put(strategyId, stopped);
        log.info("Grid strategy stopped: strategyId={}, userId={}", strategyId, userId);
        return stopped;
    }

    public List<Long> generatePriceGrid(long lower, long upper, int count, boolean isArithmetic) {
        List<Long> grid = new ArrayList<>(count);
        if (isArithmetic) {
            long step = Math.max(1L, (upper - lower) / count);
            for (int i = 0; i < count; i++) {
                grid.add(lower + i * step);
            }
        } else {
            double ratio = Math.pow((double) upper / (double) lower, 1.0 / count);
            for (int i = 0; i < count; i++) {
                long price = Math.round(lower * Math.pow(ratio, i));
                grid.add(price);
            }
        }
        return grid;
    }
}
