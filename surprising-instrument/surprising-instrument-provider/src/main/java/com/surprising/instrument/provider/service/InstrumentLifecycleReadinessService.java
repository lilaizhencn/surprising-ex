package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent;
import com.surprising.instrument.provider.repository.InstrumentLifecycleDrainRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstrumentLifecycleReadinessService {

    private final InstrumentLifecycleDrainRepository drainRepository;

    public InstrumentLifecycleReadinessService(InstrumentLifecycleDrainRepository drainRepository) {
        this.drainRepository = drainRepository;
    }

    @Transactional
    public void acknowledge(InstrumentLifecycleDrainEvent event) {
        drainRepository.acknowledge(event, Instant.now());
    }

    @Transactional(readOnly = true)
    public boolean isReady(ProductLine productLine, String symbol, long instrumentVersion) {
        return drainRepository.isReady(productLine, symbol, instrumentVersion);
    }
}
