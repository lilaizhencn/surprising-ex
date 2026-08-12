package com.surprising.insurance.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 保险基金服务使用的合约快照基础组件。
 */
@Configuration("insuranceInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache insuranceInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
