package com.surprising.trading.order.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.OrderRecord;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 下单保证金计算需要的本地状态快照。
 *
 * <p>快照只在订单和仓位事件成功到达后更新，不能替代数据库的最终落账。重建完成前
 * lookup 返回空值，调用方必须失败关闭，不能把下单请求转成数据库查询。</p>
 */
@Component
public class OrderMarginSnapshotCache {

    private final ConcurrentMap<PositionKey, PositionValue> positions = new ConcurrentHashMap<>();
    private final ConcurrentMap<LeverageKey, LeverageValue> leverages = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, OrderValue> orders = new ConcurrentHashMap<>();
    private final ConcurrentMap<OrderScope, Long> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<ReduceOnlyScope, Long> pendingReduceOnly = new ConcurrentHashMap<>();
    private final Set<ProductLine> readyLines = ConcurrentHashMap.newKeySet();

    public void markNotReady(ProductLine productLine) {
        if (productLine == null) {
            return;
        }
        markOrderProjectionNotReady(productLine);
        positions.keySet().removeIf(key -> key.productLine() == productLine);
        leverages.keySet().removeIf(key -> key.productLine() == productLine);
    }

    public void markOrderProjectionNotReady(ProductLine productLine) {
        if (productLine == null) {
            return;
        }
        readyLines.remove(productLine);
        orders.entrySet().removeIf(entry -> entry.getValue().productLine() == productLine);
        pending.keySet().removeIf(key -> key.productLine() == productLine);
        pendingReduceOnly.keySet().removeIf(key -> key.productLine() == productLine);
    }

    public void markReady(ProductLine productLine) {
        if (productLine != null) {
            readyLines.add(productLine);
        }
    }

    public void markOrderProjectionReady(ProductLine productLine) {
        markReady(productLine);
    }

    public boolean ready(ProductLine productLine) {
        return productLine != null && readyLines.contains(productLine);
    }

    /**
     * 记录完整仓位事件，旧修订不能覆盖新状态。
     *
     * <p>同一修订号的重复事件必须幂等忽略；如果同一修订号携带不同状态，说明
     * 事件流或恢复快照已经分叉，不能让最后到达的消息覆盖先到达的状态。</p>
     */
    public ApplyResult applyPosition(PositionUpdatedEvent event) {
        if (event == null) {
            return ApplyResult.IGNORED;
        }
        PositionKey key = new PositionKey(event.productLine(), event.userId(), event.symbol(),
                event.marginMode(), event.positionSide());
        final ApplyResult[] result = {ApplyResult.APPLIED};
        positions.compute(key, (ignored, previous) -> {
            if (previous != null && event.revision() < previous.revision()) {
                result[0] = ApplyResult.STALE;
                return previous;
            }
            if (previous != null && event.revision() == previous.revision()) {
                result[0] = samePosition(previous, event) ? ApplyResult.STALE : ApplyResult.CONFLICT;
                return previous;
            }
            return new PositionValue(event.revision(), event.instrumentVersion(), event.signedQuantitySteps());
        });
        return result[0];
    }

    /** 首次数据库兜底成功后补齐本地仓位，后续订单可直接使用事件快照。 */
    public void putPosition(ProductLine productLine,
                            long userId,
                            String symbol,
                            MarginMode marginMode,
                            PositionSide positionSide,
                            long instrumentVersion,
                            long signedQuantitySteps) {
        if (productLine == null || userId <= 0L || instrumentVersion <= 0L) {
            return;
        }
        PositionKey key = new PositionKey(productLine, userId, symbol, marginMode, positionSide);
        positions.compute(key, (ignored, previous) -> previous == null || previous.revision() == 0L
                ? new PositionValue(0L, instrumentVersion, signedQuantitySteps) : previous);
    }

    /** 仅在启动恢复没有对应仓位行时补入零仓位。 */
    public void putPositionIfAbsent(ProductLine productLine,
                                    long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    PositionSide positionSide,
                                    long instrumentVersion) {
        if (productLine == null || userId <= 0L || instrumentVersion <= 0L) {
            return;
        }
        positions.putIfAbsent(new PositionKey(productLine, userId, symbol, marginMode, positionSide),
                new PositionValue(0L, instrumentVersion, 0L));
    }

