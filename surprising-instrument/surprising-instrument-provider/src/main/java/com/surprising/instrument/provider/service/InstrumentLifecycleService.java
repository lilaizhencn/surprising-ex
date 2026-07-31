package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.provider.config.InstrumentProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
    public class InstrumentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentLifecycleService.class);

    private final InstrumentStorageService storageService;
    private final InstrumentService instrumentService;
    private final InstrumentProperties properties;

    public InstrumentLifecycleService(InstrumentStorageService storageService,
                                      InstrumentService instrumentService,
                                      InstrumentProperties properties) {
        this.storageService = storageService;
        this.instrumentService = instrumentService;
        this.properties = properties;
    }

    public void advanceLifecycle() {
        if (!properties.getLifecycle().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        int batchSize = Math.max(1, properties.getLifecycle().getBatchSize());
        markExpiredContractsSettling(now, batchSize);
        closeSettledContracts(now, batchSize);
    }

    private void markExpiredContractsSettling(Instant now, int batchSize) {
        for (InstrumentResponse instrument : storageService.expiringContractsDue(now, batchSize)) {
            try {
                instrumentService.updateStatus(instrument.symbol(), InstrumentStatus.SETTLING);
            } catch (Exception ex) {
                log.error("Failed to mark expired instrument settling: symbol={} version={}",
                        instrument.symbol(), instrument.version(), ex);
            }
        }
    }

    private void closeSettledContracts(Instant now, int batchSize) {
        for (InstrumentResponse instrument : storageService.settlingContractsDue(now, batchSize)) {
            try {
                InstrumentResponse closed = instrumentService.updateStatus(instrument.symbol(), InstrumentStatus.CLOSED);
                instrumentService.publishProductLifecycleEvent(closed);
            } catch (Exception ex) {
                log.error("Failed to close settled instrument: symbol={} version={}",
                        instrument.symbol(), instrument.version(), ex);
            }
        }
    }
}
