package com.surprising.marketmaker.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/** 做市服务使用的合约不可变 JVM 快照。 */
@Configuration
public class InstrumentSnapshotConfiguration {

    @Bean
    @ConditionalOnMissingBean(InstrumentSnapshotCache.class)
    public InstrumentSnapshotCache marketMakerInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
