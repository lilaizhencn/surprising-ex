package com.surprising.instrument.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Gateway 进程内共享的合约快照缓存配置。
 */
@Configuration("gatewayInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    @Primary
    public InstrumentSnapshotCache gatewayInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
