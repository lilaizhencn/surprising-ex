package com.surprising.account.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * 账户服务使用的合约快照基础组件。
 */
@Configuration
public class InstrumentSnapshotConfiguration {

    @Bean
    @ConditionalOnMissingBean(InstrumentSnapshotCache.class)
    public InstrumentSnapshotCache accountInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
