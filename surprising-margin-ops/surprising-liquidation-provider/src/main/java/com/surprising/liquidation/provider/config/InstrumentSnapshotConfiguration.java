package com.surprising.liquidation.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 强平服务使用的合约快照基础组件。
 */
@Configuration
public class InstrumentSnapshotConfiguration {

    @Bean
    @ConditionalOnMissingBean(InstrumentSnapshotCache.class)
    public InstrumentSnapshotCache liquidationInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
