package com.surprising.risk.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 风险服务使用的合约快照基础组件。
 */
@Configuration("riskInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache riskInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
