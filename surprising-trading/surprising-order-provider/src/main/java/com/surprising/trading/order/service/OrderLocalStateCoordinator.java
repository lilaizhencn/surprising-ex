package com.surprising.trading.order.service;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 重建订单下单所需的本地 JVM 快照。
 *
 * <p>订单用户状态和账户完整快照都来自本地 RocksDB/Kafka 快照，数据库投影不参与启动恢复，
 * 也不能被当成缓存缺失时的回退来源。快照未准备好时保持失败关闭，避免用旧订单或旧仓位
 * 计算保证金。</p>
 */
@Component
public class OrderLocalStateCoordinator {

    private final TradingOrderProperties properties;
    private final OrderUserStateService orderUserStateService;
    private final OrderMarginSnapshotCache marginSnapshotCache;
    private final PerpetualAccountStateSnapshotCache accountSnapshotCache;
    private final LeverageSnapshotInitializer leverageSnapshotInitializer;

    public OrderLocalStateCoordinator(TradingOrderProperties properties,
                                      OrderUserStateService orderUserStateService,
                                      OrderMarginSnapshotCache marginSnapshotCache,
                                      PerpetualAccountStateSnapshotCache accountSnapshotCache,
                                      LeverageSnapshotInitializer leverageSnapshotInitializer) {
        this.properties = properties;
        this.orderUserStateService = orderUserStateService;
        this.marginSnapshotCache = marginSnapshotCache;
        this.accountSnapshotCache = accountSnapshotCache;
        this.leverageSnapshotInitializer = leverageSnapshotInitializer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        reconcile();
    }

    /** 按本地事实状态重建保证金索引；任何异常都让当前产品线保持不可下单。 */
    public synchronized void reconcile() {
        ProductLine line = properties.getKafka().getProductLine();
        if (marginSnapshotCache.ready(line)) {
            return;
        }
        if (!line.supportsUserPositionMarginFlow() || !accountSnapshotCache.ready()) {
            marginSnapshotCache.markNotReady(line);
            return;
        }
        try {
            marginSnapshotCache.markNotReady(line);
            leverageSnapshotInitializer.initialize(line);
            for (var snapshot : accountSnapshotCache.states()) {
                marginSnapshotCache.applyAccountSnapshot(snapshot);
            }
            for (var order : orderUserStateService.localOrders(line)) {
                OrderMarginSnapshotCache.ApplyResult result = marginSnapshotCache.applyOrder(order);
                if (result == OrderMarginSnapshotCache.ApplyResult.CONFLICT) {
                    throw new IllegalStateException("订单本地快照同一修订号内容冲突");
                }
            }
            marginSnapshotCache.markAccountSnapshotReady(line);
            marginSnapshotCache.markOrderProjectionReady(line);
        } catch (RuntimeException ex) {
            marginSnapshotCache.markNotReady(line);
        }
    }
}
