package com.surprising.candlestick.provider.config;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * K 线服务使用的合约快照基础组件。
 */
@Configuration("candlestickInstrumentSnapshotConfiguration")
public class InstrumentSnapshotConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "candlestickInstrumentSnapshotCache")
    public InstrumentSnapshotCache candlestickInstrumentSnapshotCache() {
        return new InstrumentSnapshotCache();
    }
}
