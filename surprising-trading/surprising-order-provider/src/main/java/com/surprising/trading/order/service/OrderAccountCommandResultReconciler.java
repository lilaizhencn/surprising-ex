package com.surprising.trading.order.service;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderAccountCommandResultRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
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

    @Scheduled(fixedDelayString = "${surprising.trading.order.account-result-reconcile-delay-ms:1000}")
    public void reconcile() {
        orderService.processAccountCommandResults(repository.terminalPendingOrderReservations(
                properties.getKafka().getProductLine(), 500));
    }
}