    /** 记录订单投影，并按订单修订维护未成交数量，支持撤单请求仍占用容量。 */
    /**
     * 记录订单投影，并按订单修订维护未成交数量。
     *
     * <p>同一修订号的不同订单状态不是正常的重放，而是投影分叉；调用方必须暂停
     * 当前产品线快照并等待重建。</p>
     */
    public ApplyResult applyOrder(OrderRecord order) {
        if (order == null) {
            return ApplyResult.IGNORED;
        }
        putPositionIfAbsent(order.productLine(), order.userId(), order.symbol(), order.marginMode(),
                order.positionSide(), order.instrumentVersion());
        putDefaultLeverageIfAbsent(order.productLine(), order.userId(), order.symbol(), order.marginMode());
        OrderValue next = new OrderValue(order.productLine(), order.userId(), order.symbol(), order.marginMode(),
                order.positionSide(), order.instrumentVersion(), order.side(), order.reduceOnly(), order.status().name(),
                order.remainingQuantitySteps(), order.revision());
        final ApplyResult[] result = {ApplyResult.APPLIED};
        orders.compute(order.orderId(), (ignored, previous) -> {
            if (previous != null && previous.revision() > next.revision()) {
                result[0] = ApplyResult.STALE;
                return previous;
            }
            if (previous != null && previous.revision() == next.revision()) {
                result[0] = sameOrder(previous, next) ? ApplyResult.STALE : ApplyResult.CONFLICT;
                return previous;
            }
            if (previous != null) {
                adjustPending(previous, -1L);
                adjustPendingReduceOnly(previous, -1L);
            }
            adjustPending(next, 1L);
            adjustPendingReduceOnly(next, 1L);
            return next;
        });
        return result[0];
    }

    /**
     * 读取只减仓校验需要的持仓和未完成只减仓数量。
     *
     * <p>返回空值表示该产品线快照尚未就绪，调用方必须拒绝请求，不能再回查
     * account_positions 或 trading_orders。</p>
     */
    public Optional<ReduceOnlySnapshot> lookupReduceOnly(ProductLine productLine,
                                                         long userId,
                                                         String symbol,
                                                         MarginMode marginMode,
                                                         PositionSide positionSide,
                                                         OrderSide closeSide) {
        if (!ready(productLine)) {
            return Optional.empty();
        }
        PositionKey positionKey = new PositionKey(productLine, userId, symbol, marginMode, positionSide);
        PositionValue position = positions.get(positionKey);
        if (position == null) {
            return Optional.empty();
        }
        ReduceOnlyScope scope = new ReduceOnlyScope(productLine, userId, symbol, marginMode, positionSide,
                position.instrumentVersion(), closeSide);
        return Optional.of(new ReduceOnlySnapshot(position.instrumentVersion(), position.signedQuantitySteps(),
                pendingReduceOnly.getOrDefault(scope, 0L)));
    }

    public void putLeverage(ProductLine productLine,
                            long userId,
                            String symbol,
                            MarginMode marginMode,
                            Long leveragePpm) {
        if (productLine == null || userId <= 0L || symbol == null || symbol.isBlank()) {
            return;
        }
        leverages.put(new LeverageKey(productLine, userId, symbol.trim().toUpperCase(), marginMode),
                new LeverageValue(true, leveragePpm));
    }

    /** 为没有用户特殊设置的用户预置“使用合约默认杠杆”的已知状态。 */
    public void putDefaultLeverageIfAbsent(ProductLine productLine,
                                           long userId,
                                           String symbol,
                                           MarginMode marginMode) {
        if (productLine == null || userId <= 0L || symbol == null || symbol.isBlank()) {
            return;
        }
        leverages.putIfAbsent(new LeverageKey(productLine, userId, symbol.trim().toUpperCase(), marginMode),
                new LeverageValue(true, null));
    }

    public Optional<MarginSnapshot> lookup(ProductLine productLine,
                                           long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide,
                                           OrderSide side) {
        if (!ready(productLine)) {
            return Optional.empty();
        }
        PositionKey positionKey = new PositionKey(productLine, userId, symbol, marginMode, positionSide);
        PositionValue position = positions.get(positionKey);
        LeverageValue leverage = leverages.get(new LeverageKey(productLine, userId,
                symbol, marginMode));
        if (position == null || leverage == null) {
            return Optional.empty();
        }
        OrderScope scope = new OrderScope(productLine, userId, symbol, marginMode, positionSide, side);
        return Optional.of(new MarginSnapshot(position.instrumentVersion(), position.signedQuantitySteps(),
                pending.getOrDefault(scope, 0L), leverage.leveragePpm()));
    }

