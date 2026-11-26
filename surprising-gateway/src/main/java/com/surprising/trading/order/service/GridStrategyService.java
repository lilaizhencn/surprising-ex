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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class GridStrategyService {
    private static final Logger log = LoggerFactory.getLogger(GridStrategyService.class);

    private final OrderService orderService;
    private final AtomicLong strategyIdGenerator = new AtomicLong(System.currentTimeMillis() % 10000000);
    private final Map<Long, GridStrategyRecord> strategies = new ConcurrentHashMap<>();
    private final Map<Long, List<GridLevel>> strategyLevels = new ConcurrentHashMap<>();
    private final Map<String, String> levelClientOrderIds = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock reconcileLock = new java.util.concurrent.locks.ReentrantLock();

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
        if (request.gridCount() < 2 || request.gridCount() > 150) {
            throw new IllegalArgumentException("gridCount must be between 2 and 150");
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
            levelClientOrderIds.put(levelKey(strategyId, i), clientOrderId);

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
     * 后台全局检索网格策略机器人（可按合约/交易对、状态和用户过滤）。
     */
    public List<GridStrategyRecord> listAllStrategies(String symbolOrInstrumentId, String status, Long userId) {
        String needle = symbolOrInstrumentId == null || symbolOrInstrumentId.isBlank()
                ? null : symbolOrInstrumentId.trim().toUpperCase(Locale.ROOT);
        String expectedStatus = status == null || status.isBlank()
                ? null : status.trim().toUpperCase(Locale.ROOT);
        return strategies.values().stream()
                .filter(s -> userId == null || s.userId() == userId)
                .filter(s -> needle == null || s.instrumentId().toUpperCase(Locale.ROOT).contains(needle))
                .filter(s -> {
                    if (expectedStatus == null) return true;
                    String actual = "ACTIVE".equalsIgnoreCase(s.status()) ? "RUNNING" : s.status().toUpperCase(Locale.ROOT);
                    return actual.equals(expectedStatus) || s.status().equalsIgnoreCase(expectedStatus);
                })
                .sorted(Comparator.comparing(GridStrategyRecord::createdAt).reversed()
                        .thenComparing(Comparator.comparingLong(GridStrategyRecord::strategyId).reversed()))
                .toList();
    }

    /**
     * 管理员紧急强制终止任意用户的网格机器人并撤销活动挂单。
     */
    public GridStrategyRecord adminStopGrid(long strategyId) {
        GridStrategyRecord record = strategies.get(strategyId);
        if (record == null) {
            throw new IllegalArgumentException("Grid strategy not found: " + strategyId);
        }
        return stopGrid(record.userId(), strategyId);
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

    /**
     * 查询单个网格策略及各档位挂单详情。
     */
    public GridStrategyDetailResponse getDetail(long userId, long strategyId) {
        GridStrategyRecord record = strategies.get(strategyId);
        if (record == null || record.userId() != userId) {
            throw new IllegalArgumentException("Grid strategy not found");
        }
        List<GridLevel> levels = strategyLevels.getOrDefault(strategyId, List.of());
        return new GridStrategyDetailResponse(record, List.copyOf(levels));
    }

    /**
     * 定时巡检运行中的网格策略：检测已成交挂单并自动在相邻档位补挂反向单。
     */
    @Scheduled(fixedDelay = 5000)
    public void reconcileActiveGrids() {
        if (!reconcileLock.tryLock()) {
            return;
        }
        try {
            for (GridStrategyRecord record : strategies.values()) {
                if (!"ACTIVE".equalsIgnoreCase(record.status()) && !"RUNNING".equalsIgnoreCase(record.status())) {
                    continue;
                }
                try {
                    reconcileStrategy(record.strategyId());
                } catch (Exception ex) {
                    log.error("Grid strategy reconcile failed strategyId={} userId={}: {}",
                            record.strategyId(), record.userId(), ex.getMessage(), ex);
                }
            }
        } finally {
            reconcileLock.unlock();
        }
    }

    void reconcileStrategy(long strategyId) {
        GridStrategyRecord record = strategies.get(strategyId);
        List<GridLevel> levels = strategyLevels.get(strategyId);
        if (record == null || levels == null || levels.isEmpty()) {
            return;
        }
        int matchedDelta = 0;
        long profitDelta = 0L;
        List<Integer> newlyFilled = new ArrayList<>();
        // Pass 1: 同步各档位挂单最新终态，避免相邻档位因遍历顺序错过补单时机
        for (int i = 0; i < levels.size(); i++) {
            GridLevel level = levels.get(i);
            if (!"PLACED".equalsIgnoreCase(level.status())) {
                continue;
            }
            String clientOrderId = levelClientOrderIds.get(levelKey(strategyId, i));
            if (clientOrderId == null) {
                continue;
            }
            OrderResponse order;
            try {
                order = orderService.getByClientOrderId(record.userId(), clientOrderId, null);
            } catch (Exception ex) {
                continue;
            }
            if (order == null) {
                continue;
            }
            if (order.status() == OrderStatus.FILLED) {
                levels.set(i, new GridLevel(level.levelIndex(), level.priceTicks(), level.side(),
                        level.quantitySteps(), level.orderId(), "FILLED"));
                matchedDelta++;
                newlyFilled.add(i);
                if (OrderSide.SELL.name().equalsIgnoreCase(level.side()) && i > 0) {
                    long spread = Math.max(0L, level.priceTicks() - levels.get(i - 1).priceTicks());
                    profitDelta += spread * level.quantitySteps();
                }
            } else if (order.status() == OrderStatus.CANCELED || order.status() == OrderStatus.REJECTED) {
                levels.set(i, new GridLevel(level.levelIndex(), level.priceTicks(), level.side(),
                        level.quantitySteps(), level.orderId(), order.status().name()));
            }
        }
        // Pass 2: 为本轮新成交的档位在相邻格补挂反向单
        for (int i : newlyFilled) {
            GridLevel level = levels.get(i);
            boolean filledBuy = OrderSide.BUY.name().equalsIgnoreCase(level.side());
            int counterIdx = filledBuy ? i + 1 : i - 1;
            if (counterIdx >= 0 && counterIdx < levels.size()) {
                GridLevel target = levels.get(counterIdx);
                if (!"PLACED".equalsIgnoreCase(target.status())) {
                    OrderSide counterSide = filledBuy ? OrderSide.SELL : OrderSide.BUY;
                    String nextClientId = "grid-" + strategyId + "-" + counterIdx + "-" + System.nanoTime() % 1000000;
                    levelClientOrderIds.put(levelKey(strategyId, counterIdx), nextClientId);
                    try {
                        OrderResponse placed = orderService.place(new PlaceOrderRequest(
                                record.userId(), nextClientId, record.instrumentId(),
                                counterSide, OrderType.LIMIT, TimeInForce.GTC,
                                target.priceTicks(), target.quantitySteps(),
                                MarginMode.CROSS, PositionSide.NET, false, true
                        ));
                        if (placed != null && placed.orderId() > 0) {
                            levels.set(counterIdx, new GridLevel(
                                    target.levelIndex(), target.priceTicks(), counterSide.name(),
                                    target.quantitySteps(), placed.orderId(), "PLACED"
                            ));
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to place counter grid order strategyId={} level={}: {}",
                                strategyId, counterIdx, ex.getMessage());
                    }
                }
            }
        }
        if (matchedDelta > 0 || profitDelta > 0) {
            strategies.put(strategyId, new GridStrategyRecord(
                    record.strategyId(), record.userId(), record.instrumentId(), record.productLine(),
                    record.lowerPriceTicks(), record.upperPriceTicks(), record.gridCount(),
                    record.isArithmetic(), record.totalInvestmentUnits(),
                    record.status(), record.totalProfitUnits() + profitDelta,
                    record.matchedTradeCount() + matchedDelta, record.createdAt()
            ));
        }
    }

    private static String levelKey(long strategyId, int levelIndex) {
        return strategyId + ":" + levelIndex;
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
