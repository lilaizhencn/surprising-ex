package com.surprising.adl.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ADL 服务使用的合约快照基础组件。
 */
@Configuration("adlInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache adlInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
