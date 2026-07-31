package com.surprising.trading.matching.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.RecoveredOrderBookOrder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 撮合保护的本地热索引。
 *
 * <p>索引只保存仍在订单簿中的最小字段，服务重启时由数据库恢复快照重建。数据库仍然是最终
 * 状态和审计来源；索引未就绪时调用方必须回退到数据库，避免启动窗口产生漏检。</p>
 */
@Component
public class MatchingProtectionIndex {

    private final ProductLine productLine;
    private final ConcurrentMap<Long, OpenOrder> orders = new ConcurrentHashMap<>();
    private final ConcurrentMap<UserSymbolKey, Set<Long>> userOrders = new ConcurrentHashMap<>();
    private final ConcurrentMap<SymbolKey, Set<Long>> symbolOrders = new ConcurrentHashMap<>();
    private volatile boolean ready;

    public MatchingProtectionIndex(MatchingProperties properties) {
        MatchingProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        this.productLine = kafka != null && kafka.isProductTopicsEnabled() ? kafka.getProductLine() : null;
    }

    public boolean ready() {
        return ready;
    }

    public void markReady() {
        ready = true;
    }

    public void markNotReady() {
        ready = false;
    }

    public void restore(RecoveredOrderBookOrder order) {
        if (order == null || order.remainingQuantitySteps() <= 0) {
            return;
        }
        put(new OpenOrder(order.orderId(), order.userId(), order.symbol(), order.instrumentVersion(),
                order.side(), order.priceTicks(), order.remainingQuantitySteps()));
    }

    public boolean wouldSelfTrade(long userId,
                                  String symbol,
                                  long instrumentVersion,
                                  OrderSide side,
                                  long effectivePriceTicks) {
        if (symbol == null || side == null) {
            return false;
        }
        Set<Long> ids = userOrders.get(new UserSymbolKey(productLine, userId, symbol));
        if (ids == null) {
            return false;
        }
        OrderSide opposite = side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        for (Long id : ids) {
            OpenOrder order = orders.get(id);
            if (order == null || order.instrumentVersion() != instrumentVersion
                    || order.side() != opposite || order.remainingQuantitySteps() <= 0) {
                continue;
            }
            if (side == OrderSide.BUY && order.priceTicks() <= effectivePriceTicks) {
                return true;
            }
            if (side == OrderSide.SELL && order.priceTicks() >= effectivePriceTicks) {
                return true;
            }
        }
        return false;
    }

    public Set<Long> commandsThatWouldSelfTrade(List<OrderCommandEvent> commands) {
        if (commands == null || commands.isEmpty()) {
            return Set.of();
        }
        Set<Long> conflicts = new LinkedHashSet<>();
        for (OrderCommandEvent command : commands) {
            if (command == null || wouldSelfTrade(command.userId(), command.symbol(), command.instrumentVersion(),
                    command.side(), command.priceTicks())) {
                if (command != null) {
                    conflicts.add(command.commandId());
                }
            }
        }
        return Set.copyOf(conflicts);
    }

    public boolean hasOpenOrdersWithDifferentInstrumentVersion(String symbol,
                                                               long instrumentVersion,
                                                               long orderId) {
        if (symbol == null) {
            return false;
        }
        Set<Long> ids = symbolOrders.get(new SymbolKey(productLine, symbol));
        if (ids == null) {
            return false;
        }
        for (Long id : ids) {
            OpenOrder order = orders.get(id);
            if (order != null && order.orderId() != orderId
                    && order.instrumentVersion() != instrumentVersion
                    && order.remainingQuantitySteps() > 0) {
                return true;
            }
        }
        return false;
    }

    public Set<Long> commandsWithOpenOrdersAtDifferentInstrumentVersion(List<OrderCommandEvent> commands) {
        if (commands == null || commands.isEmpty()) {
            return Set.of();
        }
        Set<Long> conflicts = new LinkedHashSet<>();
        for (OrderCommandEvent command : commands) {
            if (command != null && hasOpenOrdersWithDifferentInstrumentVersion(
                    command.symbol(), command.instrumentVersion(), command.orderId())) {
                conflicts.add(command.commandId());
            }
        }
        return Set.copyOf(conflicts);
    }

    /** 在撮合结果持久化成功后更新索引，避免把未提交状态暴露给后续命令。 */
    public void apply(OrderCommandEvent command, MatchResultEvent result) {
        if (command == null || result == null) {
            return;
        }
        for (MatchTradeEvent trade : result.trades()) {
            if (trade.makerOrderCompleted()) {
                remove(trade.makerOrderId());
            } else {
                decrement(trade.makerOrderId(), trade.quantitySteps());
            }
        }
        if (command.commandType() == OrderCommandType.CANCEL
                || isTerminal(result.orderStatus())) {
            remove(command.orderId());
            return;
        }
        long remaining = Math.subtractExact(command.quantitySteps(), result.filledQuantitySteps());
        if (remaining <= 0) {
            remove(command.orderId());
            return;
        }
        put(new OpenOrder(command.orderId(), command.userId(), command.symbol(), command.instrumentVersion(),
                command.side(), command.priceTicks(), remaining));
    }

    public int size() {
        return orders.size();
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.CANCELED || status == OrderStatus.FILLED || status == OrderStatus.REJECTED;
    }

    private void decrement(long orderId, long quantity) {
        if (quantity <= 0) {
            return;
        }
        orders.computeIfPresent(orderId, (ignored, current) -> {
            long remaining = current.remainingQuantitySteps() - quantity;
            if (remaining <= 0) {
                removeFromIndexes(current);
                return null;
            }
            return new OpenOrder(current.orderId(), current.userId(), current.symbol(), current.instrumentVersion(),
                    current.side(), current.priceTicks(), remaining);
        });
    }

    private void put(OpenOrder order) {
        if (order.remainingQuantitySteps() <= 0) {
            return;
        }
        OpenOrder previous = orders.put(order.orderId(), order);
        if (previous != null && !previous.equals(order)) {
            removeFromIndexes(previous);
        }
        userOrders.computeIfAbsent(new UserSymbolKey(productLine, order.userId(), order.symbol()), ignored ->
                ConcurrentHashMap.newKeySet()).add(order.orderId());
        symbolOrders.computeIfAbsent(new SymbolKey(productLine, order.symbol()), ignored ->
                ConcurrentHashMap.newKeySet()).add(order.orderId());
    }

    private void remove(long orderId) {
        OpenOrder previous = orders.remove(orderId);
        if (previous != null) {
            removeFromIndexes(previous);
        }
    }

    private void removeFromIndexes(OpenOrder order) {
        removeMember(userOrders, new UserSymbolKey(productLine, order.userId(), order.symbol()), order.orderId());
        removeMember(symbolOrders, new SymbolKey(productLine, order.symbol()), order.orderId());
    }

    private <K> void removeMember(ConcurrentMap<K, Set<Long>> index, K key, long orderId) {
        Set<Long> ids = index.get(key);
        if (ids != null) {
            ids.remove(orderId);
            if (ids.isEmpty()) {
                index.remove(key, ids);
            }
        }
    }

    private record OpenOrder(long orderId,
                             long userId,
                             String symbol,
                             long instrumentVersion,
                             OrderSide side,
                             long priceTicks,
                             long remainingQuantitySteps) {
    }

    private record UserSymbolKey(ProductLine productLine, long userId, String symbol) {
    }

    private record SymbolKey(ProductLine productLine, String symbol) {
    }
}
