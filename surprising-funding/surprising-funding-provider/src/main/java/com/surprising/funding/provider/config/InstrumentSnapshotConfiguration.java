package com.surprising.funding.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 资金费服务使用的合约不可变 JVM 快照。 */
@Configuration("fundingInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    public InstrumentSnapshotCache fundingInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }

}
