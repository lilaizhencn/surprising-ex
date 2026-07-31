package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.repository.OrderInstrumentLifecycleFenceRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderInstrumentLifecycleFenceService {

    private final OrderInstrumentLifecycleFenceRepository repository;

    public OrderInstrumentLifecycleFenceService(OrderInstrumentLifecycleFenceRepository repository) {
        this.repository = repository;
    }

    /**
     * 与排空操作锁定同一行，防止排空确认与正在提交的新订单交错。
     */
    @Transactional
    public void requirePlacementAllowed(ProductLine productLine, String symbol) {
        repository.lockForPlacement(productLine, symbol, Instant.now());
    }

    /**
     * 先提交永久关闭栅栏，后续新订单会在数据库事务内被拒绝。
     */
    @Transactional
    public void blockForSettlement(ProductLine productLine, String symbol, long instrumentVersion) {
        repository.blockForSettlement(productLine, symbol, instrumentVersion, Instant.now());
    }
}