    private void adjustPending(OrderValue order, long multiplier) {
        if (!isPending(order)) {
            return;
        }
        OrderScope scope = new OrderScope(order.productLine(), order.userId(), order.symbol(), order.marginMode(),
                order.positionSide(), order.side());
        long delta = Math.multiplyExact(order.remainingQuantitySteps(), multiplier);
        pending.compute(scope, (ignored, current) -> {
            long value = Math.addExact(current == null ? 0L : current, delta);
            return value <= 0L ? null : value;
        });
    }

    private void adjustPendingReduceOnly(OrderValue order, long multiplier) {
        if (!isOpen(order) || !order.reduceOnly() || order.instrumentVersion() <= 0L) {
            return;
        }
        ReduceOnlyScope scope = new ReduceOnlyScope(order.productLine(), order.userId(), order.symbol(),
                order.marginMode(), order.positionSide(), order.instrumentVersion(), order.side());
        long delta = Math.multiplyExact(order.remainingQuantitySteps(), multiplier);
        pendingReduceOnly.compute(scope, (ignored, current) -> {
            long value = Math.addExact(current == null ? 0L : current, delta);
            return value <= 0L ? null : value;
        });
    }

    private boolean isPending(OrderValue order) {
        return isOpen(order) && !order.reduceOnly();
    }

    private boolean isOpen(OrderValue order) {
        return order.remainingQuantitySteps() > 0L
                && ("ACCEPTED".equals(order.status()) || "PARTIALLY_FILLED".equals(order.status())
                || "CANCEL_REQUESTED".equals(order.status()));
    }

    public record MarginSnapshot(long instrumentVersion,
                                 long currentSignedQuantitySteps,
                                 long pendingSameSideSteps,
                                 Long configuredLeveragePpm) {
    }

    public record ReduceOnlySnapshot(long instrumentVersion,
                                     long signedQuantitySteps,
                                     long pendingReduceOnlySteps) {
    }

    public enum ApplyResult {
        APPLIED,
        STALE,
        CONFLICT,
        IGNORED
    }

    private boolean samePosition(PositionValue previous, PositionUpdatedEvent event) {
        return previous.instrumentVersion() == event.instrumentVersion()
                && previous.signedQuantitySteps() == event.signedQuantitySteps();
    }

    private boolean sameOrder(OrderValue previous, OrderValue next) {
        return previous.productLine() == next.productLine()
                && previous.userId() == next.userId()
                && previous.symbol().equals(next.symbol())
                && previous.marginMode() == next.marginMode()
                && previous.positionSide() == next.positionSide()
                && previous.instrumentVersion() == next.instrumentVersion()
                && previous.side() == next.side()
                && previous.reduceOnly() == next.reduceOnly()
                && previous.status().equals(next.status())
                && previous.remainingQuantitySteps() == next.remainingQuantitySteps();
    }

    private record PositionKey(ProductLine productLine, long userId, String symbol,
                               MarginMode marginMode, PositionSide positionSide) {
        private PositionKey {
            symbol = symbol == null ? "" : symbol.trim().toUpperCase();
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }

    private record OrderScope(ProductLine productLine, long userId, String symbol,
                              MarginMode marginMode, PositionSide positionSide, OrderSide side) {
        private OrderScope {
            symbol = symbol == null ? "" : symbol.trim().toUpperCase();
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
            side = side == null ? OrderSide.BUY : side;
        }
    }

    private record ReduceOnlyScope(ProductLine productLine, long userId, String symbol,
                                   MarginMode marginMode, PositionSide positionSide,
                                   long instrumentVersion, OrderSide side) {
        private ReduceOnlyScope {
            symbol = symbol == null ? "" : symbol.trim().toUpperCase();
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
            side = side == null ? OrderSide.BUY : side;
        }
    }

    private record LeverageKey(ProductLine productLine, long userId, String symbol, MarginMode marginMode) {
        private LeverageKey {
            symbol = symbol == null ? "" : symbol.trim().toUpperCase();
            marginMode = MarginMode.defaultIfNull(marginMode);
        }
    }

    private record PositionValue(long revision, long instrumentVersion, long signedQuantitySteps) {
    }

    private record LeverageValue(boolean known, Long leveragePpm) {
    }

    private record OrderValue(ProductLine productLine, long userId, String symbol, MarginMode marginMode,
                              PositionSide positionSide, long instrumentVersion,
                              com.surprising.trading.api.model.OrderSide side,
                              boolean reduceOnly, String status, long remainingQuantitySteps, long revision) {
        private OrderValue {
            symbol = symbol == null ? "" : symbol.trim().toUpperCase();
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }
}
