package com.surprising.price.index.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指数价格服务使用的合约快照基础组件。
 */
@Configuration("indexInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache indexInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
