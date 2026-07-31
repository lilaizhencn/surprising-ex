package com.surprising.trading.trigger.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.trigger.repository.TriggerInstrumentLifecycleFenceRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TriggerInstrumentLifecycleFenceService {

    private final TriggerInstrumentLifecycleFenceRepository repository;

    public TriggerInstrumentLifecycleFenceService(TriggerInstrumentLifecycleFenceRepository repository) {
        this.repository = repository;
    }

    /**
     * 与排空操作锁定同一行，防止排空确认与正在提交的新触发单交错。
     */
    @Transactional
    public void requirePlacementAllowed(ProductLine productLine, String symbol) {
        repository.lockForPlacement(productLine, symbol, Instant.now());
    }

    /**
     * 先提交永久关闭栅栏，后续新触发单会在数据库事务内被拒绝。
     */
    @Transactional
    public void blockForSettlement(ProductLine productLine, String symbol, long instrumentVersion) {
        repository.blockForSettlement(productLine, symbol, instrumentVersion, Instant.now());
    }
}
