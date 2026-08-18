package com.surprising.derivatives.lifecycle;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DerivativesInstrumentSnapshotConfiguration {

    @Bean(name = "derivativesInstrumentSnapshotCache")
    public InstrumentSnapshotCache derivativesInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
