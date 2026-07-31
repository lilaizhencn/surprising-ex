package com.surprising.trading.matching.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * 撮合服务使用的合约快照基础组件。
 */
@Configuration
public class InstrumentSnapshotConfiguration {

    @Bean
    @ConditionalOnMissingBean(InstrumentSnapshotCache.class)
    public InstrumentSnapshotCache matchingInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
