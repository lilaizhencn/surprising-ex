package com.surprising.trading.order.service;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderAccountCommandResultRepository;
/**
 * 旧订单数据库结果核对器，仅保留历史测试类型；生产订单结果由用户分区 WAL 消费器处理。
 */
@Deprecated(forRemoval = true)
public class OrderAccountCommandResultReconciler {

    private final TradingOrderProperties properties;
    private final OrderAccountCommandResultRepository repository;
    private final OrderService orderService;

    public OrderAccountCommandResultReconciler(TradingOrderProperties properties,
                                               OrderAccountCommandResultRepository repository,
                                               OrderService orderService) {
        this.properties = properties;
        this.repository = repository;
        this.orderService = orderService;
    }

    public void reconcile() {
        orderService.processAccountCommandResults(repository.terminalPendingOrderReservations(
                properties.getKafka().getProductLine(), 500));
    }
}
