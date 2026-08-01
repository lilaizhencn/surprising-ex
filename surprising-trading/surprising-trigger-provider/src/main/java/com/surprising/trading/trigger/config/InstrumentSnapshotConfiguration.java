package com.surprising.trading.trigger.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 条件单服务使用的合约快照基础组件。
 */
@Configuration("triggerInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache triggerInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
