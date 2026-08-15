package com.surprising.price.mark.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 标记价格服务使用的合约快照基础组件。
 */
@Configuration("markInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache markInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
